package com.bharathvishal.messagecommunicationusingwearabledatalayer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun ConfigField(value: String, change: (String) -> Unit, label: String, enabled: Boolean,
                        suggestions: List<String>, historyLabel: String) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, enabled = enabled,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            trailingIcon = if (suggestions.isEmpty()) null else {
                { IconButton(enabled = enabled, onClick = { expanded = true }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = historyLabel)
                } }
            })
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            suggestions.take(SERVER_HISTORY_LIMIT).forEach { previous ->
                DropdownMenuItem(text = { Text(previous) }, onClick = { expanded = false; change(previous) })
            }
        }
    }
}

@Composable
internal fun ServerSettings(language: String) {
    val context = LocalContext.current
    val prefs = remember(context) { CollectionPreferences(context) }
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var url by remember { mutableStateOf(if (prefs.custom) prefs.url else BuildConfig.PEMESA_INFLUX_URL) }
    var org by remember { mutableStateOf(if (prefs.custom) prefs.organization else BuildConfig.PEMESA_INFLUX_ORG) }
    var activity by remember { mutableStateOf(prefs.activity) }
    var wearable by remember { mutableStateOf(prefs.wearable) }
    fun defaultToken(url: String, org: String): String =
        if (validServerUrl(url.trim()) && url.trim() == BuildConfig.PEMESA_INFLUX_URL &&
            org.trim().isNotBlank() && org.trim() == BuildConfig.PEMESA_INFLUX_ORG)
            BuildConfig.PEMESA_INFLUX_TOKEN else ""
    var token by remember { mutableStateOf(if (prefs.hasToken) "" else defaultToken(url, org)) }
    var savedToken by remember { mutableStateOf(prefs.hasToken) }
    var savedTarget by remember { mutableStateOf(ServerTarget(prefs.url, prefs.organization, prefs.activity, prefs.wearable)) }
    var history by remember { mutableStateOf(ServerHistorySuggestions()) }
    var tokenVisible by remember { mutableStateOf(false) }
    var confirmHistoryClear by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(prefs.custom) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { history = withContext(Dispatchers.IO) { prefs.serverHistory() } }
    val target = ServerTarget(url.trim(), org.trim(), activity.trim(), wearable.trim())
    val canKeepToken = savedToken && target == savedTarget
    fun destinationChanged() {
        tokenVisible = false
        val destination = ServerTarget(url.trim(), org.trim(), activity.trim(), wearable.trim())
        token = if (savedToken && destination == savedTarget) "" else defaultToken(url, org)
    }
    val historyLabel = t("Previous values", "Valeurs précédentes")
    Text(if (custom) t("Custom server", "Serveur personnalisé") else t("Server not configured", "Serveur non configuré"),
        style = MaterialTheme.typography.titleMedium)
    Text(t("HTTPS only. Configuration is required before recording. Save synchronizes the watch; changes apply at the next Start. Token encrypted on both devices.",
        "HTTPS uniquement. Configuration obligatoire avant la collecte. Enregistrer synchronise la montre ; réglages appliqués au prochain Start. Jeton chiffré sur les deux appareils."))
    ConfigField(url, { url = it; destinationChanged() }, t("Server HTTPS URL", "URL HTTPS du serveur"), !busy, history.fields["url"].orEmpty(), historyLabel)
    ConfigField(org, { org = it; destinationChanged() }, t("Organization", "Organisation"), !busy, history.fields["org"].orEmpty(), historyLabel)
    ConfigField(activity, { activity = it; destinationChanged() }, t("Activity bucket", "Bucket activité"), !busy, history.fields["activity"].orEmpty(), historyLabel)
    ConfigField(wearable, { wearable = it; destinationChanged() }, t("Wearable bucket", "Bucket montre"), !busy, history.fields["wearable"].orEmpty(), historyLabel)
    OutlinedTextField(token, { token = it }, label = { Text("API Token") }, singleLine = true,
        visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
        enabled = !busy,
        trailingIcon = {
            IconButton(enabled = !busy, onClick = { tokenVisible = !tokenVisible }) {
                Icon(if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (tokenVisible) t("Hide token", "Masquer le jeton") else t("Show token", "Afficher le jeton"))
            }
        },
        supportingText = { Text(if (canKeepToken) t("Leave blank to keep saved token", "Laisser vide pour conserver le jeton")
            else if (token.isNotBlank() && token == defaultToken(url, org)) t("Default write token (masked)", "Jeton d’écriture par défaut (masqué)")
            else t("Write token required for this destination", "Jeton d’écriture requis pour cette destination")) },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && validServerUrl(url.trim()) &&
        org.isNotBlank() && activity.isNotBlank() && wearable.isNotBlank() && activity.trim() != wearable.trim() && (canKeepToken || token.isNotBlank()),
        onClick = {
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { prefs.saveServer(target.url, target.org, target.activity, target.wearable, token.trim()) }
                if (ok) {
                    custom = true; savedToken = true; savedTarget = target; token = ""; tokenVisible = false
                    history = withContext(Dispatchers.IO) { prefs.serverHistory() }
                }
                notice = if (ok) ConfigurationSync.message(ConfigurationSync.synchronize(context), language == AppLanguage.FRENCH)
                    else t("Could not save. Re-enter the token.", "Enregistrement impossible. Ressaisissez le jeton.")
                busy = false
            }
        }) { Text(t("Save configuration", "Enregistrer la configuration")) }
    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && custom, onClick = {
        busy = true
        scope.launch {
            try {
                val confirmation = ConfigurationSync.synchronize(context)
                if (confirmation != ConfigSyncResult.CONFIRMED) {
                    notice = ConfigurationSync.message(confirmation, language == AppLanguage.FRENCH)
                } else {
                    val report = runDiagnostics(context)
                    val probe = report.detail?.optJSONObject("probe")
                    notice = when {
                        report.code != "OK" -> t("Connection test unavailable. Check Diagnostics.", "Test indisponible. Vérifiez Diagnostics.")
                        report.detail?.optBoolean("busy") == true -> t("Stop recording and wait for pending writes.", "Arrêtez la collecte et attendez les écritures en attente.")
                        probe == null -> t("Connection test unavailable.", "Test de connexion indisponible.")
                        probe.optInt("activity") in 200..299 && probe.optInt("wearable") in 200..299 &&
                            probe.optString("error").isBlank() -> t("Connection verified for both buckets.", "Connexion vérifiée pour les deux buckets.")
                        else -> t("Connection test failed. Check Diagnostics.", "Échec du test. Vérifiez Diagnostics.")
                    }
                }
            } finally { busy = false }
        }
    }) { Text(t("Test saved connection", "Tester la connexion enregistrée")) }
    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && custom, onClick = {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { prefs.resetServer() }
            if (ok) { url = ""; org = ""; activity = "activity"; wearable = "wearable"; token = ""; tokenVisible = false; savedToken = false; custom = false }
            notice = if (ok) t("Server settings cleared. ", "Paramètres du serveur effacés. ") +
                ConfigurationSync.message(ConfigurationSync.synchronize(context), language == AppLanguage.FRENCH)
                else t("Reset failed", "Échec de la réinitialisation")
            busy = false
        }
    }) { Text(t("Clear server configuration", "Effacer la configuration du serveur")) }
    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { confirmHistoryClear = true }) {
        Text(t("Clear InfluxDB history", "Effacer l’historique InfluxDB"))
    }
    if (confirmHistoryClear) AlertDialog(onDismissRequest = { confirmHistoryClear = false },
        title = { Text(t("Clear InfluxDB history?", "Effacer l’historique InfluxDB ?")) },
        text = { Text(t("Previous values will be removed from this phone. The active configuration is kept.",
            "Les anciennes valeurs seront effacées de ce téléphone. La configuration active est conservée.")) },
        confirmButton = { TextButton(onClick = {
            confirmHistoryClear = false; busy = true
            scope.launch {
                try {
                    val ok = withContext(Dispatchers.IO) { prefs.clearServerHistory() }
                    if (ok) { history = ServerHistorySuggestions(); tokenVisible = false }
                    notice = if (ok) t("History cleared.", "Historique effacé.") else t("Could not clear history.", "Impossible d’effacer l’historique.")
                } finally { busy = false }
            }
        }) { Text(t("Clear", "Effacer")) } },
        dismissButton = { TextButton(onClick = { confirmHistoryClear = false }) { Text(t("Cancel", "Annuler")) } })
    if (notice.isNotEmpty()) Text(notice)
}

