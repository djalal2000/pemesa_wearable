package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val DEVICE_PATH = "/pemesa/device-v1"

private suspend fun deviceRequest(context: Context, node: String, action: String, code: String? = null): JSONObject? = withContext(Dispatchers.IO) {
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
        if (code != null) request.put("code", code)
        Tasks.await(client.sendMessage(node, DEVICE_PATH, request.toString().toByteArray(Charsets.UTF_8)), 5, TimeUnit.SECONDS)
        withTimeoutOrNull(5000L) { waiter.await() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    finally { waiter.cancel(); client.removeListener(listener) }
}

private data class FoundWatch(val node: String, val name: String, val info: JSONObject?)

@Composable
internal fun SmartwatchSettings(language: String) {
    val context = LocalContext.current
    val registry = remember(context) { WatchRegistry(context) }
    val scope = rememberCoroutineScope()
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var saved by remember { mutableStateOf(registry.list()) }
    var selected by remember { mutableStateOf(registry.selected) }
    var pinned by remember { mutableStateOf(registry.pinned) }
    var found by remember { mutableStateOf<List<FoundWatch>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf<RegisteredWatch?>(null) }
    var draft by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<RegisteredWatch?>(null) }
    fun reload() { saved = registry.list(); selected = registry.selected; pinned = registry.pinned }
    suspend fun scan() {
        val nodes = withContext(Dispatchers.IO) {
            runCatching { Tasks.await(Wearable.getNodeClient(context).connectedNodes, 5, TimeUnit.SECONDS) }.getOrNull()
        }.orEmpty().take(50)
        found = coroutineScope { nodes.map { node -> async {
            FoundWatch(node.id, node.displayName, deviceRequest(context, node.id, "info"))
        } }.awaitAll() }
        found.forEach { item ->
            if (item.info?.optBoolean("associated") == true &&
                saved.any { it.node == item.node && it.id == item.info.optString("watchId") }) registry.seen(item.node)
        }
        reload()
    }
    fun refresh() {
        if (busy) return
        busy = true
        scope.launch { try { scan() } finally { busy = false } }
    }
    LaunchedEffect(Unit) { refresh() }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(t("Registered watches", "Montres enregistrées"), style = MaterialTheme.typography.titleMedium)
            Text(saved.size.toString(), style = MaterialTheme.typography.headlineLarge)
            Text(t("One selected watch per recording.", "Une seule montre sélectionnée par acquisition."))
        }
    }
    if (pinned != null) Text(t("Selection locked until Stop is confirmed on the original watch.",
        "Sélection verrouillée jusqu’à confirmation du Stop sur la montre d’origine."), color = MaterialTheme.colorScheme.error)
    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = ::refresh) { Text(t("Refresh devices", "Actualiser les appareils")) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    saved.forEach { watch ->
        val connected = found.firstOrNull { it.node == watch.node && it.info?.optBoolean("associated") == true && it.info.optString("watchId") == watch.id }
        val battery = connected?.info?.optInt("battery", -1) ?: -1
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_pemesa_round_watch), null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(watch.name, style = MaterialTheme.typography.titleMedium)
                        Text("PEMESA · " + watch.id.take(8), style = MaterialTheme.typography.bodySmall)
                        Text(if (connected != null) t("Connected", "Connectée") else t("Unavailable", "Indisponible"),
                            color = if (connected != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Text(if (battery in 0..100) "🔋 $battery%" else t("Battery unavailable", "Batterie indisponible"))
                        if (watch.lastSeen > 0) Text(t("Last seen: ", "Dernière connexion : ") + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(watch.lastSeen)), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (selected == watch.node) Text(t("Selected for recording", "Sélectionnée pour la collecte"), color = MaterialTheme.colorScheme.primary)
                else Button(enabled = !busy && pinned == null && connected != null, modifier = Modifier.fillMaxWidth(), onClick = {
                    if (registry.select(watch.node)) reload()
                }) { Text(t("Select smartwatch", "Sélectionner la montre")) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(enabled = !busy, onClick = { rename = watch; draft = watch.name }) { Text(t("Rename", "Renommer")) }
                    TextButton(enabled = !busy && connected != null, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val result = deviceRequest(context, watch.node, "identify")
                                notice = if (result?.optBoolean("ok") == true) t("Identification sent", "Identification envoyée") else t("No reply", "Aucune réponse")
                            } finally { busy = false }
                        }
                    }) { Text(t("Identify", "Identifier")) }
                    TextButton(enabled = !busy && pinned == null, onClick = { remove = watch }) { Text(t("Forget", "Retirer")) }
                }
            }
        }
    }
    Button(enabled = !busy && pinned == null, modifier = Modifier.fillMaxWidth(), onClick = { adding = !adding; candidate = null; pin = ""; if (adding) refresh() }) {
        Text(t("Add smartwatch", "Ajouter une montre"))
    }
    if (adding) {
        Text(t("Pair the watch in Android/Galaxy Wearable first. Open PEMESA on the watch and tap Pair with phone.",
            "Associez d’abord la montre dans Android/Galaxy Wearable. Ouvrez PEMESA sur la montre et appuyez sur Pair with phone."))
        TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) { Text(t("Open Bluetooth settings", "Ouvrir Bluetooth")) }
        found.forEach { item ->
            if (item.info != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = candidate == item.node, enabled = !busy, onClick = { candidate = item.node; pin = "" })
                    Text(item.name)
                }
            }
        }
        if (found.none { it.info != null } && !busy) Text(t("No responding PEMESA watch. Open the watch app, then refresh.",
            "Aucune montre PEMESA ne répond. Ouvrez l’application montre puis actualisez."))
        OutlinedTextField(value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(6) },
            label = { Text(t("Six-digit pairing code", "Code à six chiffres")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Button(enabled = !busy && candidate != null && pin.length == 6 && pinned == null, modifier = Modifier.fillMaxWidth(), onClick = {
            val node = candidate ?: return@Button
            val code = pin; pin = ""; busy = true
            scope.launch {
                try {
                    val result = deviceRequest(context, node, "pair", code)
                    val item = found.firstOrNull { it.node == node }
                    if (result?.optBoolean("ok") == true && item != null &&
                        registry.register(node, result.optString("watchId"), item.name)) {
                        adding = false; candidate = null; notice = t("Watch associated", "Montre associée")
                        scan()
                    } else notice = t("Association failed. Check the code, expiry and watch state.", "Association impossible. Vérifiez le code, sa validité et l’état de la montre.")
                } finally { busy = false }
            }
        }) { Text(t("Pair device", "Associer la montre")) }
    }
    rename?.let { watch ->
        AlertDialog(onDismissRequest = { rename = null }, title = { Text(t("Rename smartwatch", "Renommer la montre")) },
            text = { OutlinedTextField(draft, { draft = it.take(60) }, singleLine = true) },
            confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = { if (registry.rename(watch.node, draft)) reload(); rename = null }) { Text(t("Save", "Enregistrer")) } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text(t("Cancel", "Annuler")) } })
    }
    remove?.let { watch ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text(t("Forget this watch?", "Retirer cette montre ?")) },
            text = { Text(t("Removes the phone registration only. Android pairing and existing measurements stay unchanged. Re-associate using a new code.",
                "Retire uniquement l’enregistrement sur le téléphone. Appairage Android et mesures inchangés. Réassociez avec un nouveau code.")) },
            confirmButton = { TextButton(onClick = { if (registry.forget(watch.node)) reload(); remove = null }) { Text(t("Forget", "Retirer")) } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text(t("Cancel", "Annuler")) } })
    }
    if (notice.isNotBlank()) Text(notice)
}
