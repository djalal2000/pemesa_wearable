package com.bharathvishal.messagecommunicationusingwearabledatalayer

import kotlinx.coroutines.isActive
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import com.bharathvishal.messagecommunicationusingwearabledatalayer.databinding.ActivityValidationBinding
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ValidationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityValidationBinding
    private lateinit var communicator: WearableCommunicator
    private var connectionMonitorJob: kotlinx.coroutines.Job? = null
    // PEMESA_WATCH_BATTERY_V1
    private val batteryUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val batteryExpiry = Runnable { showWatchBattery(null) }
    private val influxExpiry = Runnable { showInfluxStatus(null) }

    private var lastAlertFailures = 0L
    private val influxAlerts = InfluxAlertTracker()
    private var watchLossAlerted = false

    private fun optionalHapticAlert() {
        if (CollectionPreferences(this).vibration) binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun showInfluxStatus(status: InfluxWriteStatus?) {
        val alert = influxAlerts.update(status)
        if (status != null) {
            if (status.failures > lastAlertFailures) {
                HealthJournal.add(HealthCode.FAILURES_REPORTED, status.failures)
                if (isRecording && alert != InfluxAlert.LOST) optionalHapticAlert()
            }
            lastAlertFailures = status.failures
        }
        if (isRecording || commandUncertain) {
            when (alert) {
                InfluxAlert.LOST -> {
                    optionalHapticAlert()
                    Toast.makeText(this, commandText("InfluxDB transmission failed. Check connection and pending writes.",
                        "Transmission InfluxDB interrompue. Vérifiez la connexion et les envois en attente."), Toast.LENGTH_LONG).show()
                }
                InfluxAlert.RECOVERED -> Toast.makeText(this, commandText("InfluxDB transmission restored.",
                    "Transmission InfluxDB rétablie."), Toast.LENGTH_LONG).show()
                InfluxAlert.NONE -> Unit
            }
        }
        batteryUiHandler.removeCallbacks(influxExpiry)
        val text: String
        val color: String
        when (influxDisplayState(status)) {
            InfluxDisplayState.UNAVAILABLE -> {
                text = commandText("Status unavailable", "État indisponible")
                color = "#D8E7E4"
            }
            InfluxDisplayState.FAILED -> {
                val failed = requireNotNull(status)
                val buckets = listOfNotNull(
                    "activity".takeIf { failed.activity == "ERROR" },
                    "wearable".takeIf { failed.wearable == "ERROR" }
                ).joinToString(", ")
                text = commandText("Write failed", "Échec d’écriture") + " ($buckets)"
                color = "#FFB8C0"
            }
            InfluxDisplayState.PREVIOUS_FAILURES -> {
                val previous = requireNotNull(status)
                text = if (previous.activity == "OK" || previous.wearable == "OK")
                    commandText("Writes restored · Previous failures", "Écritures rétablies · Échecs antérieurs") + " (${previous.failures})"
                else commandText("Previous write failures", "Échecs antérieurs") + " (${previous.failures})"
                color = "#FFD7A6"
            }
            InfluxDisplayState.OK -> {
                text = commandText("Last writes OK", "Dernières écritures OK")
                color = "#D6EDA5"
            }
            InfluxDisplayState.PENDING -> {
                text = commandText("Awaiting confirmation", "Confirmation en attente")
                color = "#FFD7A6"
            }
            InfluxDisplayState.NO_WRITES -> {
                text = commandText("No writes yet", "Aucune écriture")
                color = "#D8E7E4"
            }
        }
        val pending = status?.pending ?: 0L
        binding.influxStatusTv.text = if (pending > 0) {
            "● $text · " + commandText("$pending pending", "$pending en attente")
        } else "● $text"
        binding.influxStatusTv.setTextColor(android.graphics.Color.parseColor(color))
        binding.influxStatusTv.contentDescription = "InfluxDB: " + binding.influxStatusTv.text.toString().removePrefix("● ") +
            if (status != null && status.failures > 0) {
                commandText(". Failed writes since watch app launch: ${status.failures}",
                    ". Échecs depuis le lancement de la montre : ${status.failures}")
            } else ""
        if (status != null) batteryUiHandler.postDelayed(influxExpiry, 25000L)
    }

    private fun showWatchBattery(level: Int?) {
        batteryUiHandler.removeCallbacks(batteryExpiry)
        val french = AppLanguage.get(this) == AppLanguage.FRENCH
        val title = if (french) "Batterie de la montre" else "Watch battery"
        val warning = if (level != null && level <= 20) " ⚠️" else ""
        // PEMESA_BATTERY_COMPACT_V1: retain the accessible battery description.
        val batteryValue = (level?.let { "$it%" } ?: "—") + warning
        binding.watchBatteryTv.text = "🔋 $batteryValue"
        binding.watchBatteryTv.contentDescription = "$title: $batteryValue"
        if (level != null) {
            batteryUiHandler.postDelayed(batteryExpiry, 25000L)
        }
    }

    private var wearableDeviceConnected: Boolean = false
    private var selectedId: String = ""
    private var selectedprofession: String = ""
    private var selectedActivity: String = "Unlabeled activity"
    private var hasSelectedActivity = false
    // Store activity labels independently of the interface language.
    private val unlabeledIndex = 6
    private lateinit var defaultActivityLabels: List<String>
    private val customActivityLabels = MutableList<String?>(7) { null }
    private val activityLabelPreferences by lazy {
        getSharedPreferences(ActivityLabels.PREFERENCES, MODE_PRIVATE)
    }
    private var isRecording: Boolean = false
    private var isPaused: Boolean = false

    private var startTime: Long = 0
    private val handler = Handler()
    private var updateTimerRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HealthJournal.add(HealthCode.APP_OPEN)
        binding = ActivityValidationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showWatchBattery(null)
        showInfluxStatus(null)

        binding.deviceconnectionStatusTv.text = commandText("Watch status", "État de la montre")
        binding.watchStatusSubtitleTv.text = commandText("Checking connection", "Vérification de la connexion")
        binding.startRecordingButton.text = localized("Start Recording")
        binding.stopRecordingButton.text = localized("Stop Recording")
        binding.pauseRecordingButton.text = localized("⏸ Pause")
        binding.toggleActivityButton.text = localized("🚫 Unlabeled activity")
        binding.TirednessValueTextView.text = localized("Fatigue Level:") + " " + binding.TirednessSeekBar.progress

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        selectedId = intent.getStringExtra("id") ?: "Unknown"
        selectedprofession = intent.getStringExtra("profession") ?: "Unknown"
        initializeActivityLabels()

        communicator = WearableCommunicator(
            context = this,
            onMessageReceivedCallback = { message ->
                runOnUiThread {
                    Toast.makeText(this, localized("Message received:") + " " + message, Toast.LENGTH_SHORT).show()
                }
            },
            onWearableConnected = { isConnected, appOpen ->
                runOnUiThread {
                    val wasConnected = wearableDeviceConnected
                    wearableDeviceConnected = isConnected && appOpen
                    if (wasConnected != wearableDeviceConnected) HealthJournal.add(if (wearableDeviceConnected) HealthCode.WATCH_CONNECTED else HealthCode.WATCH_LOST)
                    updateConnectionStatus(isConnected, appOpen)
                    if (wearableDeviceConnected && lifecycle.currentState.isAtLeast(
                            androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        communicator.requestWatchBattery()
                        communicator.requestInfluxStatus()
                    } else {
                        showWatchBattery(null)
                        showInfluxStatus(null)
                    }
                    Log.d("WearableConnection", "Watch connection: $isConnected, App open: $appOpen")

                    if (wearableDeviceConnected && watchLossAlerted) {
                        watchLossAlerted = false
                        Toast.makeText(this@ValidationActivity, commandText("Watch connection restored.", "Connexion à la montre rétablie."), Toast.LENGTH_LONG).show()
                    }
                    if (!wearableDeviceConnected && wasConnected && isRecording) {
                        watchLossAlerted = true
                        optionalHapticAlert()
                        Toast.makeText(
                            this@ValidationActivity,
                            commandText("Watch connection lost. Recording state unknown.", "Connexion perdue. État de l’enregistrement inconnu."),
                            Toast.LENGTH_LONG
                        ).show()
                        markCommandUncertain()
                    }
                }
            }
        )

        communicator.onWatchBatteryLevel = { level ->
            runOnUiThread {
                if (wearableDeviceConnected && lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    showWatchBattery(level)
                }
            }
        }


        communicator.onInfluxStatus = { status ->
            runOnUiThread {
                if (wearableDeviceConnected && lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    showInfluxStatus(status)
                }
            }
        }

        binding.stopRecordingButton.isEnabled = false
        setActivityButtonsEnabled(false)
        updateActivityButtonColor()
        updateActivityStatusText()

        setupSeekBar()
        setupButtonListeners()
        setupActivityButtons()
        setupBottomNavigation()
        applyUiDesign()
        if (WatchRegistry(this).pinned != null) markCommandUncertain()
    }

    override fun onResume() {
        super.onResume()
        communicator.registerListeners()
        if (isRecording && !isPaused && !commandUncertain) startTimer()
        connectionMonitorJob?.cancel()
        connectionMonitorJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    communicator.checkWearableConnection()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("ValidationActivity", "Connection check failed", error)
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    override fun onPause() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
        communicator.unregisterListeners()
        showWatchBattery(null)
        showInfluxStatus(null)
        super.onPause()
        stopTimer()
    }

    // PEMESA_COMMAND_ACK_V1
    private val commandGate = RecordingCommandGate()
    private val commandPending: Boolean get() = commandGate.busy
    private var queuedStop: (() -> Unit)? = null
    private var commandUncertain = false
    private var confirmedFatigue = 0

    private fun commandText(english: String, french: String): String = if (AppLanguage.get(this) == AppLanguage.FRENCH) french else english

    private fun commandNotice(english: String, french: String) {
        Toast.makeText(this, commandText(english, french), Toast.LENGTH_LONG).show()
    }

    private fun markCommandUncertain() {
        HealthJournal.add(HealthCode.COMMAND_UNKNOWN)
        commandUncertain = true
        stopTimer()
        binding.startRecordingButton.isEnabled = false
        binding.pauseRecordingButton.isEnabled = false
        binding.stopRecordingButton.isEnabled = true
        setActivityButtonsEnabled(false)
        binding.TirednessSeekBar.isEnabled = false
    }

    /** Apply the mobile state transition only after the watch confirms execution. */
    private fun confirmedCommand(message: String, onFailure: () -> Unit = {}, onConfirmed: () -> Unit) {
        when (commandGate.request(message)) {
            RecordingCommandGate.Decision.QUEUE_STOP -> {
                // Prioritize pending Stop commands.
                queuedStop = { confirmedCommand(message, onFailure, onConfirmed) }
                commandNotice("Stop requested. Waiting for the current command.",
                    "Arrêt demandé. Attente de la commande en cours.")
                return
            }
            RecordingCommandGate.Decision.STOP_ALREADY_REQUESTED -> {
                commandNotice("Stop already requested", "Arrêt déjà demandé")
                return
            }
            RecordingCommandGate.Decision.BUSY -> {
                commandNotice("Waiting for the watch", "Confirmation de la montre en cours")
                onFailure()
                return
            }
            RecordingCommandGate.Decision.SEND -> Unit
        }
        if (commandUncertain && message != "Action: Stop" && message != "Action: Refresh") {
            commandNotice("Watch state unknown. Confirm Stop or Refresh first.", "État de la montre inconnu. Confirmez d’abord Stop ou Actualiser.")
            onFailure()
            return
        }
        if (!wearableDeviceConnected) {
            if (message == RecordingCommandGate.STOP) markCommandUncertain()
            commandNotice("Watch not connected", "Montre non connectée")
            onFailure()
            return
        }
        if (message.contains("Action: Start")) {
            val node = communicator.lastNodeId
            if (node == null || !WatchRegistry(this).pin(node)) {
                commandNotice("Select and associate a watch in Settings first.", "Sélectionnez et associez une montre dans les paramètres.")
                onFailure(); return
            }
        }
        commandGate.begin(message)
        // Allow Stop to queue while Start is awaiting acknowledgement.
        if (message.contains("Action: Start")) {
            binding.startRecordingButton.isEnabled = false
            binding.stopRecordingButton.isEnabled = true
            uiStyleButton(binding.stopRecordingButton, ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
        lifecycleScope.launch {
            try {
                val configuration = if (message.contains("Action: Start")) {
                    try {
                        val node = WatchRegistry(this@ValidationActivity).pinned
                        val snapshot = withContext(Dispatchers.IO) {
                            val prefs = CollectionPreferences(this@ValidationActivity)
                            require(prefs.serverConfigured)
                            prefs.configuration()
                        }
                        val sync = ConfigurationSync.synchronize(this@ValidationActivity, node, forStart = true)
                        if (sync != ConfigSyncResult.CONFIRMED ||
                            CollectionPreferences(this@ValidationActivity).version() != snapshot.version) {
                            commandNotice("Configuration not confirmed. Reconnect the watch or check Settings.",
                                "Configuration non confirmée. Reconnectez la montre ou vérifiez les paramètres.")
                            node?.let { WatchRegistry(this@ValidationActivity).release(it) }
                            applyStoppedState()
                            onFailure()
                            return@launch
                        }
                        snapshot.startEnvelope(java.util.UUID.randomUUID().toString())
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        HealthJournal.add(HealthCode.CONFIG_INVALID)
                        commandNotice("Configure InfluxDB in Settings before starting.", "Configurez InfluxDB dans les paramètres avant de démarrer.")
                        communicator.lastNodeId?.let { WatchRegistry(this@ValidationActivity).release(it) }
                        applyStoppedState()
                        return@launch
                    }
                } else null
                if (configuration != null && commandGate.stopping) {
                    // Stop was requested before Start was sent. Keep the pin until Stop is confirmed.
                    return@launch
                }
                val result = communicator.sendConfirmedCommand(message, configuration)
                when (result) {
                    "OK" -> {
                        if (!wearableDeviceConnected) {
                            onFailure()
                            markCommandUncertain()
                            commandNotice("Watch disconnected during confirmation", "Montre déconnectée pendant la confirmation")
                            return@launch
                        }
                        if (message == "Action: Stop" || message == "Action: Refresh") {
                            communicator.lastNodeId?.let { WatchRegistry(this@ValidationActivity).release(it) }
                            commandUncertain = false
                            binding.TirednessSeekBar.isEnabled = true
                        }
                        when {
                            message.contains("Action: Start") -> HealthJournal.add(HealthCode.START_OK)
                            message == "Action: Stop" || message == "Action: Refresh" -> HealthJournal.add(HealthCode.STOP_OK)
                            message == "Action: Pause" -> HealthJournal.add(HealthCode.PAUSE_OK)
                            message == "Action: Resume" -> HealthJournal.add(HealthCode.RESUME_OK)
                        }
                        onConfirmed()
                    }
                    "REJECTED", "NO_CONNECTION" -> {
                        HealthJournal.add(HealthCode.COMMAND_REJECTED)
                        onFailure()
                        if (commandNeedsRecovery(message, result)) {
                            markCommandUncertain()
                        }
                        commandNotice("Command rejected or watch unavailable. Check the watch.", "Commande refusée ou montre indisponible. Vérifiez la montre.")
                    }
                    else -> {
                        onFailure()
                        markCommandUncertain()
                        commandNotice("No execution confirmation. Check the watch and use Stop or Refresh.", "Exécution non confirmée. Vérifiez la montre puis utilisez Stop ou Actualiser.")
                    }
                }
            } finally {
                val stopNext = commandGate.finish()
                val next = queuedStop
                queuedStop = null
                // Reserve the next command slot on the main thread.
                if (stopNext && lifecycle.currentState != androidx.lifecycle.Lifecycle.State.DESTROYED) {
                    next?.invoke()
                }
            }
        }
    }

    private fun setupSeekBar() {
        binding.TirednessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.TirednessValueTextView.text = localized("Fatigue Level:") + " " + progress
                updateFatigueColor(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                sendFatigueLevel()
            }
        })
    }

    private fun stopRecording(after: () -> Unit = {}) {
        confirmedCommand("Action: Stop") {
            applyStoppedState()
            after()
        }
    }

    private fun applyStoppedState() {
        binding.startRecordingButton.isEnabled = true
        binding.stopRecordingButton.isEnabled = false
        uiStyleButton(binding.stopRecordingButton, ContextCompat.getColor(this, android.R.color.darker_gray))
        binding.pauseRecordingButton.isEnabled = false
        binding.pauseRecordingButton.text = localized("⏸ Pause")

        isRecording = false
        isPaused = false
        hasSelectedActivity = false
        setActivityButtonsEnabled(false)
        updateActivityButtonColor()

        stopTimer()

        Toast.makeText(this, localized("Recording stopped"), Toast.LENGTH_SHORT).show()
    }

    private fun setupButtonListeners() {
        binding.startRecordingButton.setOnClickListener {
            if (wearableDeviceConnected) {
                val fatigue = getCurrentFatigueLevel()
                val messageToSend = "ID: $selectedId, Profession: $selectedprofession, Activity: $selectedActivity, FatigueLevel: $fatigue, Action: Start"
                confirmedCommand(messageToSend) {
                confirmedFatigue = fatigue

                binding.startRecordingButton.isEnabled = false
                binding.stopRecordingButton.isEnabled = true
                uiStyleButton(binding.stopRecordingButton, ContextCompat.getColor(this, android.R.color.holo_red_dark))
                setActivityButtonsEnabled(true)
                binding.TirednessSeekBar.isEnabled = "fatigue" in CollectionPreferences(this@ValidationActivity).sensors
                binding.pauseRecordingButton.isEnabled = true

                isRecording = true
                isPaused = false
                binding.pauseRecordingButton.text = localized("⏸ Pause")
                startTime = android.os.SystemClock.elapsedRealtime()
                startTimer()

                Toast.makeText(this, localized("Recording started"), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, commandText("Select a connected PEMESA watch in Settings.", "Sélectionnez une montre PEMESA connectée dans les paramètres."), Toast.LENGTH_SHORT).show()
            }
        }

        binding.toggleActivityButton.setOnClickListener {
            if (isRecording) {
                val requestedActivity = activityLabel(unlabeledIndex)
                confirmedCommand("Activity: $requestedActivity") {
                    selectedActivity = requestedActivity
                    hasSelectedActivity = true
                    updateUnlabeledButton("🛑")
                    updateActivityButtonColor()
                    updateActivityStatusText()
                }
            } else {
                Toast.makeText(this, localized("Please start recording before setting the Activity."), Toast.LENGTH_SHORT).show()
            }
        }

        binding.stopRecordingButton.setOnClickListener {
            stopRecording()
        }

        binding.pauseRecordingButton.setOnClickListener {
            if (!isRecording) {
                Toast.makeText(this, localized("No recording in progress."), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val requestedPause = !isPaused
            confirmedCommand(if (requestedPause) "Action: Pause" else "Action: Resume") {
            isPaused = requestedPause

            if (isPaused) {
                updateTimerRunnable?.let { handler.removeCallbacks(it) }
                setActivityButtonsEnabled(false)
                binding.pauseRecordingButton.text = localized("▶ Resume")
                Toast.makeText(this, localized("Recording paused"), Toast.LENGTH_SHORT).show()
            } else {
                startTime = android.os.SystemClock.elapsedRealtime() - getElapsedTimeFromText()
                startTimer()
                setActivityButtonsEnabled(true)
                binding.pauseRecordingButton.text = localized("⏸ Pause")
                Toast.makeText(this, localized("Recording resumed"), Toast.LENGTH_SHORT).show()
            }
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.action_refresh_connection -> {
                    refreshPage()
                    true
                }
                R.id.action_show_connected_devices -> {
                    lifecycleScope.launch {
                        val nodeList = withContext(Dispatchers.IO) {
                            try {
                                Tasks.await(Wearable.getNodeClient(this@ValidationActivity).connectedNodes)
                            } catch (e: Exception) {
                                Log.e("Wearable", "Error fetching nodes", e)
                                null
                            }
                        }

                        if (!nodeList.isNullOrEmpty()) {
                            val deviceNames = nodeList.joinToString("\n") { "• ${it.displayName} (${it.id})" }
                            AlertDialog.Builder(this@ValidationActivity)
                                .setTitle(localized("Connected Watches"))
                                .setIcon(R.drawable.ic_pemesa_round_watch)
                                .setMessage(deviceNames)
                                .setPositiveButton("OK", null)
                                .create()
                                .show()
                        } else {
                            Toast.makeText(this@ValidationActivity, localized("No connected watches"), Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                R.id.action_home -> {
                    val goHome = {
                        val intent = Intent(this, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                    }
                    if (commandPending) {
                        commandNotice("Waiting for the watch", "Confirmation de la montre en cours")
                    } else if (isRecording || commandUncertain) {
                        stopRecording(goHome)
                    } else {
                        goHome()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupActivityButtons() {
        activityButtons().forEachIndexed { index, button ->
            button.text = activityDisplay(index)

            button.setOnClickListener {
                if (isRecording) {
                    val requestedActivity = activityLabel(index)
                    confirmedCommand("Activity: $requestedActivity") {
                        selectedActivity = requestedActivity
                        hasSelectedActivity = true
                        updateActivityButtonColor()
                        updateActivityStatusText()
                    }
                } else {
                    Toast.makeText(this, localized("Start recording before choosing a Activity."), Toast.LENGTH_SHORT).show()
                }
            }
        }
        updateUnlabeledButton("🚫")
    }

    private fun activityButtons() = listOf(
        binding.ActivityButton1, binding.ActivityButton2, binding.ActivityButton3,
        binding.ActivityButton4, binding.ActivityButton5, binding.ActivityButton6
    )

    private fun labelKey(index: Int) = ActivityLabels.key(selectedprofession, index)

    private fun initializeActivityLabels() {
        val fromIntent = intent.getStringArrayExtra("Activities")
        val defaults = ActivityLabels.defaults(selectedprofession)
        defaultActivityLabels = List(6) { index ->
            fromIntent?.getOrNull(index) ?: defaults[index]
        } + defaults[unlabeledIndex]
        customActivityLabels.indices.forEach { index ->
            customActivityLabels[index] = activityLabelPreferences.getString(labelKey(index), null)
        }
        selectedActivity = activityLabel(unlabeledIndex)
    }

    private fun activityLabel(index: Int): String =
        customActivityLabels[index] ?: defaultActivityLabels[index]

    private fun activityDisplay(index: Int): String =
        customActivityLabels[index] ?: localized(defaultActivityLabels[index])

    private fun selectedActivityDisplay(): String {
        val index = defaultActivityLabels.indices.firstOrNull { activityLabel(it) == selectedActivity }
        return if (index == null) selectedActivity else activityDisplay(index)
    }

    private fun updateUnlabeledButton(prefix: String) {
        binding.toggleActivityButton.text = "$prefix ${activityDisplay(unlabeledIndex)}"
    }

    private fun setActivityButtonsEnabled(enabled: Boolean) {
        binding.toggleActivityButton.isEnabled = enabled
        binding.ActivityButton1.isEnabled = enabled
        binding.ActivityButton2.isEnabled = enabled
        binding.ActivityButton3.isEnabled = enabled
        binding.ActivityButton4.isEnabled = enabled
        binding.ActivityButton5.isEnabled = enabled
        binding.ActivityButton6.isEnabled = enabled
    }

    private fun startTimer() {
        stopTimer()
        updateTimerRunnable = object : Runnable {
            override fun run() {
                if (isRecording && !isPaused && !commandUncertain) {
                    val elapsedTime = android.os.SystemClock.elapsedRealtime() - startTime
                    val seconds = (elapsedTime / 1000) % 60
                    val minutes = (elapsedTime / 60000) % 60
                    val hours = (elapsedTime / 3600000)
                    val milliseconds = elapsedTime % 1000

                    binding.timerTextView.text = String.format("%02d:%02d:%02d:%03d", hours, minutes, seconds, milliseconds)
                    handler.postDelayed(this, 33)
                }
            }
        }
        handler.post(updateTimerRunnable!!)
    }

    private fun stopTimer() {
        updateTimerRunnable?.let { handler.removeCallbacks(it) }
        updateTimerRunnable = null
    }

    private fun updateConnectionStatus(isConnected: Boolean, appOpen: Boolean) {
        when {
            !isConnected -> uiStatusStyle("#402B30", "#54383E", "#FFB8C0")
            appOpen -> uiStatusStyle("#1E3935", "#2D4843", "#D6EDA5")
            else -> uiStatusStyle("#3E3427", "#514335", "#FFD7A6")
        }
        binding.deviceconnectionStatusTv.text = if (isConnected)
            commandText("Watch connected", "Montre connectée")
        else commandText("Watch disconnected", "Montre déconnectée")
        binding.watchStatusSubtitleTv.text = when {
            !isConnected -> commandText("Connection unavailable", "Connexion indisponible")
            appOpen -> commandText("Application running", "Application ouverte")
            else -> commandText("Application closed", "Application fermée")
        }
    }

    // PEMESA_UI_REFRESH_V1: presentation and animation helpers.
    private fun uiColor(hex: String): Int = android.graphics.Color.parseColor(hex)
    private fun uiDp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun uiRounded(fill: Int, radius: Int = 16, stroke: Int? = null): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = uiDp(radius).toFloat()
            if (stroke != null) setStroke(uiDp(1), stroke)
        }

    private fun uiStyleButton(button: android.widget.Button, originalColor: Int) {
        val fill = when (originalColor) {
            ContextCompat.getColor(this, android.R.color.holo_green_light) -> uiColor("#096B69")
            ContextCompat.getColor(this, android.R.color.darker_gray) -> uiColor("#405B6B")
            ContextCompat.getColor(this, android.R.color.holo_orange_light) -> uiColor("#965018")
            ContextCompat.getColor(this, android.R.color.holo_red_dark) -> uiColor("#B32636")
            else -> originalColor
        }
        val outline = androidx.core.graphics.ColorUtils.blendARGB(fill, uiColor("#142D3A"), 0.16f)
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),
                uiRounded(uiColor("#DCE5E8"), 20, uiColor("#CCD8DC")))
            addState(intArrayOf(), uiRounded(fill, 20, outline))
        }
        button.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(
                if (button === binding.startRecordingButton) 0x18173E59 else 0x33FFFFFF
            ), states,
            uiRounded(android.graphics.Color.WHITE, 20)
        )
        button.backgroundTintList = null
        (button as? androidx.appcompat.widget.AppCompatButton)?.supportBackgroundTintList = null
        button.background?.setTintList(null)
        val enabledText = if (button === binding.startRecordingButton) uiColor("#173E59")
            else android.graphics.Color.WHITE
        button.setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(uiColor("#526671"), enabledText)
        ))
        applyModernButtonTypography(button, 15f)
    }

    private fun applyModernButtonTypography(button: Button, sizeSp: Float) {
        button.setAllCaps(false)
        button.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        button.textSize = sizeSp
        button.letterSpacing = 0.01f
        button.gravity = android.view.Gravity.CENTER
        button.minHeight = uiDp(52)
        button.minimumHeight = uiDp(52)
        button.setPadding(uiDp(12), uiDp(10), uiDp(12), uiDp(10))
        button.elevation = 0f
    }

    /** Animate native pressed states without consuming touch events or delaying clicks. */
    private fun installButtonPressEffect(button: Button) {
        fun scaleTo(scale: Float, durationMs: Long): android.animation.AnimatorSet =
            android.animation.AnimatorSet().apply {
                playTogether(
                    android.animation.ObjectAnimator.ofFloat(button, "scaleX", scale),
                    android.animation.ObjectAnimator.ofFloat(button, "scaleY", scale)
                )
                duration = durationMs
                interpolator = android.view.animation.DecelerateInterpolator()
            }

        button.stateListAnimator = android.animation.StateListAnimator().apply {
            addState(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed),
                scaleTo(0.96f, 85L)
            )
            addState(intArrayOf(-android.R.attr.state_enabled), scaleTo(1f, 0L))
            addState(intArrayOf(), scaleTo(1f, 150L))
        }
    }

    private fun uiStatusStyle(fill: String, inner: String, accent: String) {
        binding.watchStatusContainer.apply {
            background = uiRounded(uiColor(fill), 20)
            setPadding(uiDp(12), uiDp(10), uiDp(12), uiDp(10))
        }
        binding.watchStatusIcon.background = uiRounded(uiColor(accent), 50)
        binding.deviceconnectionStatusTv.setTextColor(android.graphics.Color.WHITE)
        binding.watchStatusSubtitleTv.setTextColor(uiColor("#D8E7E4"))
        binding.watchBatteryTv.setTextColor(android.graphics.Color.WHITE)
        binding.influxStatusContainer.apply {
            background = uiRounded(uiColor(inner), 12)
            setPadding(uiDp(8), uiDp(6), uiDp(8), uiDp(6))
        }
    }

    private fun applyUiDesign() {
        binding.root.setBackgroundColor(uiColor("#F1F6F7"))
        uiStatusStyle("#26383B", "#38494C", "#D8E7E4")
        binding.timerTextView.apply {
            background = uiRounded(uiColor("#FFFFFF"), 20, uiColor("#D6E3E6"))
            setTextColor(uiColor("#142D3A"))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 28f
            setPadding(uiDp(16), uiDp(16), uiDp(16), uiDp(16))
        }
        binding.ActivityStatusTextView.apply {
            background = null
            setTextColor(android.graphics.Color.BLACK)
            setPadding(0, 0, 0, 0)
        }
        binding.TirednessValueTextView.setTextColor(uiColor("#142D3A"))
        updateFatigueColor(binding.TirednessSeekBar.progress)
        binding.bottomNavigationView.apply {
            setBackgroundColor(uiColor("#FFFFFF"))
            // Preserve the multicolor watch vector; keep the existing tint on other icons.
            itemIconTintList = null
            listOf(R.id.action_home, R.id.action_refresh_connection).forEach { id ->
                val item = menu.findItem(id) ?: return@forEach
                val drawable = item.icon?.mutate()
                drawable?.setTint(uiColor("#36566B"))
                item.icon = drawable
            }
            elevation = uiDp(4).toFloat()
        }
        uiStyleButton(binding.startRecordingButton, uiColor("#B9E0FA"))
        uiStyleButton(binding.pauseRecordingButton, uiColor("#76521E"))
        uiStyleButton(binding.stopRecordingButton, uiColor("#B32636"))
        updateActivityButtonColor()
        // Install once; later color updates must not restart a running press animation.
        (activityButtons() + listOf(
            binding.toggleActivityButton,
            binding.startRecordingButton,
            binding.pauseRecordingButton,
            binding.stopRecordingButton
        )).forEach(::installButtonPressEffect)
        binding.recordingButtonsContainer.apply {
            alpha = 0.86f
            translationY = uiDp(6).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
    }

    private val activityButtonTargets = mutableMapOf<Button, Int>()
    private val activityButtonColors = mutableMapOf<Button, Int>()
    private val activityButtonAnimations = mutableMapOf<Button, android.animation.ValueAnimator>()

    /** Render the acknowledged activity selection. */
    private fun styleActivityButton(button: Button, fill: Int) {
        if (activityButtonTargets[button] == fill) return
        activityButtonAnimations.remove(button)?.cancel()
        val previous = activityButtonColors[button] ?: uiColor("#FFFFFF")
        val shape = uiRounded(previous, 18, activityOutline(previous))
        val ripple = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x16142D3A),
            shape,
            uiRounded(android.graphics.Color.WHITE, 18)
        )
        button.background = ripple
        // Clear both framework and AppCompat tints after replacing the background.
        button.backgroundTintList = null
        (button as? androidx.appcompat.widget.AppCompatButton)?.supportBackgroundTintList = null
        ripple.setTintList(null)
        val visibleShape = ripple.getDrawable(0) as android.graphics.drawable.GradientDrawable
        visibleShape.setColor(previous)
        visibleShape.setStroke(uiDp(1), activityOutline(previous))
        button.setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(uiColor("#526671"), android.graphics.Color.BLACK)
        ))
        applyModernButtonTypography(button, 14f)
        activityButtonTargets[button] = fill

        if (previous == fill) {
            visibleShape.setColor(fill)
            visibleShape.setStroke(uiDp(1), activityOutline(fill))
            activityButtonColors[button] = fill
            button.invalidate()
            return
        }
        val animation = android.animation.ValueAnimator.ofArgb(previous, fill).apply {
            duration = 180L
            addUpdateListener {
                val color = it.animatedValue as Int
                visibleShape.setColor(color)
                visibleShape.setStroke(uiDp(1), activityOutline(color))
                activityButtonColors[button] = color
                ripple.invalidateSelf()
                button.invalidate()
            }
        }
        activityButtonAnimations[button] = animation
        animation.start()
    }

    private fun activityOutline(fill: Int): Int =
        androidx.core.graphics.ColorUtils.blendARGB(fill, uiColor("#36566B"), 0.24f)

    private fun updateActivityButtonColor() {
        val idle = uiColor("#FFFFFF")
        val selected = uiColor("#D6EDA5")
        val unlabeled = uiColor("#FFD7A6")
        activityButtons().forEachIndexed { index, button ->
            styleActivityButton(button, if (hasSelectedActivity && activityLabel(index) == selectedActivity) selected else idle)
        }
        styleActivityButton(binding.toggleActivityButton,
            if (hasSelectedActivity && selectedActivity == activityLabel(unlabeledIndex)) unlabeled else idle)
    }

    private fun updateFatigueColor(progress: Int) {
        val colors = listOf("#2563EB", "#0EA5E9", "#22A06B", "#E3B341", "#F28C28", "#DC2626")
        val tint = android.content.res.ColorStateList.valueOf(uiColor(colors[progress.coerceIn(0, colors.lastIndex)]))
        binding.TirednessSeekBar.thumbTintList = tint
        binding.TirednessSeekBar.progressTintList = tint
    }

    private fun updateActivityStatusText() {
        binding.ActivityStatusTextView.text = localized("Current Activity:") + " " + selectedActivityDisplay()
    }

    private fun refreshPage() {
        if (!wearableDeviceConnected) {
            lifecycleScope.launch { communicator.checkWearableConnection() }
            commandNotice("Reconnect, then refresh the watch", "Reconnectez puis actualisez la montre")
            return
        }
        confirmedCommand("Action: Refresh") {
        selectedActivity = activityLabel(unlabeledIndex)
        hasSelectedActivity = false
        updateUnlabeledButton("🛑")
        updateActivityButtonColor()
        updateActivityStatusText()

        applyStoppedState()
        confirmedFatigue = 0
        binding.TirednessSeekBar.progress = 0
        binding.timerTextView.text = "00:00:00:000"

        lifecycleScope.launch {
            communicator.checkWearableConnection()
        }

        Toast.makeText(this, localized("Page refreshed"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCurrentFatigueLevel(): Int = binding.TirednessSeekBar.progress

    private fun sendFatigueLevel() {
        if (!isRecording || "fatigue" !in CollectionPreferences(this).sensors) return
        val fatigue = getCurrentFatigueLevel()
        confirmedCommand("FatigueLevel: $fatigue", onFailure = {
            binding.TirednessSeekBar.progress = confirmedFatigue
        }) {
            confirmedFatigue = fatigue
        }
    }

    private fun getElapsedTimeFromText(): Long {
        val parts = binding.timerTextView.text.split(":")
        if (parts.size != 4) return 0
        val hours = parts[0].toLongOrNull() ?: 0
        val minutes = parts[1].toLongOrNull() ?: 0
        val seconds = parts[2].toLongOrNull() ?: 0
        val millis = parts[3].toLongOrNull() ?: 0
        return (hours * 3600000) + (minutes * 60000) + (seconds * 1000) + millis
    }
}
