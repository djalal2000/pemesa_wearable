package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class DiagnosticResult(val code: String, val detail: JSONObject? = null, val roundTripMs: Long = 0)

internal suspend fun runDiagnostics(context: Context, journalOnly: Boolean = false): DiagnosticResult = withContext(Dispatchers.IO) {
    val configuration = try { if (journalOnly) null else CollectionPreferences(context).configuration() }
        catch (_: Exception) { return@withContext DiagnosticResult("CONFIG") }
    val id = UUID.randomUUID().toString()
    val client = Wearable.getMessageClient(context)
    val node = try {
        val target = WatchRegistry(context).target
        Tasks.await(Wearable.getNodeClient(context).connectedNodes, 7, TimeUnit.SECONDS).firstOrNull { it.id == target }
    } catch (_: Exception) { null }
    if (node == null) return@withContext DiagnosticResult("NODE")
    val result = CompletableDeferred<JSONObject>()
    val listener = MessageClient.OnMessageReceivedListener { event ->
        if (event.sourceNodeId == node.id && event.path == "/pemesa/diagnostics-v1") {
            runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull()
                ?.takeIf { it.optString("id") == id }?.let { result.complete(it) }
        }
    }
    try {
        Tasks.await(client.addListener(listener), 7, TimeUnit.SECONDS)
        val request = JSONObject().put("id", id).put("journalOnly", journalOnly)
        configuration?.let { request.put("version", it.version).put("hash", it.hash) }
        val start = android.os.SystemClock.elapsedRealtime()
        Tasks.await(client.sendMessage(node.id, "/pemesa/diagnostics-v1", request.toString().toByteArray(Charsets.UTF_8)), 7, TimeUnit.SECONDS)
        val answer = withTimeoutOrNull(20000L) { result.await() }
        if (answer == null) DiagnosticResult("TIMEOUT")
        else DiagnosticResult("OK", answer, android.os.SystemClock.elapsedRealtime() - start)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { DiagnosticResult("TIMEOUT") }
    finally { result.cancel(); client.removeListener(listener) }
}

internal fun diagnosticHttpIssue(code: Int): String = when {
    code in 200..299 -> "OK"
    code == 401 || code == 403 -> "AUTH"
    code == 404 -> "BUCKET"
    code == 429 || code >= 500 -> "SERVER"
    else -> "CONFIG"
}

@Composable
internal fun DiagnosticsSettings(language: String, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { CollectionPreferences(context) }
    val scope = rememberCoroutineScope()
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var result by remember { mutableStateOf<DiagnosticResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                result = runDiagnostics(context)
                HealthJournal.add(if (result?.code == "OK" && result?.detail?.optString("error").isNullOrBlank()) HealthCode.DIAGNOSTIC_OK else HealthCode.DIAGNOSTIC_FAILED)
            }
            finally { busy = false }
        }
    }
    fun system(action: String) { runCatching { context.startActivity(Intent(action)) } }
    Text(t("Run a manual, read-only check from the watch. No sensors are started and no measurement points are written.",
        "Lancez un contrôle manuel depuis la montre. Aucun capteur n’est démarré et aucun point de mesure n’est écrit."))
    Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = ::run) { Text(t("Analyze application", "Analyser l’application")) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    val report = result
    if (report != null) {
        if (report.code == "CONFIG") {
            DiagnosticCard(t("Configuration", "Configuration"), t("Token or settings cannot be loaded.", "Jeton ou paramètres illisibles."),
                t("Open InfluxDB settings", "Ouvrir les paramètres InfluxDB")) { onNavigate("server") }
        } else if (report.code == "NODE") {
            DiagnosticCard(t("Watch connection", "Connexion montre"), t("Select a registered watch in Settings. Check Bluetooth and pairing.", "Sélectionnez une montre enregistrée dans les paramètres. Vérifiez Bluetooth et l’appairage."),
                t("Open Bluetooth", "Ouvrir Bluetooth")) { system(Settings.ACTION_BLUETOOTH_SETTINGS) }
        } else if (report.code != "OK") {
            DiagnosticCard(t("Watch application", "Application montre"), t("No reply. Open PEMESA on the watch, then retry.", "Aucune réponse. Ouvrez PEMESA sur la montre puis réessayez."),
                t("Retry", "Réessayer"), ::run)
        } else {
            val json = report.detail ?: JSONObject()
            DiagnosticCard(t("Watch connection", "Connexion montre"), t("Reply received in ", "Réponse reçue en ") + "${report.roundTripMs} ms (incl. server checks)")
            val available = json.optJSONArray("sensors")
            if (available != null) {
                val keys = (0 until available.length()).map { available.optString(it) }.toSet()
                val missing = prefs.sensors - keys
                DiagnosticCard(t("Sensor availability", "Disponibilité des capteurs"),
                    if (missing.isEmpty()) t("All selected signals available.", "Tous les signaux sélectionnés sont disponibles.")
                    else t("Unavailable or permission missing: ", "Indisponibles ou autorisation manquante : ") + missing.joinToString(),
                    if (missing.isNotEmpty()) t("Review selection", "Vérifier la sélection") else null) { onNavigate("sensors") }
                if (missing.isNotEmpty()) Text(t("On the watch, check Settings > Apps > PEMESA > Permissions.", "Sur la montre, vérifiez Paramètres > Applications > PEMESA > Autorisations."))
            }
            if (json.optBoolean("busy")) {
                DiagnosticCard("InfluxDB", t("Probe skipped: recording or pending writes. Stop and let writes finish before retrying.", "Test reporté : collecte ou écritures en cours. Arrêtez et attendez la fin des écritures avant de réessayer."))
            } else {
                val probe = json.optJSONObject("probe")
                if (probe != null) {
                    val codes = listOf("activity", "wearable").map { it to probe.optInt(it, 0) }
                    val error = probe.optString("error")
                    val issue = if (error.isNotBlank()) error else codes.map { diagnosticHttpIssue(it.second) }.firstOrNull { it != "OK" } ?: "OK"
                    val advice = when (issue) {
                        "OK" -> t("Both empty write requests accepted.", "Les deux requêtes d’écriture vides sont acceptées.")
                        "AUTH" -> t("Check the API token and write permissions.", "Vérifiez le jeton API et les droits d’écriture.")
                        "BUCKET" -> t("Check organization and bucket names.", "Vérifiez l’organisation et les noms des buckets.")
                        "TLS" -> t("Check certificate validity and watch date/time. Do not disable TLS.", "Vérifiez le certificat et la date de la montre. Ne désactivez pas TLS.")
                        "DNS", "NETWORK" -> t("Check watch Wi-Fi and server address.", "Vérifiez le Wi-Fi de la montre et l’adresse du serveur.")
                        "SERVER" -> t("Server overloaded or unavailable. Retry later.", "Serveur surchargé ou indisponible. Réessayez.")
                        else -> t("Check HTTPS URL and configuration.", "Vérifiez l’URL HTTPS et la configuration.")
                    }
                    DiagnosticCard("InfluxDB", advice + "\n" + codes.joinToString(" · ") { "${it.first}: ${if (it.second == 0) "—" else it.second}" },
                        if (issue != "OK") t("Resolve / settings", "Résoudre / paramètres") else null) { onNavigate("server") }
                    if (issue in setOf("NETWORK", "DNS")) OutlinedButton(onClick = { system(Settings.ACTION_WIFI_SETTINGS) }) { Text(t("Phone Wi-Fi settings", "Wi-Fi du téléphone")) }
                }
            }
            val battery = json.optInt("battery", -1)
            DiagnosticCard(t("Watch battery", "Batterie montre"), if (battery in 0..100) "$battery%" + if (battery <= 20) t(" · Recharge recommended", " · Recharge conseillée") else "" else "—")
            DiagnosticCard(t("Watch memory", "Mémoire montre"), if (json.has("freeMb")) "${json.optLong("freeMb")} MB " + t("available system RAM", "RAM système disponible") else "—")
            val thermal = json.optInt("thermalStatus", -1)
            DiagnosticCard(t("Watch thermal status", "État thermique de la montre"), when (thermal) {
                0 -> t("Normal", "Normal")
                1 -> t("Light thermal throttling", "Limitation thermique légère")
                2 -> t("Moderate thermal throttling", "Limitation thermique modérée")
                3 -> t("Severe thermal throttling — let the watch cool", "Limitation thermique sévère — laissez refroidir la montre")
                4, 5, 6 -> t("Critical thermal state — stop the experiment safely", "État thermique critique — arrêtez l’expérience en sécurité")
                else -> t("Unavailable", "Indisponible")
            })
            json.optJSONObject("writes")?.let { stats ->
                DiagnosticCard(t("Transmission statistics", "Statistiques des transmissions"), t("Pending: ", "En attente : ") + stats.optLong("pending") + t(" · Failed: ", " · Échecs : ") + stats.optLong("failures"))
            }
            json.optJSONObject("acquisition")?.let { acquisition ->
                val hz = acquisition.optDouble("observedHz", Double.NaN)
                DiagnosticCard(t("Observed sampling", "Échantillonnage observé"),
                    t("Target: ", "Cible : ") + acquisition.optLong("targetHz") + " Hz · " +
                        t("Samples: ", "Échantillons : ") + acquisition.optLong("samples") + "\n" +
                        if (hz.isFinite()) t("Observed timer rate: ", "Fréquence observée du timer : ") + String.format(java.util.Locale.ROOT, "%.1f Hz", hz)
                        else t("Not measured yet (minimum 3 seconds).", "Non mesurée (minimum 3 secondes)."))
            }
            Text(t("Timer rate is not the hardware sensor frequency. No recording is started by this test.", "La fréquence du timer ne correspond pas à celle des capteurs matériels. Ce test ne démarre aucune acquisition."), style = MaterialTheme.typography.bodySmall)
        }
    }
    HealthJournalPanel(language, result, busy, onNavigate)
}

@Composable
private fun DiagnosticCard(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}
