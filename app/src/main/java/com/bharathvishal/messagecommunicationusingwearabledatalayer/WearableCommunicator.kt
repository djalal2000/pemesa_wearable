package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.google.android.gms.tasks.Task
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

internal data class InfluxWriteStatus(
    val activity: String,
    val wearable: String,
    val pending: Long,
    val failures: Long
)

class WearableCommunicator(
    private val context: Context,
    private val onMessageReceivedCallback: (String) -> Unit,
    private val onWearableConnected: (Boolean, Boolean) -> Unit
) : MessageClient.OnMessageReceivedListener {

    private val registry = WatchRegistry(context)
    private val wearableAppCheckPayload = "AppOpenWearable"
    private val wearableAppCheckPayloadReturnACK = "AppOpenWearableACK"
    private val connectionCheckMutex = Mutex()
    @Volatile private var pendingConnectionAck: CompletableDeferred<Unit>? = null
    @Volatile private var listenerRegistration: Task<Void>? = null
    private val connectionTimeoutMs = 7000L

    private val APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD"
    private val MESSAGE_ITEM_RECEIVED_PATH = "/message-item-received"
    // PEMESA_WATCH_BATTERY_V1
    private val WATCH_BATTERY_PATH = "/pemesa/watch-battery"
    var onWatchBatteryLevel: ((Int?) -> Unit)? = null

    private val influxStatusPath = "/pemesa/influx-status-v1"
    private val influxEventPath = "/pemesa/influx-event-v1"
    private val influxOrder = InfluxStatusOrder()
    @Volatile private var influxRequestId: String? = null
    internal var onInfluxStatus: ((InfluxWriteStatus) -> Unit)? = null

    fun requestInfluxStatus() {
        val node = lastNodeId ?: return
        val id = java.util.UUID.randomUUID().toString()
        influxRequestId = id
        Wearable.getMessageClient(context)
            .sendMessage(node, influxStatusPath, id.toByteArray(StandardCharsets.UTF_8))
            .addOnFailureListener { error -> Log.w("WearableComm", "InfluxDB status request failed", error) }
    }

    private fun receiveInfluxStatus(event: MessageEvent, message: String) {
        if (listenerRegistration == null || event.sourceNodeId != lastNodeId || event.sourceNodeId != registry.target) return
        try {
            val reply = org.json.JSONObject(message)
            val solicited = event.path == influxStatusPath
            if (solicited && reply.optString("id") != influxRequestId) return
            val status = InfluxWriteStatus(
                reply.getString("activity"), reply.getString("wearable"),
                reply.getLong("pending"), reply.getLong("failures")
            )
            if (validInfluxStatus(status) && influxOrder.accept(event.sourceNodeId,
                    reply.optString("stream"), reply.optLong("revision", -1L), solicited)) {
                onInfluxStatus?.invoke(status)
            }
        } catch (error: Exception) {
            Log.w("WearableComm", "Invalid InfluxDB status reply", error)
        }
    }

    fun requestWatchBattery() {
        val nodeId = lastNodeId ?: return
        try {
            Wearable.getMessageClient(context)
                .sendMessage(nodeId, WATCH_BATTERY_PATH,
                    "get".toByteArray(StandardCharsets.UTF_8))
                .addOnFailureListener { error ->
                    Log.w("WearBattery", "Battery request failed", error)
                }
        } catch (error: Exception) {
            Log.w("WearBattery", "Battery request failed", error)
        }
    }


    @Volatile var lastNodeId: String? = null

    /**
     * Checks transport availability and waits for the watch application to acknowledge the ping.
     * Acknowledgements are delivered by the message listener rather than a polling loop.
     */
    suspend fun checkWearableConnection() = withContext(Dispatchers.IO) {
        connectionCheckMutex.withLock {
            val registration = listenerRegistration ?: return@withLock
            var ack: CompletableDeferred<Unit>? = null
            try {
                Tasks.await(registration, connectionTimeoutMs, TimeUnit.MILLISECONDS)
                if (listenerRegistration !== registration) return@withLock

                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes,
                    connectionTimeoutMs, TimeUnit.MILLISECONDS)
                if (listenerRegistration !== registration) return@withLock
                val target = selectedReachableNode(registry.selected, registry.pinned, nodes.map { it.id })
                val node = nodes.firstOrNull { it.id == target }
                if (node == null) {
                    lastNodeId = null
                    withContext(Dispatchers.Main) {
                        if (listenerRegistration === registration) onWearableConnected(false, false)
                    }
                    return@withLock
                }

                lastNodeId = node.id
                // Register the acknowledgement waiter before sending.
                val response = CompletableDeferred<Unit>()
                ack = response
                pendingConnectionAck = response
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, APP_OPEN_WEARABLE_PAYLOAD_PATH,
                        wearableAppCheckPayload.toByteArray(StandardCharsets.UTF_8))
                    .addOnFailureListener { error -> response.completeExceptionally(error) }

                val appOpen = withTimeoutOrNull(connectionTimeoutMs) {
                    response.await()
                    true
                } ?: false

                if (appOpen) registry.seen(node.id)
                withContext(Dispatchers.Main) {
                    if (listenerRegistration === registration) onWearableConnected(true, appOpen)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                HealthJournal.add(HealthCode.WATCH_TIMEOUT)
                Log.w("WearableComm", "Connection check failed", error)
                withContext(Dispatchers.Main) {
                    if (listenerRegistration === registration) {
                        lastNodeId = null
                        onWearableConnected(false, false)
                    }
                }
            } finally {
                if (pendingConnectionAck === ack) pendingConnectionAck = null
                ack?.cancel()
            }
        }
    }

    // PEMESA_COMMAND_ACK_V1
    private val commandPath = "/pemesa/command-v1"
    private val commandAckPath = "/pemesa/command-ack-v1"
    private data class PendingCommand(
        val nodeId: String,
        val result: kotlinx.coroutines.CompletableDeferred<String>
    )
    private val pendingCommands = java.util.concurrent.ConcurrentHashMap<String, PendingCommand>()

    /** Wait for execution on the watch, not merely successful transport submission. */
    suspend fun sendConfirmedCommand(message: String, configuration: org.json.JSONObject? = null): String {
        val node = lastNodeId?.takeIf { it == registry.target } ?: return "NO_CONNECTION"
        val id = java.util.UUID.randomUUID().toString()
        val pending = PendingCommand(node, kotlinx.coroutines.CompletableDeferred())
        pendingCommands[id] = pending
        try {
            val envelope = org.json.JSONObject().put("id", id).put("payload", message)
            if (configuration != null) envelope.put("configuration", configuration)
            Wearable.getMessageClient(context)
                .sendMessage(node, commandPath, envelope.toString().toByteArray(StandardCharsets.UTF_8))
                .addOnFailureListener { error ->
                    Log.w("WearableComm", "Command transport failed", error)
                    pending.result.complete("UNKNOWN")
                }
            return kotlinx.coroutines.withTimeoutOrNull(if (configuration != null) 25000L else 3000L) { pending.result.await() } ?: "UNKNOWN"
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("WearableComm", "Command could not be confirmed", error)
            return "UNKNOWN"
        } finally {
            pendingCommands.remove(id)
            pending.result.cancel()
        }
    }

    private fun receiveCommandAck(event: MessageEvent, message: String) {
        try {
            val ack = org.json.JSONObject(message)
            val pending = pendingCommands[ack.getString("id")] ?: return
            val result = ack.getString("result")
            if (validCommandAck(pending.nodeId, event.sourceNodeId, result)) pending.result.complete(result)
        } catch (error: Exception) {
            HealthJournal.add(HealthCode.INTERNAL_ERROR)
            Log.w("WearableComm", "Invalid command acknowledgement", error)
        }
    }

    fun sendMessage(message: String) {
        val nodeId = lastNodeId?.takeIf { it == registry.target }
        if (nodeId == null) {
            Log.w("WearableSend", "❌ Aucun noeud disponible pour l'envoi : $message")
            Toast.makeText(context, context.localized("No watch connected"), Toast.LENGTH_SHORT).show()
            return
        }

        val payload = message.toByteArray()
        Log.d("WearableSend", "📤 Envoi du message : $message à $nodeId")

        Wearable.getMessageClient(context)
            .sendMessage(nodeId, MESSAGE_ITEM_RECEIVED_PATH, payload)
            .addOnCompleteListener {
                if (it.isSuccessful) {
                    Log.d("WearableSend", "✅ Message envoyé avec succès à $nodeId : $message")
                } else {
                    Log.e("WearableSend", "❌ Échec de l'envoi du message à $nodeId : $message", it.exception)
                    Toast.makeText(context, context.localized("Failed to send message"), Toast.LENGTH_SHORT).show()
                }
            }
    }

    fun registerListeners() {
        listenerRegistration = Wearable.getMessageClient(context).addListener(this)
        Log.d("WearableListener", "📡 Listener enregistré pour les messages")
    }

    fun unregisterListeners() {
        // A command may have executed even if the listener is removed before its reply.
        pendingCommands.values.forEach { it.result.complete("UNKNOWN") }
        // Invalidate the registration before releasing the listener.
        listenerRegistration = null
        influxRequestId = null
        influxOrder.clear()
        pendingConnectionAck?.cancel()
        Wearable.getMessageClient(context).removeListener(this)
        Log.d("WearableListener", "📴 Listener supprimé pour les messages")
    }

    override fun onMessageReceived(event: MessageEvent) {
        val message = String(event.data, StandardCharsets.UTF_8)
        Log.d("WearableReceive", "Message received: ${event.path}")

        when (event.path) {
            APP_OPEN_WEARABLE_PAYLOAD_PATH -> {
                if (listenerRegistration != null && event.sourceNodeId == lastNodeId &&
                    message == wearableAppCheckPayloadReturnACK) {
                    pendingConnectionAck?.complete(Unit)
                }
                Log.d("WearableACK", "📥 ACK reçu pour vérification d'ouverture : $message")
            }
            WATCH_BATTERY_PATH -> {
                if (event.sourceNodeId == lastNodeId) {
                    onWatchBatteryLevel?.invoke(message.toIntOrNull()?.takeIf { it in 0..100 })
                }
            }
            influxStatusPath, influxEventPath -> receiveInfluxStatus(event, message)
            commandAckPath -> receiveCommandAck(event, message)
            MESSAGE_ITEM_RECEIVED_PATH -> {
                if (event.sourceNodeId == lastNodeId && event.sourceNodeId == registry.target) onMessageReceivedCallback(message)
            }
            else -> {
                Log.w("WearableReceive", "⚠️ Message reçu avec un chemin inconnu : ${event.path}")
            }
        }
    }
}
