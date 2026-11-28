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
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
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
internal fun ServerSettings(language: String, onDiagnostics: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember(context) { CollectionPreferences(context) }
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var url by remember { mutableStateOf(if (prefs.custom) prefs.url else BuildConfig.PEMESA_INFLUX_URL) }
    var org by remember { mutableStateOf(if (prefs.custom) prefs.organization else BuildConfig.PEMESA_INFLUX_ORG) }
    var activity by remember { mutableStateOf(prefs.activity) }
    var wearable by remember { mutableStateOf(prefs.wearable) }
    var hz by remember { mutableStateOf(prefs.hz) }
    var interval by remember { mutableStateOf(prefs.interval) }
    var sensors by remember { mutableStateOf(prefs.sensors) }
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
    var confirmReset by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(prefs.custom) }
    var editing by remember { mutableStateOf(!prefs.serverConfigured) }
    var busy by remember { mutableStateOf(true) }
    var current by remember { mutableStateOf<PhoneConfigurationView?>(null) }
    var notice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val target = ServerTarget(url.trim(), org.trim(), activity.trim(), wearable.trim())
    val canKeepToken = savedToken && target == savedTarget
    val validDraft = validServerUrl(target.url) && target.org.isNotBlank() &&
        target.activity.isNotBlank() && target.wearable.isNotBlank() &&
        target.activity != target.wearable && (canKeepToken || token.isNotBlank()) &&
        validAcquisition(hz, interval) && validSensorSelection(sensors)
    val confirmed = !busy && !editing && custom && current?.result == "CONFIRMED"
    fun reloadDraft() {
        custom = prefs.custom
        savedToken = prefs.hasToken
        savedTarget = ServerTarget(prefs.url, prefs.organization, prefs.activity, prefs.wearable)
        url = if (custom) prefs.url else BuildConfig.PEMESA_INFLUX_URL
        org = if (custom) prefs.organization else BuildConfig.PEMESA_INFLUX_ORG
        activity = prefs.activity; wearable = prefs.wearable
        hz = prefs.hz; interval = prefs.interval; sensors = prefs.sensors
        token = if (savedToken) "" else defaultToken(url, org)
        tokenVisible = false
    }
    fun destinationChanged() {
        tokenVisible = false
        val destination = ServerTarget(url.trim(), org.trim(), activity.trim(), wearable.trim())
        token = if (savedToken && destination == savedTarget) "" else defaultToken(url, org)
    }
    suspend fun refreshConfirmation() {
        current = try { ConfigurationSync.inspectLocal(context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    LaunchedEffect(Unit) {
        try {
            history = withContext(Dispatchers.IO) { prefs.serverHistory() }
            refreshConfirmation()
        } finally { busy = false }
    }
    val status = when {
        busy -> t("Checking / synchronizing…", "Vérification / synchronisation…")
        editing -> t("Configuration to save", "Configuration à enregistrer")
        !custom -> t("Server not configured", "Serveur non configuré")
        confirmed -> t("Configuration confirmed by the watch", "Configuration confirmée par la montre")
        current?.result == "OFFLINE" -> t("Waiting for the watch", "En attente de la montre")
        current?.result == "NO_WATCH" -> t("No selected watch", "Aucune montre sélectionnée")
        current?.result == "BUSY" -> t("Watch busy — retry after recording", "Montre occupée — réessayez après la collecte")
        else -> t("Configuration not confirmed", "Configuration non confirmée")
    }
    Text(t("Watch configuration", "Configuration de la montre"), style = MaterialTheme.typography.titleLarge)
    Surface(shape = RoundedCornerShape(18.dp),
        color = if (confirmed) Color(0xFFD6EDA5) else Color(0xFFFFE4C2),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(current?.watchName?.takeIf { it.isNotBlank() } ?: t("Selected watch", "Montre sélectionnée"),
                style = MaterialTheme.typography.titleMedium, color = Color(0xFF1E3935))
            Text(status, color = Color(0xFF1E3935))
            if (confirmed) {
                current?.let { value ->
                    if (value.checkedAt > 0) Text(t("Checked at: ", "Vérifié à : ") +
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(value.checkedAt)),
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF1E3935))
                }
            }
        }
    }
    val names = listOf(t("Accelerometer","Accéléromètre"), t("Gyroscope","Gyroscope"), t("Gravity","Gravité"),
        t("Linear acceleration","Accélération linéaire"), t("Rotation vector","Vecteur de rotation"),
        t("Heart rate","Fréquence cardiaque"), t("Steps","Nombre de pas"), t("Light","Luminosité"),
        t("Pressure","Pression"), t("Subjective fatigue","Fatigue subjective"), t("Magnetometer","Magnétomètre"))
    val historyLabel = t("Previous values", "Valeurs précédentes")
    Text("InfluxDB", style = MaterialTheme.typography.titleMedium)
    if (editing) {
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
    } else {
        ConfigSummaryLine(t("Server URL", "URL du serveur"), url)
        ConfigSummaryLine(t("Organization", "Organisation"), org)
        ConfigSummaryLine(t("Activity bucket", "Bucket activité"), activity)
        ConfigSummaryLine(t("Wearable bucket", "Bucket montre"), wearable)
        ConfigSummaryLine("API Token", if (savedToken) t("Configured (hidden)", "Configuré (masqué)") else t("Not configured", "Non configuré"))
    }
    Text(t("Acquisition", "Acquisition"), style = MaterialTheme.typography.titleMedium)
    if (editing) {
        Text(t("Sampling frequency", "Fréquence d’échantillonnage"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(25, 50, 100).forEach { value ->
                FilterChip(selected = hz == value, onClick = { hz = value }, label = { Text("$value Hz") }, enabled = !busy)
            }
        }
        Text(t("Transmission interval", "Intervalle de transmission"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 20).forEach { value ->
                FilterChip(selected = interval == value, onClick = { interval = value }, label = { Text("$value s") }, enabled = !busy)
            }
        }
    } else {
        ConfigSummaryLine(t("Sampling frequency", "Fréquence d’échantillonnage"), "$hz Hz")
        ConfigSummaryLine(t("Transmission interval", "Intervalle de transmission"), "$interval s")
    }
    ConfigSummaryLine(t("Samples per batch", "Échantillons par lot"), (hz * interval).toString())
    Text(t("Selected signals", "Signaux sélectionnés"), style = MaterialTheme.typography.titleMedium)
    if (editing) {
        SENSOR_KEYS.forEachIndexed { index, key ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(names[index], modifier = Modifier.weight(1f))
                Switch(checked = key in sensors, enabled = !busy, onCheckedChange = {
                    sensors = if (it) sensors + key else sensors - key
                })
            }
        }
        TextButton(enabled = !busy, onClick = { sensors = SENSOR_KEYS.toSet() }) { Text(t("Enable all", "Tout activer")) }
    } else {
        Text(SENSOR_KEYS.mapIndexedNotNull { index, key -> names[index].takeIf { key in sensors } }.joinToString(" · "))
    }

    val buttonColor = if (confirmed) Color(0xFF287A42) else Color(0xFFC66A16)
    Button(modifier = Modifier.fillMaxWidth(),
        enabled = !busy && (if (editing) validDraft else custom && savedToken && !confirmed),
        colors = ButtonDefaults.buttonColors(containerColor = buttonColor, contentColor = Color.White,
            disabledContainerColor = buttonColor, disabledContentColor = Color.White),
        onClick = {
            val saveDraft = editing
            val draftToken = token.trim()
            val draftHz = hz; val draftInterval = interval; val draftSensors = sensors.toSet()
            busy = true
            current = null
            notice = ""
            scope.launch {
                try {
                    val ok = !saveDraft || withContext(Dispatchers.IO) {
                        prefs.saveServer(target.url, target.org, target.activity, target.wearable,
                            draftToken, draftHz, draftInterval, draftSensors)
                    }
                    if (!ok) {
                        notice = t("Could not save. Check settings and re-enter the token.", "Enregistrement impossible. Vérifiez les réglages et ressaisissez le jeton.")
                    } else {
                        reloadDraft()
                        editing = false
                        history = withContext(Dispatchers.IO) { prefs.serverHistory() }
                        val result = ConfigurationSync.synchronize(context)
                        notice = ConfigurationSync.message(result, language == AppLanguage.FRENCH)
                        refreshConfirmation()
                        if (result == ConfigSyncResult.CONFIRMED && current?.result != "CONFIRMED") {
                            notice = t("Saved on phone. Confirmation could not be rechecked; retry.", "Enregistré sur le téléphone. Confirmation non revérifiée ; réessayez.")
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notice = t("Operation failed. Retry.", "Échec de l’opération. Réessayez.") }
                finally { busy = false }
            }
        }) {
        Text(when {
            busy -> t("In progress…", "En cours…")
            confirmed -> t("Saved and confirmed", "Enregistrée et confirmée")
            editing -> t("Save and confirm on watch", "Enregistrer et confirmer sur la montre")
            else -> t("Retry watch confirmation", "Réessayer la confirmation de la montre")
        })
    }
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    if (!editing) {
        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
            reloadDraft(); editing = true; notice = ""
        }) { Text(t("Edit configuration", "Modifier la configuration")) }
    } else if (custom) {
        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
            reloadDraft(); editing = false; notice = ""
        }) { Text(t("Cancel changes", "Annuler les modifications")) }
    }
    if (notice.isNotBlank()) Text(notice)
    Text(t("Confirmation checks the saved settings, not the InfluxDB connection or sensor availability. Changes apply at the next Start.",
        "La confirmation vérifie les réglages enregistrés, pas la connexion InfluxDB ni la disponibilité des capteurs. Les changements s’appliquent au prochain Start."),
        style = MaterialTheme.typography.bodySmall)
    if (!editing) {
        TextButton(enabled = !busy, onClick = onDiagnostics) {
            Text(t("Test InfluxDB connection in Diagnostics", "Tester la connexion InfluxDB dans Diagnostics"))
        }
    } else {
        TextButton(enabled = !busy && custom, onClick = { confirmReset = true }) {
            Text(t("Clear server configuration", "Effacer la configuration du serveur"))
        }
        TextButton(enabled = !busy, onClick = { confirmHistoryClear = true }) {
            Text(t("Clear InfluxDB history", "Effacer l’historique InfluxDB"))
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text(t("Clear server configuration?", "Effacer la configuration du serveur ?")) },
        text = { Text(t("The saved server and token will be removed.", "Le serveur et le jeton enregistrés seront supprimés.")) },
        confirmButton = { TextButton(onClick = {
            confirmReset = false; busy = true; current = null
            scope.launch {
                try {
                    val ok = withContext(Dispatchers.IO) { prefs.resetServer() }
                    if (ok) {
                        reloadDraft()
                        notice = t("Server settings cleared. ", "Paramètres du serveur effacés. ") +
                            ConfigurationSync.message(ConfigurationSync.synchronize(context), language == AppLanguage.FRENCH)
                        refreshConfirmation()
                    } else notice = t("Reset failed", "Échec de la réinitialisation")
                } finally { busy = false }
            }
        }) { Text(t("Clear", "Effacer")) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(t("Cancel", "Annuler")) } })
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
}

@Composable
private fun ConfigSummaryLine(label: String, value: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
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
