package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class ConfigSnapshot(val version: String, val hash: String, val payload: String) {
    fun startEnvelope(session: String): JSONObject = JSONObject().put("session", session)
        .put("version", version).put("hash", hash)
}
internal enum class ConfigSyncResult { CONFIRMED, OFFLINE, BUSY, ERROR, CHANGED }

/** Synchronizes the phone configuration with the selected watch. */
internal object ConfigurationSync {
    private val mutex = Mutex()
    private suspend fun exchange(context: Context, node: String, watchId: String, body: JSONObject): JSONObject? {
        val id = UUID.randomUUID().toString()
        body.put("id", id)
        val result = CompletableDeferred<JSONObject>()
        val client = Wearable.getMessageClient(context)
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.sourceNodeId == node && event.path == CONFIG_PATH) {
                runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull()
                    ?.takeIf { it.optString("id") == id && it.optString("watchId") == watchId }
                    ?.let { result.complete(it) }
            }
        }
        try {
            Tasks.await(client.addListener(listener), 3, TimeUnit.SECONDS)
            Tasks.await(client.sendMessage(node, CONFIG_PATH, body.toString().toByteArray(Charsets.UTF_8)), 3, TimeUnit.SECONDS)
            return withTimeoutOrNull(5000L) { result.await() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        finally { result.cancel(); client.removeListener(listener) }
    }

    suspend fun synchronize(context: Context, requestedNode: String? = null, forStart: Boolean = false): ConfigSyncResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val registry = WatchRegistry(context)
                val node = requestedNode ?: registry.target ?: return@withLock ConfigSyncResult.OFFLINE
                if (registry.target != node || (!forStart && registry.pinned != null)) return@withLock ConfigSyncResult.BUSY
                val watch = registry.list().firstOrNull { it.node == node } ?: return@withLock ConfigSyncResult.OFFLINE
                val prefs = CollectionPreferences(context)
                val snapshot = try { prefs.configuration() } catch (_: Exception) { return@withLock ConfigSyncResult.ERROR }
                val reachable = try {
                    Tasks.await(Wearable.getNodeClient(context).connectedNodes, 3, TimeUnit.SECONDS).any { it.id == node }
                } catch (_: Exception) { false }
                if (!reachable) return@withLock ConfigSyncResult.OFFLINE
                val status = exchange(context, node, watch.id, JSONObject().put("action", "status"))
                    ?: return@withLock ConfigSyncResult.OFFLINE
                if (status.optString("result") != "OK") return@withLock if (status.optString("result") == "BUSY") ConfigSyncResult.BUSY else ConfigSyncResult.ERROR
                val oldVersion = status.optString("version")
                val oldHash = status.optString("hash")
                if (prefs.version() != snapshot.version || registry.target != node) return@withLock ConfigSyncResult.CHANGED
                if (configMatches(snapshot.version, snapshot.hash, oldVersion, oldHash)) return@withLock ConfigSyncResult.CONFIRMED
                if (!forStart && registry.pinned != null) return@withLock ConfigSyncResult.BUSY
                val reply = exchange(context, node, watch.id, JSONObject().put("action", "save")
                    .put("version", snapshot.version).put("hash", snapshot.hash).put("payload", snapshot.payload)
                    .put("expectedVersion", oldVersion).put("expectedHash", oldHash))
                    ?: return@withLock ConfigSyncResult.OFFLINE
                if (reply.optString("result") == "BUSY") return@withLock ConfigSyncResult.BUSY
                if (reply.optString("result") != "OK" ||
                    !configMatches(snapshot.version, snapshot.hash, reply.optString("version"), reply.optString("hash"))) return@withLock ConfigSyncResult.ERROR
                if (prefs.version() != snapshot.version || registry.target != node) ConfigSyncResult.CHANGED
                else ConfigSyncResult.CONFIRMED
            }
        }

    /** Verify the saved configuration revision without transferring its contents. */
    suspend fun inspectLocal(context: Context): PhoneConfigurationView = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = CollectionPreferences(context)
            val snapshot = runCatching { prefs.configuration() }.getOrNull()
            val registry = WatchRegistry(context)
            val node = registry.target
            val watch = registry.list().firstOrNull { it.node == node }
            val payload = snapshot?.let { JSONObject(it.payload) }
            val server = payload?.optJSONObject("server")
            val array = payload?.optJSONArray("sensors")
            val sensors = array?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: prefs.sensors
            val local = PhoneConfigurationView(
                watchName = watch?.name ?: "", version = snapshot?.version ?: "",
                hz = payload?.getInt("hz") ?: prefs.hz, interval = payload?.getInt("interval") ?: prefs.interval,
                sensors = sensors, custom = payload?.has("server") ?: prefs.custom,
                url = server?.optString("url") ?: prefs.url, org = server?.optString("org") ?: prefs.organization,
                activity = server?.optString("activity") ?: prefs.activity,
                wearable = server?.optString("wearable") ?: prefs.wearable,
                tokenConfigured = server?.optString("token")?.isNotBlank() == true
            )
            if (snapshot == null) return@withLock local.copy(result = "ERROR")
            if (node == null || watch == null) return@withLock local.copy(result = "NO_WATCH")
            val reply = exchange(context, node, watch.id, JSONObject().put("action", "status"))
                ?: return@withLock local.copy(result = "OFFLINE")
            if (registry.target != node || prefs.version() != snapshot.version)
                return@withLock local.copy(result = "CHANGED")
            val result = when {
                reply.optString("result") == "BUSY" -> "BUSY"
                reply.optString("result") != "OK" -> "ERROR"
                configMatches(snapshot.version, snapshot.hash, reply.optString("version"), reply.optString("hash")) -> "CONFIRMED"
                else -> "DIFFERENT"
            }
            local.copy(result = result, checkedAt = System.currentTimeMillis())
        }
    }

    fun message(result: ConfigSyncResult, french: Boolean): String = when (result) {
        ConfigSyncResult.CONFIRMED -> if (french) "Enregistré et confirmé par la montre." else "Saved and confirmed by the watch."
        ConfigSyncResult.OFFLINE -> if (french) "Enregistré sur le téléphone. Synchronisation en attente de la montre." else "Saved on phone. Waiting for the watch."
        ConfigSyncResult.BUSY -> if (french) "Enregistré. Synchronisation en attente de la fin de la collecte." else "Saved. Waiting for the watch to be idle."
        ConfigSyncResult.ERROR -> if (french) "Enregistré sur le téléphone. Synchronisation non confirmée." else "Saved on phone. Synchronization not confirmed."
        ConfigSyncResult.CHANGED -> if (french) "Configuration modifiée. Nouvelle synchronisation nécessaire." else "Configuration changed. Synchronization required."
    }
}