@Composable
internal fun SensorSettings(language: String) {
    val context = LocalContext.current
    val prefs = remember(context) { CollectionPreferences(context) }
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var selected by remember { mutableStateOf(prefs.sensors) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val names = listOf(t("Accelerometer","Accéléromètre"), t("Gyroscope","Gyroscope"), t("Gravity","Gravité"),
        t("Linear acceleration","Accélération linéaire"), t("Rotation vector","Vecteur de rotation"),
        t("Heart rate","Fréquence cardiaque"), t("Steps","Nombre de pas"), t("Light","Luminosité"),
        t("Pressure","Pression"), t("Subjective fatigue","Fatigue subjective"), t("Magnetometer","Magnétomètre"))
    Text(t("Choose signals for the next session. Inertial sampling: ${prefs.hz} Hz.",
        "Choisissez les signaux de la prochaine session. Fréquence inertielle : ${prefs.hz} Hz."))
    SENSOR_KEYS.forEachIndexed { index, key ->
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(names[index], style = MaterialTheme.typography.bodyLarge)
                    Text(if (index < 5 || key == "magnetometer") "${prefs.hz} Hz" else if (key == "fatigue") t("Manual","Manuel") else t("Sensor events","Événements du capteur"),
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = key in selected, enabled = !busy, onCheckedChange = {
                    selected = if (it) selected + key else selected - key
                    notice = ""
                })
            }
        }
    }
    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && validSensorSelection(selected), onClick = {
        busy = true
        val snapshot = selected
        scope.launch {
            val ok = withContext(Dispatchers.IO) { prefs.saveSensors(snapshot) }
            notice = if (ok) ConfigurationSync.message(ConfigurationSync.synchronize(context), language == AppLanguage.FRENCH) else t("Save failed","Échec de l’enregistrement")
            busy = false
        }
    }) { Text(t("Save sensors","Enregistrer les capteurs")) }
    TextButton(enabled = !busy, onClick = { selected = SENSOR_KEYS.toSet(); notice = "" }) { Text(t("Enable all","Tout activer")) }
    if (notice.isNotEmpty()) Text(notice)
}
