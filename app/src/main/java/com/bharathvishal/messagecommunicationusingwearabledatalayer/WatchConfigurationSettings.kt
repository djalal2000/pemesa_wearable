package com.bharathvishal.messagecommunicationusingwearabledatalayer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class PhoneConfigurationView(
    val result: String = "UNVERIFIED", val watchName: String = "", val version: String = "",
    val hz: Int = 0, val interval: Int = 0, val sensors: Set<String> = emptySet(),
    val custom: Boolean = false, val url: String = "", val org: String = "",
    val activity: String = "", val wearable: String = "", val tokenConfigured: Boolean = false,
    val checkedAt: Long = 0L
)

@Composable
internal fun WatchConfigurationSettings(language: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var current by remember { mutableStateOf<PhoneConfigurationView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    suspend fun refresh() {
        busy = true
        failed = false
        try { current = ConfigurationSync.inspectLocal(context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { current = null; failed = true }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { refresh() }
    Text(t("Saved phone settings. The watch confirms the revision and fingerprint; no settings are downloaded from it.",
        "Réglages enregistrés sur le téléphone. La montre confirme la version et l’empreinte ; aucun réglage n’est téléchargé depuis la montre."))
    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { scope.launch { refresh() } }) {
        Text(t("Check watch confirmation", "Vérifier la confirmation de la montre"))
    }
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    if (failed) Text(t("Could not read settings.", "Impossible de lire les réglages."))
    val value = current
    if (value != null) {
        val confirmed = !busy && value.result == "CONFIRMED"
        Surface(shape = RoundedCornerShape(18.dp), color = if (confirmed) Color(0xFFD6EDA5) else MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value.watchName.ifBlank { t("No selected watch", "Aucune montre sélectionnée") }, style = MaterialTheme.typography.titleLarge)
                Text(if (busy) t("Checking...", "Vérification…") else when (value.result) {
                    "CONFIRMED" -> t("Configuration confirmed by the watch", "Configuration confirmée par la montre")
                    "DIFFERENT" -> t("Not synchronized — watch has different or missing settings", "Non synchronisée — réglages différents ou absents sur la montre")
                    "OFFLINE" -> t("Not confirmed — watch did not reply", "Non confirmée — la montre ne répond pas")
                    "NO_WATCH" -> t("Not confirmed — select an associated watch", "Non confirmée — sélectionnez une montre associée")
                    "BUSY" -> t("Not confirmed — watch busy", "Non confirmée — montre occupée")
                    "CHANGED" -> t("Settings or watch changed — check again", "Réglages ou montre modifiés — vérifiez à nouveau")
                    else -> t("Configuration not confirmed", "Configuration non confirmée")
                }, style = MaterialTheme.typography.titleMedium)
                if (value.version.isNotBlank()) Text(t("Version: ", "Version : ") + value.version, style = MaterialTheme.typography.bodySmall)
                if (value.checkedAt > 0 && !busy)
                    Text(t("Checked at: ", "Vérifié à : ") + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(value.checkedAt)),
                        style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(t("Acquisition", "Acquisition"), style = MaterialTheme.typography.titleMedium)
        PhoneConfigLine(t("Sampling frequency", "Fréquence d’échantillonnage"), "${value.hz} Hz")
        PhoneConfigLine(t("Transmission interval", "Intervalle de transmission"), "${value.interval} s")
        PhoneConfigLine(t("Samples per batch", "Échantillons par lot"), "${value.hz * value.interval}")
        Text("InfluxDB", style = MaterialTheme.typography.titleMedium)
        PhoneConfigLine(t("Configuration", "Configuration"), if (value.custom) t("Custom", "Personnalisée") else t("Not configured", "Non configurée"))
        if (value.custom) {
            PhoneConfigLine(t("Server URL", "URL du serveur"), value.url)
            PhoneConfigLine(t("Organization", "Organisation"), value.org)
        }
        PhoneConfigLine(t("Activity bucket", "Bucket activité"), value.activity)
        PhoneConfigLine(t("Wearable bucket", "Bucket montre"), value.wearable)
        PhoneConfigLine("API Token", if (value.tokenConfigured) t("Configured (hidden)", "Configuré (masqué)") else t("Not configured", "Non configuré"))
        Text(t("Selected signals", "Signaux sélectionnés"), style = MaterialTheme.typography.titleMedium)
        val names = listOf(t("Accelerometer","Accéléromètre"), t("Gyroscope","Gyroscope"), t("Gravity","Gravité"),
            t("Linear acceleration","Accélération linéaire"), t("Rotation vector","Vecteur de rotation"),
            t("Heart rate","Fréquence cardiaque"), t("Steps","Nombre de pas"), t("Light","Luminosité"),
            t("Pressure","Pression"), t("Subjective fatigue","Fatigue subjective"), t("Magnetometer","Magnétomètre"))
        SENSOR_KEYS.forEachIndexed { index, key ->
            PhoneConfigLine(names[index], if (key in value.sensors) t("Enabled", "Activé") else t("Disabled", "Désactivé"))
        }
        Text(t("Confirmation verifies saved settings, not hardware availability or server connectivity. Changes apply at the next Start.",
            "La confirmation vérifie les réglages enregistrés, pas la disponibilité des capteurs ni du serveur. Les changements s’appliquent au prochain Start."),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PhoneConfigLine(label: String, value: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
