package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun HealthJournalPanel(language: String, report: DiagnosticResult?, analysisBusy: Boolean, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var local by remember { mutableStateOf(HealthJournal.snapshot()) }
    var remote by remember { mutableStateOf<List<HealthEvent>>(emptyList()) }
    var remoteState by remember { mutableStateOf("NONE") }
    var filter by remember { mutableStateOf("ALL") }
    var live by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf(50) }
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT) }
    fun textLine(source: String, event: HealthEvent) =
        "${formatter.format(Date(event.time))} [$source] [${event.code.level}] ${event.code.module}: ${event.code.name}" +
            (event.value?.let { " ($it)" } ?: "") + if (event.count > 1) " ×${event.count}" else ""
    fun update(answer: DiagnosticResult?) {
        if (answer == null) return
        val array = answer.detail?.optJSONArray("journal")
        if (answer.code == "OK" && array != null) { remote = parseHealthJson(array); remoteState = "OK" }
        else remoteState = "STALE"
    }
    LaunchedEffect(report) { update(report); local = HealthJournal.snapshot() }
    LaunchedEffect(Unit) { while (isActive) { local = HealthJournal.snapshot(); delay(1500L) } }
    LaunchedEffect(live, analysisBusy) {
        while (live && !analysisBusy && isActive) {
            if (!refreshing) {
                refreshing = true
                try { update(runDiagnostics(context, true)) } finally { refreshing = false }
            }
            delay(15000L)
        }
    }
    val entries = (local.map { "PHONE" to it } + remote.map { "WATCH" to it }).sortedByDescending { it.second.time }
    val text = "PEMESA technical journal\nWatch snapshot: $remoteState\n" +
        entries.joinToString("\n") { textLine(it.first, it.second) }
    var exportText by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val content = exportText
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false) }
                notice = if (ok) t("Report exported", "Rapport exporté") else t("Export failed", "Échec de l’export")
            }
        }
    }
    HorizontalDivider()
    Text(t("Technical event journal", "Journal des défauts"), style = MaterialTheme.typography.titleLarge)
    Text(t("Up to 500 recent entries per device, in memory only. Repeated events are grouped. No tokens, participant IDs or sensor values.",
        "Jusqu’à 500 événements récents par appareil, en mémoire uniquement. Les répétitions sont regroupées. Aucun jeton, identifiant participant ou valeur de capteur."))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("INFO", "WARNING", "ERROR", "CRITICAL").forEach { level ->
            Column(Modifier.weight(1f)) {
                Text(entries.count { it.second.code.level == level }.toString(), style = MaterialTheme.typography.titleLarge)
                Text(level, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Row {
        Text(t("Live watch journal", "Journal montre en direct"), modifier = Modifier.weight(1f))
        Switch(checked = live, onCheckedChange = { live = it }, enabled = !analysisBusy)
    }
    Text(when (remoteState) {
        "OK" -> t("Watch snapshot received", "Journal montre reçu")
        "STALE" -> t("Watch unavailable; previous snapshot may be stale", "Montre indisponible ; dernier journal possiblement ancien")
        else -> t("Watch journal not yet loaded", "Journal montre non chargé")
    }, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(enabled = !refreshing && !analysisBusy && !live, onClick = {
        refreshing = true
        scope.launch { try { update(runDiagnostics(context, true)) } finally { refreshing = false } }
    }, modifier = Modifier.fillMaxWidth()) { Text(t("Refresh watch journal", "Actualiser le journal montre")) }
    if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("ALL", "WARNING", "ERROR", "CRITICAL").forEach { item ->
            FilterChip(selected = filter == item, onClick = { filter = item; limit = 50 },
                label = { Text(if (item == "ALL") t("All", "Tout") else item, style = MaterialTheme.typography.labelSmall) })
        }
    }
    val shown = entries.filter { filter == "ALL" || it.second.code.level == filter }
    if (shown.isEmpty()) Text(t("No recorded events in this filter.", "Aucun événement enregistré dans ce filtre."))
    shown.take(limit).forEach { (source, event) ->
        val color = when (event.code.level) { "ERROR", "CRITICAL" -> MaterialTheme.colorScheme.error
            "WARNING" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.primary }
        Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${event.code.level} · $source · ${formatter.format(Date(event.time))}", color = color, style = MaterialTheme.typography.labelSmall)
                Text(event.code.name + (event.value?.let { " ($it)" } ?: "") + if (event.count > 1) " ×${event.count}" else "")
                when (event.code) {
                    HealthCode.WRITE_HTTP, HealthCode.PREFLIGHT_FAILED, HealthCode.CONFIG_INVALID ->
                        TextButton(onClick = { onNavigate("server") }) { Text(t("Check InfluxDB settings", "Vérifier les paramètres InfluxDB")) }
                    HealthCode.SENSOR_PERMISSION, HealthCode.SENSOR_REGISTER_FAILED, HealthCode.SENSOR_MISSING, HealthCode.SENSOR_INVALID ->
                        TextButton(onClick = { onNavigate("sensors") }) { Text(t("Review sensors and watch permissions", "Vérifier les capteurs et autorisations montre")) }
                    HealthCode.QUEUE_FULL, HealthCode.WRITE_FAILED, HealthCode.WRITE_RETRY ->
                        Text(t("Check watch network and wait for outstanding writes.", "Vérifiez le réseau montre et attendez les écritures restantes."), style = MaterialTheme.typography.bodySmall)
                    HealthCode.THERMAL_HIGH, HealthCode.THERMAL_CRITICAL ->
                        Text(t("Let the watch cool. Stop the experiment safely if the warning persists.", "Laissez refroidir la montre. Arrêtez l’expérience en sécurité si l’alerte persiste."), style = MaterialTheme.typography.bodySmall)
                    HealthCode.COMMAND_UNKNOWN, HealthCode.WATCH_LOST, HealthCode.WATCH_TIMEOUT ->
                        Text(t("Check the watch connection and confirm recording state.", "Vérifiez la connexion et confirmez l’état de collecte."), style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
            }
        }
    }
    if (shown.size > limit) TextButton(onClick = { limit = minOf(1000, limit + 50) }) { Text(t("Show more", "Afficher plus")) }
    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("PEMESA diagnostics", text))
        notice = t("Report copied", "Rapport copié")
    }) { Text(t("Copy report", "Copier le rapport")) }
    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { exportText = text; exporter.launch("pemesa-diagnostics.txt") }) {
        Text(t("Export report", "Exporter le rapport"))
    }
    TextButton(onClick = { clear = true }) { Text(t("Clear phone journal", "Vider le journal du téléphone")) }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text(t("Clear technical journal?", "Vider le journal technique ?")) },
        text = { Text(t("Only phone events are removed. Watch events and measurements are not changed.", "Seuls les événements du téléphone sont effacés. Journal montre et mesures inchangés.")) },
        confirmButton = { TextButton(onClick = { HealthJournal.clear(); local = emptyList(); clear = false }) { Text(t("Clear", "Vider")) } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text(t("Cancel", "Annuler")) } })
    if (notice.isNotBlank()) Text(notice)
}
