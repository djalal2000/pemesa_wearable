package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.bharathvishal.messagecommunicationusingwearabledatalayer.databinding.ActivityMainBinding
import com.google.android.gms.wearable.*
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.concurrent.fixedRateTimer
import kotlinx.coroutines.*

class WearCommunicationManager(
    private val context: Context,
    private val binding: ActivityMainBinding
) : DataClient.OnDataChangedListener,
    MessageClient.OnMessageReceivedListener,
    CapabilityClient.OnCapabilityChangedListener {

    companion object {
        private const val TAG = "WearComm"
        private const val APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD"
        private const val MESSAGE_ITEM_RECEIVED_PATH = "/message-item-received"
        // PEMESA_WATCH_BATTERY_V1
        private const val WATCH_BATTERY_PATH = "/pemesa/watch-battery"
        private const val INFLUX_STATUS_PATH = "/pemesa/influx-status-v1"
        private const val INFLUX_EVENT_PATH = "/pemesa/influx-event-v1"
        private const val PREFS_NAME = "watch_prefs"
        private const val SAMPLING_PERIOD_MS = 20L
        private const val SEND_INTERVAL_MS = 10000L
    }

    private val sharedPref: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mobileDeviceConnected: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateConnectionStatusUI(value)
            }
        }

    var mobileNodeUri: String? = null

    private lateinit var sensorManager: SensorManager
    private var sensorThread: android.os.HandlerThread? = null
    private var sensorListener: SensorEventListener? = null
    private var accel: Sensor? = null
    private var gyro: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var hrate: Sensor? = null
    private var lightSensor: Sensor? = null
    private var pressureSensor: Sensor? = null
    private var linac: Sensor? = null
    private var rotat: Sensor? = null
    private var magnetometer: Sensor? = null
    private var stepCounter: Sensor? = null

    private var xaccel = 0f
    private var yaccel = 0f
    private var zaccel = 0f
    private var xgyro = 0f
    private var ygyro = 0f
    private var zgyro = 0f
    private var xGravity = 0f
    private var yGravity = 0f
    private var zGravity = 0f
    private var hvalue = "0"
    private var lightLux = 0f
    private var lastLightLux = -1f
    private var pressureHpa = 0f
    private var xLinAcc = 0f
    private var yLinAcc = 0f
    private var zLinAcc = 0f
    private var magX = 0f
    private var magY = 0f
    private var magZ = 0f
    private var xRot = 0f
    private var yRot = 0f
    private var zRot = 0f
    private var wRot = 0f
    private var stepCount = 0
    private var lastStepCount: Int? = null

    @Volatile private var stop = true
    @Volatile private var paused = false
    private var sensorTimer: Timer? = null
    private val recordingLock = Any()
    private var samplingPeriodMs = SAMPLING_PERIOD_MS
    private var pendingSamples = SampleBatch<Data>((SEND_INTERVAL_MS / SAMPLING_PERIOD_MS).toInt())
    private val pendingWearable = WearableBatch<Data>()
    private var currentActivityLabel = "Unlabeled activity"
    private var sessionPersonId = "unknown"
    private val wearableFlush = Runnable { synchronized(recordingLock) { flushWearableLocked() } }
    private val sensorUiRefresh = Runnable {
        val refresh = synchronized(recordingLock) { sensorUiScheduled = false; !stop && !paused }
        if (refresh) updateUI()
    }
    private var sensorUiScheduled = false
    private var studyMetadata = org.json.JSONObject()
    private var recordingGeneration = 0L

    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startJob: Job? = null
    private var diagnosticJob: Job? = null
    private var configJob: Job? = null
    private val configurationStore = WatchConfigurationStore(context)
    private val thermalMonitor = WatchThermalMonitor(context)
    private var enabledSensors = DEFAULT_SENSOR_KEYS
    private val availableSensors = mutableSetOf<Int>()
    private val freshSensors = mutableSetOf<Int>()
    private var sessionId: String? = null
    private var sequence = 0L
    private var sessionEpoch = 0L
    private var sessionElapsed = 0L
    private var segmentStart = 0L
    private var segmentSequence = 0L
    private var segmentEnd = 0L
    private var lastInvalidNotice = 0L
    private var lastReportedInvalid = false

    private val devicePrefs = context.getSharedPreferences("pemesa_watch_identity", Context.MODE_PRIVATE)
    private val watchId: String = devicePrefs.getString("id", null) ?: UUID.randomUUID().toString().also { devicePrefs.edit().putString("id", it).apply() }
    private val pairing = PairingChallenge(clock = { android.os.SystemClock.elapsedRealtime() })
    private var pairDialog: androidx.appcompat.app.AlertDialog? = null
    private fun authorized(node: String): Boolean = devicePrefs.getString("owner", null) == node

    private val transmit = TransmissionService(
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    ) { status ->
        // Defer status delivery to avoid acquiring recordingLock under the writer lock.
        commandHandler.post {
            val node = devicePrefs.getString("owner", null) ?: return@post
            if (!authorized(node)) return@post
            synchronized(recordingLock) {
                if (pendingWearable.size > 0) status.put("pending", status.getLong("pending") + 1L)
            }
            Wearable.getMessageClient(context).sendMessage(node, INFLUX_EVENT_PATH,
                status.toString().toByteArray(Charsets.UTF_8))
        }
    }

    fun close() {
        thermalMonitor.close()
        pairing.cancel(); pairDialog?.dismiss(); pairDialog = null
        commandHandler.removeCallbacksAndMessages(null)
        HealthJournal.add(HealthCode.STOP_OK)
        controlScope.cancel()
        stopRecording()
        transmit.close()
    }

    fun registerListeners() {
        thermalMonitor.start()
        Log.d(TAG, "🔌 Registering listeners")
        Wearable.getDataClient(context).addListener(this)
        Wearable.getMessageClient(context).addListener(this)
        Wearable.getCapabilityClient(context)
            .addListener(this, Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE)
    }

    fun unregisterListeners() {
        Log.d(TAG, "❌ Unregistering listeners")
        Wearable.getDataClient(context).removeListener(this)
        Wearable.getMessageClient(context).removeListener(this)
        Wearable.getCapabilityClient(context).removeListener(this)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {}
    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {}

    override fun onMessageReceived(messageEvent: MessageEvent) {
        try {
            val messageStr = String(messageEvent.data, StandardCharsets.UTF_8)
            val path = messageEvent.path

            Log.d(TAG, "Message received: $path")

            if (path != "/pemesa/device-v1" && !authorized(messageEvent.sourceNodeId)) return
            when (path) {
                "/pemesa/device-v1" -> receiveDeviceRequest(messageEvent.sourceNodeId, messageStr)
                CONFIG_PATH -> receiveConfiguration(messageEvent.sourceNodeId, messageStr)
                APP_OPEN_WEARABLE_PAYLOAD_PATH -> {
                    val nodeId = messageEvent.sourceNodeId
                    val payload = "AppOpenWearableACK".toByteArray()
                    Wearable.getMessageClient(context).sendMessage(nodeId, path, payload)
                        .addOnCompleteListener {
                            if (it.isSuccessful) {
                                if (!mobileDeviceConnected) HealthJournal.add(HealthCode.WATCH_CONNECTED)
                                mobileDeviceConnected = true
                                mobileNodeUri = nodeId
                            }
                        }
                }

                WATCH_BATTERY_PATH -> {
                    if (messageStr == "get") {
                        val battery = context.getSystemService(Context.BATTERY_SERVICE)
                            as? android.os.BatteryManager
                        val level = battery?.getIntProperty(
                            android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
                        )?.takeIf { it in 0..100 } ?: -1
                        Wearable.getMessageClient(context)
                            .sendMessage(messageEvent.sourceNodeId, WATCH_BATTERY_PATH,
                                level.toString().toByteArray(StandardCharsets.UTF_8))
                            .addOnFailureListener { error ->
                                Log.w(TAG, "Watch battery reply failed", error)
                            }
                    }
                }

                INFLUX_STATUS_PATH -> {
                    if (messageStr.length == 36 &&
                        runCatching { UUID.fromString(messageStr) }.isSuccess) {
                        val snapshot = combinedWriteStatus().put("id", messageStr)
                        Wearable.getMessageClient(context)
                            .sendMessage(messageEvent.sourceNodeId, INFLUX_STATUS_PATH,
                                snapshot.toString().toByteArray(StandardCharsets.UTF_8))
                            .addOnFailureListener { error -> Log.w(TAG, "InfluxDB status reply failed", error) }
                    }
                }

                "/pemesa/diagnostics-v1" -> receiveDiagnostics(messageEvent.sourceNodeId, messageStr)
                commandPath -> receiveConfirmedCommand(messageEvent.sourceNodeId, messageStr)
                MESSAGE_ITEM_RECEIVED_PATH -> Unit // All recording commands require a correlated ACK.
                else -> Log.w(TAG, "⚠️ Path non reconnu: $path")
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Erreur réception message", e)
        }
    }

    private fun combinedWriteStatus(): org.json.JSONObject = synchronized(recordingLock) {
        val status = transmit.writeStatus()
        // Include the unsent wearable batch.
        status.put("pending", status.getLong("pending") + if (pendingWearable.size > 0) 1L else 0L)
    }

    private fun associationBusy(): Boolean = sensorTimer != null || startJob?.isActive == true || configJob?.isActive == true || transmit.pendingCount() > 0L

    fun showPairingCode() {
        if (associationBusy()) {
            android.widget.Toast.makeText(context, "Stop recording and wait for pending writes", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        pairDialog?.dismiss()
        val code = pairing.open()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("PEMESA pairing")
            .setMessage("Enter this code on your phone:\n\n$code\n\nValid for 2 minutes")
            .setNegativeButton("Cancel", null).create()
        pairDialog = dialog
        dialog.setOnDismissListener { if (pairDialog === dialog) { pairing.cancel(); pairDialog = null } }
        dialog.show()
        commandHandler.postDelayed({ if (pairDialog === dialog) dialog.dismiss() }, 120000L)
    }

    private fun receiveDeviceRequest(node: String, message: String) {
        commandHandler.post {
            val request = runCatching { org.json.JSONObject(message) }.getOrNull() ?: return@post
            val id = request.optString("id")
            if (runCatching { UUID.fromString(id) }.isFailure) return@post
            val reply = org.json.JSONObject().put("id", id)
            when (request.optString("action")) {
                "info" -> {
                    val battery = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                    reply.put("watchId", watchId).put("associated", authorized(node))
                        .put("battery", battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
                }
                "pair" -> {
                    val ok = !associationBusy() && pairing.verify(request.optString("code")) &&
                        devicePrefs.edit().putString("owner", node).commit()
                    reply.put("ok", ok)
                    if (ok) {
                        reply.put("watchId", watchId)
                        pairDialog?.dismiss()
                        android.widget.Toast.makeText(context, "PEMESA associated", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                "identify" -> {
                    val ok = authorized(node)
                    if (ok) {
                        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        android.widget.Toast.makeText(context, "PEMESA: this is your selected watch", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    reply.put("ok", ok)
                }
                else -> return@post
            }
            Wearable.getMessageClient(context).sendMessage(node, "/pemesa/device-v1", reply.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun receiveConfiguration(node: String, message: String) {
        commandHandler.post {
            if (!authorized(node) || message.length > 32768) return@post
            val request = runCatching { org.json.JSONObject(message) }.getOrNull() ?: return@post
            val id = request.optString("id")
            if (!validConfigVersion(id)) return@post
            fun reply(result: String, value: StoredConfiguration? = null) {
                val response = org.json.JSONObject().put("id", id).put("watchId", watchId).put("result", result)
                    .put("version", value?.version ?: "").put("hash", value?.hash ?: "")
                Wearable.getMessageClient(context).sendMessage(node, CONFIG_PATH, response.toString().toByteArray(Charsets.UTF_8))
            }
            val action = request.optString("action")
            if (action != "status" && action != "save") return@post
            if (configJob?.isActive == true || startJob?.isActive == true ||
                (action == "save" && sensorTimer != null)) { reply("BUSY"); return@post }
            configJob = controlScope.launch {
                try {
                    val existing = withContext(Dispatchers.IO) { configurationStore.load(node) }
                    if (action == "status") { reply("OK", existing); return@launch }
                    val version = request.optString("version")
                    val hash = request.optString("hash")
                    val payload = request.optString("payload")
                    if (!validConfigVersion(version) || !validConfigDigest(hash) ||
                        configDigest(payload) != hash || !validConfigPayload(payload)) {
                        reply("REJECTED"); return@launch
                    }
                    if (existing != null && configMatches(version, hash, existing.version, existing.hash)) {
                        reply("OK", existing); return@launch
                    }
                    if (!canReplaceConfig(request.optString("expectedVersion"), request.optString("expectedHash"),
                            existing?.version ?: "", existing?.hash ?: "")) { reply("REJECTED"); return@launch }
                    val value = StoredConfiguration(version, hash, payload)
                    if (withContext(Dispatchers.IO) { configurationStore.save(node, value) }) reply("OK", value)
                    else reply("REJECTED")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { reply("REJECTED") }
            }
        }
    }

    private fun receiveDiagnostics(node: String, message: String) {
        commandHandler.post {
            val request = runCatching { org.json.JSONObject(message) }.getOrNull() ?: return@post
            val id = request.optString("id")
            if (runCatching { UUID.fromString(id) }.isFailure) return@post
            if (diagnosticJob?.isActive == true) return@post
            diagnosticJob = controlScope.launch {
                val reply = org.json.JSONObject().put("id", id)
                try {
                    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                    val types = listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GRAVITY,
                        Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_HEART_RATE,
                        Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_LIGHT, Sensor.TYPE_PRESSURE)
                    val available = org.json.JSONArray()
                    types.forEachIndexed { index, type ->
                        val permission = when (type) {
                            Sensor.TYPE_HEART_RATE -> ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
                            Sensor.TYPE_STEP_COUNTER -> android.os.Build.VERSION.SDK_INT < 29 ||
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
                            else -> true
                        }
                        if (manager.getDefaultSensor(type) != null && permission) available.put(SENSOR_KEYS[index])
                    }
                    available.put("fatigue")
                    if (manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null) available.put("magnetometer")
                    val battery = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                    val memory = android.app.ActivityManager.MemoryInfo()
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
                    reply.put("sensors", available)
                        .put("battery", battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
                        .put("lowMemory", memory.lowMemory).put("freeMb", memory.availMem / 1048576L)
                        .put("thermalStatus", thermalMonitor.status)
                        .put("writes", combinedWriteStatus())
                    if (request.optBoolean("journalOnly")) {
                        reply.put("journalOnly", true)
                    } else if (sensorTimer != null || startJob?.isActive == true || transmit.pendingCount() > 0L) {
                        reply.put("busy", true)
                    } else {
                        val saved = withContext(Dispatchers.IO) { configurationStore.load(node) }
                        val server = saved?.takeIf { configMatches(request.optString("version"), request.optString("hash"), it.version, it.hash) }
                            ?.json()?.optJSONObject("server")
                        reply.put("probe", transmit.diagnose(server))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { reply.put("error", "WATCH") }
                val snapshot = synchronized(recordingLock) {
                    val end = if (segmentEnd > 0L) segmentEnd else android.os.SystemClock.elapsedRealtime()
                    val duration = end - segmentStart
                    val count = (sequence - segmentSequence).coerceAtLeast(0L)
                    val hz = if (segmentStart > 0L && duration >= 3000L) count * 1000.0 / duration else null
                    org.json.JSONObject().put("running", sensorTimer != null && !stop).put("paused", paused)
                        .put("samples", sequence).put("targetHz", 1000L / samplingPeriodMs)
                        .put("observedHz", hz ?: org.json.JSONObject.NULL)
                }
                reply.put("acquisition", snapshot).put("journal", HealthJournal.snapshot().toHealthJson())
                Wearable.getMessageClient(context).sendMessage(node, "/pemesa/diagnostics-v1",
                    reply.toString().toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    // PEMESA_COMMAND_ACK_V1
    private val commandPath = "/pemesa/command-v1"
    private val commandAckPath = "/pemesa/command-ack-v1"
    private val commandHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // Cache recent command acknowledgements in RAM.
    private val completedCommands = CommandResults()

    private fun receiveConfirmedCommand(node: String, message: String) {
        // Serialize control commands on the main thread.
        commandHandler.post {
            val envelope = try { org.json.JSONObject(message) } catch (error: Exception) {
                Log.w(TAG, "Invalid command envelope", error)
                return@post
            }
            val id = envelope.optString("id")
            if (id.length != 36 || runCatching { java.util.UUID.fromString(id) }.isFailure) return@post
            val payload = envelope.optString("payload")
            fun reply(result: String) {
                val response = org.json.JSONObject().put("id", id).put("result", result)
                Wearable.getMessageClient(context).sendMessage(node, commandAckPath,
                    response.toString().toByteArray(StandardCharsets.UTF_8))
                    .addOnFailureListener { Log.w(TAG, "Command acknowledgement failed") }
            }
            if (!authorized(node)) { reply("REJECTED"); return@post }
            val config = envelope.optJSONObject("configuration")
            if (payload.contains("Action: Start") && config == null) { reply("REJECTED"); return@post }
            if (payload.contains("Action: Start") && config != null) {
                if (startJob?.isActive == true || configJob?.isActive == true || sensorTimer != null) { reply("REJECTED"); return@post }
                if (validatedCommand(payload, false) == null) { reply("REJECTED"); return@post }
                startJob = controlScope.launch {
                    try {
                        val stored = withContext(Dispatchers.IO) { configurationStore.load(node) }
                        if (stored == null || !configMatches(config.optString("version"), config.optString("hash"), stored.version, stored.hash)) {
                            HealthJournal.add(HealthCode.PREFLIGHT_FAILED)
                            reply("REJECTED")
                            return@launch
                        }
                        val saved = stored.json()
                        val keys = saved.getJSONArray("sensors").let { array ->
                            (0 until array.length()).map { array.getString(it) }.toSet()
                        }
                        val session = config.getString("session")
                        val hz = saved.getInt("hz")
                        val interval = saved.getInt("interval")
                        if (!validAcquisition(hz, interval) || !validSensorSelection(keys) || runCatching { UUID.fromString(session) }.isFailure ||
                            !transmit.prepare(saved.optJSONObject("server"))) {
                            HealthJournal.add(HealthCode.PREFLIGHT_FAILED)
                            reply("REJECTED")
                            return@launch
                        }
                        enabledSensors = keys
                        samplingPeriodMs = 1000L / hz
                        pendingSamples = SampleBatch(batchSizeFor(hz, interval))
                        studyMetadata = org.json.JSONObject()
                        sessionId = session
                        reply(completedCommands.result(node, id) { executeConfirmedCommand(payload) })
                    } catch (cancelled: CancellationException) {
                        reply("UNKNOWN")
                        throw cancelled
                    } catch (_: Exception) { reply("REJECTED") }
                }
            } else {
                if (payload == "Action: Stop" || payload == "Action: Refresh") startJob?.cancel()
                if (startJob?.isActive == true && payload != "Action: Stop" && payload != "Action: Refresh") {
                    reply("REJECTED")
                } else {
                    reply(completedCommands.result(node, id) { executeConfirmedCommand(payload) })
                }
            }
        }
    }

    private fun executeConfirmedCommand(payload: String): String {
        val fields = validatedCommand(payload, sensorTimer != null) ?: return "REJECTED"
        val action = fields["Action"]
        val fatigue = fields["FatigueLevel"]
        return try {
            handleMessagePayload(payload)
            val stateMatches = recordingStateMatches(action, sensorTimer != null, stop, paused)
            val labelMatches = fields["Activity"]?.let { sharedPref.getString("userActivity", null) == it } ?: true
            val fatigueMatches = fatigue?.let { sharedPref.getString("Fatigue Level", null) == it } ?: true
            if (stateMatches && labelMatches && fatigueMatches) "OK" else "UNKNOWN"
        } catch (error: Exception) {
            HealthJournal.add(HealthCode.INTERNAL_ERROR)
            Log.e(TAG, "Command execution failed", error)
            "UNKNOWN"
        }
    }

    private fun handleMessagePayload(payload: String) {
        val parts = payload.split(",").map { it.trim() }
        val messageMap = parts.mapNotNull {
            val kv = it.split(":").map { it.trim() }
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()

        val personID = messageMap["ID"]
        val userProfession = messageMap["Profession"]
        val userActivity = messageMap["Activity"]
        val action = messageMap["Action"]
        val fatigueLevel = messageMap["FatigueLevel"]
        // Do not log participant metadata.

        when (action) {
            "Start" -> {
                stepCount = 0
                lastStepCount = null
                sharedPref.edit()
                    .putString("personID", personID ?: "unknown")
                    .putString("userProfession", userProfession ?: "unknown")
                    .putString("userActivity", userActivity ?: "Unlabeled activity")
                    .putString("Fatigue Level", fatigueLevel ?: "0")
                    .apply()
                updateUI("📡 Start recording...")
                startRecording()
                HealthJournal.add(HealthCode.START_OK)
                // Record the initial fatigue rating.
                if ("fatigue" in enabledSensors) transmitSensorData(fatigueLevel = fatigueLevel ?: "0")
                transmit.transmitSession(sessionId ?: "", personID ?: "unknown", studyMetadata,
                    1000L / samplingPeriodMs, enabledSensors, sessionEpoch, watchId)
            }

            "Stop" -> {
                stopRecording()
                HealthJournal.add(HealthCode.STOP_OK)
                updateUI("⛔ Recording finished !")
            }

            "Pause" -> {
                synchronized(recordingLock) { if (!paused) segmentEnd = android.os.SystemClock.elapsedRealtime(); paused = true; lastStepCount = null; freshSensors.clear(); flushWearableLocked() }
                HealthJournal.add(HealthCode.PAUSE_OK)
                updateUI("⏸️ En pause.")
            }

            "Resume" -> {
                synchronized(recordingLock) { if (paused) { segmentStart = android.os.SystemClock.elapsedRealtime(); segmentSequence = sequence; segmentEnd = 0L }; paused = false; lastStepCount = null; freshSensors.clear(); hvalue = "0"; lastLightLux = -1f }
                HealthJournal.add(HealthCode.RESUME_OK)
                updateUI("▶ Recording in progress...")
            }

            "Refresh" -> {
                stopRecording()
                sharedPref.edit()
                    .putString("personID", personID ?: "unknown")
                    .putString("userProfession", userProfession ?: "unknown")
                    .putString("userActivity", userActivity ?: "Unlabeled activity")
                    .putString("Fatigue Level", fatigueLevel ?: "0")
                    .apply()
                updateUI("🔄 Watch Rafraîchi")
            }

            else -> Log.w(TAG, "⚠️ Action non identifiée: $action")
        }

        fatigueLevel?.let {
            val currentFatigue = sharedPref.getString("Fatigue Level", "0") ?: "0"
            if (it != currentFatigue) {
                sharedPref.edit().putString("Fatigue Level", it).apply()
                if (!stop && !paused && "fatigue" in enabledSensors) transmitSensorData(fatigueLevel = it)
            }
        }

        if (userActivity != null) {
            synchronized(recordingLock) {
                if (userActivity != currentActivityLabel) {
                    sharedPref.edit().putString("userActivity", userActivity).apply()
                    currentActivityLabel = userActivity
                }
            }
        }
    }

    private fun updateConnectionStatusUI(connected: Boolean) {
        binding.deviceconnectionStatusTv.visibility = View.VISIBLE
        binding.deviceconnectionStatusTv.text =
            if (connected) "📶 Mobile connected" else "❌ Mobile disconnected"
    }

    private fun requestSensorUiRefresh() {
        if (!sensorUiScheduled) {
            sensorUiScheduled = true
            commandHandler.postDelayed(sensorUiRefresh, 1000L)
        }
    }

    private fun updateUI(prefix: String? = null) {
        val readings = synchronized(recordingLock) {
            commandHandler.removeCallbacks(sensorUiRefresh)
            sensorUiScheduled = false
            Triple(hvalue, stepCount, lightLux)
        }
        val personID = sharedPref.getString("personID", "unknown")
        val userProfession = sharedPref.getString("userProfession", "unknown")
        val userActivity = sharedPref.getString("userActivity", "Unlabeled activity")
        val fatigueLevel = sharedPref.getString("Fatigue Level", "0")
        val heartRate = readings.first
        val steps = readings.second

        val displayText = buildString {
            if (prefix != null) appendLine(prefix)
            appendLine("ID: $personID")
            appendLine("Profession: $userProfession")
            appendLine("Activity: $userActivity")
            appendLine("\uD83E\uDD71 Fatigue Level: $fatigueLevel")
            appendLine("❤️ BPM: $heartRate")
            appendLine("👣 Steps: $steps")
            appendLine("💡 Light: ${readings.third}")
        }

        binding.deviceconnectionStatusTv.post {
            binding.deviceconnectionStatusTv.text = displayText
            binding.deviceconnectionStatusTv.visibility = View.VISIBLE
        }
    }

    private fun setupSensors(generation: Long) {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        linac = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        rotat = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magnetometer = if ("magnetometer" in enabledSensors) sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) else null
        if ("magnetometer" in enabledSensors && magnetometer == null)
            HealthJournal.add(HealthCode.SENSOR_MISSING, Sensor.TYPE_MAGNETIC_FIELD.toLong())
        stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            hrate = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        } else {
            hrate = null
        }

        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        val samplingPeriodUs = (samplingPeriodMs * 1000).toInt()

        val thread = android.os.HandlerThread("PEMESA-sensors").apply { start() }
        sensorThread = thread
        val listener = createSensorListener(generation)
        sensorListener = listener
        val callbackHandler = android.os.Handler(thread.looper)
        availableSensors.clear()
        listOf("accelerometer" to accel, "gyroscope" to gyro, "gravity" to gravitySensor,
            "heart" to hrate, "light" to lightSensor, "pressure" to pressureSensor,
            "linear" to linac, "rotation" to rotat, "steps" to stepCounter, "magnetometer" to magnetometer)
            .filter { it.first in enabledSensors }.forEach { (_, sensor) ->
            sensor?.let {
                try {
                    if (!sensorManager.registerListener(listener, it, samplingPeriodUs, callbackHandler)) {
                        HealthJournal.add(HealthCode.SENSOR_REGISTER_FAILED, it.type.toLong())
                        Log.w(TAG, "Sensor registration failed: ${it.type}")
                    } else availableSensors.add(it.type)
                } catch (error: SecurityException) {
                    HealthJournal.add(HealthCode.SENSOR_PERMISSION, it.type.toLong())
                    Log.w(TAG, "Sensor permission unavailable: ${it.type}", error)
                }
            }
        }
    }

    private fun createSensorListener(generation: Long): SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                synchronized(recordingLock) {
                if (!acquisitionCallbackAllowed(generation, recordingGeneration, stop, paused) ||
                    it.sensor.type !in availableSensors) return
                val required = when (it.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GRAVITY,
                    Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_MAGNETIC_FIELD -> 3
                    else -> 1
                }
                if (!finiteSensorValues(it.values, required)) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!lastReportedInvalid || now - lastInvalidNotice >= 10000L) {
                        lastReportedInvalid = true; lastInvalidNotice = now
                        HealthJournal.add(HealthCode.SENSOR_INVALID, it.sensor.type.toLong())
                    }
                    return
                }
                freshSensors.add(it.sensor.type)
                // Event-driven signals use sensor timestamps; inertial samples use the session clock.
                when (it.sensor.type) {

                    Sensor.TYPE_HEART_RATE -> {
                        val newBpm = it.values[0].toInt().toString()
                        if (newBpm != hvalue) {
                            hvalue = newBpm
                            transmitSensorData(bpm = hvalue, eventTime = sensorEpochMillis(it.timestamp))
                            requestSensorUiRefresh()
                        }
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        xaccel = it.values[0]
                        yaccel = it.values[1]
                        zaccel = it.values[2]
                    }

                    Sensor.TYPE_GYROSCOPE -> {
                        xgyro = it.values[0]
                        ygyro = it.values[1]
                        zgyro = it.values[2]
                    }

                    Sensor.TYPE_STEP_COUNTER -> {
                        val totalSteps = it.values[0].toInt()
                        val previousCount = lastStepCount
                        if (previousCount == null) {
                            // Initialize the step counter from the first sensor event.
                            lastStepCount = totalSteps
                            transmitSensorData(steps = stepCount, eventTime = sensorEpochMillis(it.timestamp))
                        } else {
                            val newSteps = totalSteps - previousCount
                            if (newSteps > 0) {
                                stepCount += newSteps
                                lastStepCount = totalSteps
                                transmitSensorData(steps = stepCount, eventTime = sensorEpochMillis(it.timestamp))
                                requestSensorUiRefresh()
                            } else if (newSteps < 0) {
                                // Reset the baseline when the hardware counter resets.
                                lastStepCount = totalSteps
                            }
                        }
                    }

                    Sensor.TYPE_LIGHT -> {
                        val newLux = it.values[0]
                        if (newLux != lastLightLux) {
                            lastLightLux = newLux
                            lightLux = newLux
                            transmitSensorData(light = lightLux, eventTime = sensorEpochMillis(it.timestamp))
                            requestSensorUiRefresh()
                        }
                    }

                    Sensor.TYPE_PRESSURE -> {
                        pressureHpa = it.values[0]
                        transmitSensorData(pressure = pressureHpa, eventTime = sensorEpochMillis(it.timestamp))
                    }

                    Sensor.TYPE_GRAVITY -> {
                        xGravity = it.values[0]
                        yGravity = it.values[1]
                        zGravity = it.values[2]
                    }

                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        xLinAcc = it.values[0]
                        yLinAcc = it.values[1]
                        zLinAcc = it.values[2]
                    }

                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        magX = it.values[0]
                        magY = it.values[1]
                        magZ = it.values[2]
                    }

                    Sensor.TYPE_ROTATION_VECTOR -> {
                        xRot = it.values[0]
                        yRot = it.values[1]
                        zRot = it.values[2]
                        wRot = if (it.values.size > 3) it.values[3] else 0f
                    }
                    else -> Log.w(TAG, "⚠️ Sensor not managed: ${it.sensor.type}")
                }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun sensorEpochMillis(sensorNanos: Long): Long =
        System.currentTimeMillis() - ((android.os.SystemClock.elapsedRealtimeNanos() - sensorNanos).coerceAtLeast(0L) / 1000000L)

    // Requires recordingLock.
    private fun flushWearableLocked() {
        commandHandler.removeCallbacks(wearableFlush)
        val batch = pendingWearable.drain()
        if (batch.isNotEmpty()) transmit.transmitWearableData(batch)
    }

    private fun transmitSensorData(
        bpm: String? = null,
        light: Float? = null,
        pressure: Float? = null,
        steps: Int? = null,
        fatigueLevel: String? = null,
        eventTime: Long = System.currentTimeMillis()
    ) {
        synchronized(recordingLock) {
            if (stop || paused) return
            val data = Data(
                timestamp = "", // Numeric epochMillis is authoritative; skip unused date formatting.
                person_id = sessionPersonId,
                bpm = bpm,
                light = light,
                pressure = pressure,
                steps = steps,
                fatigueLevel = fatigueLevel, epochMillis = eventTime, sessionId = sessionId, watchId = watchId
            )
            val first = pendingWearable.size == 0
            if (pendingWearable.add(data)) flushWearableLocked()
            else if (first) commandHandler.postDelayed(wearableFlush, 1000L)
        }
    }

    private fun startRecording() {
        synchronized(recordingLock) {
            if (sensorTimer != null) return
            stop = false; paused = false
            freshSensors.clear()
            hvalue = "0"; lastLightLux = -1f; lastStepCount = null; stepCount = 0
            sequence = 0L
            sessionEpoch = System.currentTimeMillis()
            sessionElapsed = android.os.SystemClock.elapsedRealtime()
            segmentStart = sessionElapsed; segmentSequence = 0L; segmentEnd = 0L
            lastInvalidNotice = 0L; lastReportedInvalid = false
            if (sessionId == null) sessionId = UUID.randomUUID().toString()
            sessionPersonId = sharedPref.getString("personID", "unknown") ?: "unknown"
            currentActivityLabel = sharedPref.getString("userActivity", "Unlabeled activity") ?: "Unlabeled activity"
            val personID = sessionPersonId
            val generation = ++recordingGeneration
            pendingSamples.clear()
            try {
                setupSensors(generation)
            } catch (error: Exception) {
                stop = true
                recordingGeneration++
                releaseSensorCallbacks()
                throw error
            }

            sensorTimer = fixedRateTimer("sensorTimer", true, 100, samplingPeriodMs) {
                synchronized(recordingLock) {
                    if (generation == recordingGeneration && !stop && !paused) {
                        val data = Data(
                            timestamp = "", // epochMillis is already supplied below.
                            person_id = personID,
                            acceX = xaccel.takeIf { Sensor.TYPE_ACCELEROMETER in freshSensors },
                            acceY = yaccel.takeIf { Sensor.TYPE_ACCELEROMETER in freshSensors },
                            acceZ = zaccel.takeIf { Sensor.TYPE_ACCELEROMETER in freshSensors },
                            gyroX = xgyro.takeIf { Sensor.TYPE_GYROSCOPE in freshSensors },
                            gyroY = ygyro.takeIf { Sensor.TYPE_GYROSCOPE in freshSensors },
                            gyroZ = zgyro.takeIf { Sensor.TYPE_GYROSCOPE in freshSensors },
                            gravityX = xGravity.takeIf { Sensor.TYPE_GRAVITY in freshSensors },
                            gravityY = yGravity.takeIf { Sensor.TYPE_GRAVITY in freshSensors },
                            gravityZ = zGravity.takeIf { Sensor.TYPE_GRAVITY in freshSensors },
                            linAccX = xLinAcc.takeIf { Sensor.TYPE_LINEAR_ACCELERATION in freshSensors },
                            linAccY = yLinAcc.takeIf { Sensor.TYPE_LINEAR_ACCELERATION in freshSensors },
                            linAccZ = zLinAcc.takeIf { Sensor.TYPE_LINEAR_ACCELERATION in freshSensors },
                            rotX = xRot.takeIf { Sensor.TYPE_ROTATION_VECTOR in freshSensors },
                            rotY = yRot.takeIf { Sensor.TYPE_ROTATION_VECTOR in freshSensors },
                            rotZ = zRot.takeIf { Sensor.TYPE_ROTATION_VECTOR in freshSensors },
                            rotW = wRot.takeIf { Sensor.TYPE_ROTATION_VECTOR in freshSensors },
                            magX = magX.takeIf { Sensor.TYPE_MAGNETIC_FIELD in freshSensors },
                            magY = magY.takeIf { Sensor.TYPE_MAGNETIC_FIELD in freshSensors },
                            magZ = magZ.takeIf { Sensor.TYPE_MAGNETIC_FIELD in freshSensors },
                            label = currentActivityLabel,
                            sessionId = sessionId, sequence = sequence++, watchId = watchId,
                            epochMillis = sessionEpoch + (android.os.SystemClock.elapsedRealtime() - sessionElapsed)
                        )
                        pendingSamples.add(data)
                        if (pendingSamples.isFull()) {
                            submitPendingSamples()
                        }
                    }
                }
            }
        }
    }

    // Requires recordingLock; ownership transfers to the writer.
    private fun submitPendingSamples() {
        if (pendingSamples.size == 0) return
        transmit.transmitData(pendingSamples.drain())
    }

    private fun stopRecording() {
        synchronized(recordingLock) {
            if (!stop && segmentEnd == 0L) segmentEnd = android.os.SystemClock.elapsedRealtime()
            stop = true; paused = false
            recordingGeneration++
            sensorTimer?.cancel(); sensorTimer = null
            // Enqueue partial batches before acknowledging Stop.
            submitPendingSamples()
            flushWearableLocked()
            commandHandler.removeCallbacks(sensorUiRefresh)
            sensorUiScheduled = false
            lastStepCount = null
            lastLightLux = -1f
        }
        releaseSensorCallbacks()
    }

    private fun releaseSensorCallbacks() {
        val listener = sensorListener
        sensorListener = null
        try {
            if (listener != null && ::sensorManager.isInitialized) sensorManager.unregisterListener(listener)
        } finally {
            sensorThread?.quitSafely()
            sensorThread = null
        }
    }
}
