package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val DEVICE_PATH = "/pemesa/device-v1"

private suspend fun deviceRequest(context: Context, node: String, action: String): JSONObject? = withContext(Dispatchers.IO) {
    val requestId = UUID.randomUUID().toString()
    val client = Wearable.getMessageClient(context)
    val waiter = CompletableDeferred<JSONObject>()
    val listener = MessageClient.OnMessageReceivedListener { event ->
        if (event.sourceNodeId == node && event.path == DEVICE_PATH) {
            runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull()
                ?.takeIf { it.optString("id") == requestId }?.let { waiter.complete(it) }
        }
    }
    try {
        Tasks.await(client.addListener(listener), 5, TimeUnit.SECONDS)
        val request = JSONObject().put("id", requestId).put("action", action)
        Tasks.await(client.sendMessage(node, DEVICE_PATH, request.toString().toByteArray(Charsets.UTF_8)), 5, TimeUnit.SECONDS)
        withTimeoutOrNull(5000L) { waiter.await() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    finally { waiter.cancel(); client.removeListener(listener) }
}

internal data class FoundWatch(val node: String, val name: String, val info: JSONObject?) {
    val ready: Boolean get() = info?.optBoolean("associated") == true &&
        runCatching { UUID.fromString(info?.optString("watchId").orEmpty()) }.isSuccess
}

private val discoveryMutex = Mutex()

/** Discover already connected Wear OS devices; no PEMESA pairing code. */
internal suspend fun discoverWatches(context: Context): List<FoundWatch> = discoveryMutex.withLock {
    val nodes = withContext(Dispatchers.IO) {
        try { Tasks.await(Wearable.getNodeClient(context).connectedNodes, 5, TimeUnit.SECONDS) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    } ?: return@withLock emptyList()
    val found = coroutineScope { nodes.take(50).map { node -> async {
        FoundWatch(node.id, node.displayName, deviceRequest(context, node.id, "info"))
    } }.awaitAll() }
    WatchRegistry(context).updateDetected(found.filter { it.ready }.map {
        RegisteredWatch(it.node, it.info!!.getString("watchId"), it.name.take(60), System.currentTimeMillis())
    })
    found
}

@Composable
internal fun SmartwatchSettings(language: String) {
    val context = LocalContext.current
    val registry = remember(context) { WatchRegistry(context) }
    val scope = rememberCoroutineScope()
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var found by remember { mutableStateOf<List<FoundWatch>>(emptyList()) }
    var selected by remember { mutableStateOf(registry.selected) }
    var pinned by remember { mutableStateOf(registry.pinned) }
    var busy by remember { mutableStateOf(false) }
    suspend fun refresh() {
        if (busy) return
        busy = true
        try {
            found = discoverWatches(context)
            selected = registry.selected
            pinned = registry.pinned
        } finally { busy = false }
    }
    LaunchedEffect(Unit) {
        while (true) { refresh(); delay(10000L) }
    }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(t("Connected watches", "Montres connectées"), style = MaterialTheme.typography.titleMedium)
            Text(found.size.toString(), style = MaterialTheme.typography.headlineLarge)
            Text(t("Wear OS devices are detected automatically. Open PEMESA on the watch to use it.",
                "Les montres Wear OS sont détectées automatiquement. Ouvrez PEMESA sur la montre pour l’utiliser."))
        }
    }
    if (pinned != null) Text(t("Selection locked until Stop is confirmed on the original watch.",
        "Sélection verrouillée jusqu’à confirmation du Stop sur la montre d’origine."), color = MaterialTheme.colorScheme.error)
    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { scope.launch { refresh() } }) {
        Text(t("Refresh devices", "Actualiser les appareils"))
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (found.isEmpty() && !busy) Text(t("No connected Wear OS watch detected.", "Aucune montre Wear OS connectée détectée."))
    found.forEach { watch ->
        val battery = watch.info?.optInt("battery", -1) ?: -1
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_pemesa_round_watch), null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(watch.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (watch.ready) t("PEMESA ready", "PEMESA prête") else t("Connected — PEMESA unavailable", "Connectée — PEMESA indisponible"),
                            color = if (watch.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        if (battery in 0..100) Text("🔋 $battery%")
                    }
                }
                if (selected == watch.node && watch.ready) Text(t("Selected for recording", "Sélectionnée pour la collecte"), color = MaterialTheme.colorScheme.primary)
                else if (watch.ready) Button(enabled = !busy && pinned == null, modifier = Modifier.fillMaxWidth(), onClick = {
                    if (registry.select(watch.node)) selected = registry.selected
                }) { Text(t("Select smartwatch", "Sélectionner la montre")) }
            }
        }
    }
}
