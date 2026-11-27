package com.bharathvishal.messagecommunicationusingwearabledatalayer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AdvancedSettings(page: String, language: String) {
    val context = LocalContext.current
    val prefs = remember(context) { CollectionPreferences(context) }
    val scope = rememberCoroutineScope()
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun save(action: () -> Boolean) {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching(action).getOrDefault(false) }
            notice = if (ok && page == "acquisition") ConfigurationSync.message(ConfigurationSync.synchronize(context), language == AppLanguage.FRENCH)
            else if (ok) t("Saved", "Enregistré") else t("Operation failed. Check the settings.", "Échec. Vérifiez les paramètres.")
            busy = false
        }
    }
    when (page) {
        "acquisition" -> {
            var hz by remember { mutableStateOf(prefs.hz) }
            var interval by remember { mutableStateOf(prefs.interval) }
            Text(t("Save synchronizes the watch. Changes apply at the next Start. Defaults: 50 Hz and 10 seconds.",
                "Save synchronise la montre. Les changements s’appliquent au prochain Start. Valeurs d’origine : 50 Hz et 10 secondes."))
            Text(t("Sampling frequency", "Fréquence d’échantillonnage"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 50, 100).forEach { value ->
                    FilterChip(selected = hz == value, onClick = { hz = value }, label = { Text("$value Hz") }, enabled = !busy)
                }
            }
            Text(t("Transmission interval", "Intervalle de transmission"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 20).forEach { value ->
                    FilterChip(selected = interval == value, onClick = { interval = value }, label = { Text("$value s") }, enabled = !busy)
                }
            }
            Text(t("Samples per batch: ", "Échantillons par lot : ") + (hz * interval))
            Text(t("Target frequency, subject to hardware and Android scheduling.", "Fréquence cible, selon le matériel et Android."))
            Button(enabled = !busy && validAcquisition(hz, interval), modifier = Modifier.fillMaxWidth(), onClick = {
                val f = hz; val i = interval; save { prefs.saveAcquisition(f, i) }
            }) { Text(t("Save", "Enregistrer")) }
            TextButton(enabled = !busy, onClick = { hz = 50; interval = 10 }) { Text(t("Restore defaults", "Valeurs par défaut")) }
        }
        "alerts" -> {
            var vibration by remember { mutableStateOf(prefs.vibration) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Vibration feedback", "Retour vibratoire"))
                    Text(t("Connection loss and write failures while the recording screen is open.", "Perte de connexion et échec d’écriture lorsque l’écran de collecte est ouvert."), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = vibration, enabled = !busy, onCheckedChange = { vibration = it; save { prefs.saveVibration(it) } })
            }
            Text(t("Visual warnings always remain active. Vibration follows Android system settings.",
                "Les avertissements visuels restent toujours actifs. Les vibrations suivent les réglages Android."))
        }
    }
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    if (notice.isNotBlank()) Text(notice)
}

