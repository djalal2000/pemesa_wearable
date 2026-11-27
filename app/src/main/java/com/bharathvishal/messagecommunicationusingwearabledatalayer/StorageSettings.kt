package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Delete temporary files only, without following symbolic links. */
private fun clearCacheDirectory(directory: File): Boolean = runCatching {
    val root = directory.canonicalFile
    if (!root.exists()) return true
    fun remove(file: File): Boolean {
        val canonical = file.canonicalFile
        if (canonical != File(file.parentFile!!.canonicalFile, file.name)) return file.delete()
        if (!canonical.path.startsWith(root.path + File.separator)) return false
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            val cleared = children.map { remove(it) }.all { it }
            if (!cleared) return false
        }
        return file.delete() || !file.exists()
    }
    val files = root.listFiles() ?: return false
    files.map { remove(it) }.all { it }
}.getOrDefault(false)

private fun clearPhoneCache(context: Context): Boolean =
    (listOf(context.cacheDir) + context.externalCacheDirs.filterNotNull())
        .distinct().map { clearCacheDirectory(it) }.all { it }

@Composable
internal fun StorageSettings(language: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun t(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    var action by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    Text(t("Manage local history and temporary files on this phone. Active settings, watch association and measurement data are not removed.",
        "Gérez l’historique local et les fichiers temporaires de ce téléphone. Les réglages actifs, l’association de la montre et les mesures ne sont pas supprimés."))
    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { action = "history" }) {
        Text(t("Clear InfluxDB history", "Effacer l’historique InfluxDB"))
    }
    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { action = "cache" }) {
        Text(t("Clear application cache", "Vider le cache de l’application"))
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (notice.isNotBlank()) Text(notice)
    action?.let { selected ->
        val history = selected == "history"
        AlertDialog(onDismissRequest = { action = null },
            title = { Text(if (history) t("Clear InfluxDB history?", "Effacer l’historique InfluxDB ?") else t("Clear phone cache?", "Vider le cache du téléphone ?")) },
            text = { Text(if (history) t("Remove previous values. Keep the active configuration.", "Supprimer les anciennes valeurs. Conserver la configuration active.")
                else t("Remove temporary cache files only. Recording buffers and the technical journal are not affected.", "Supprimer uniquement les fichiers temporaires. Les tampons de collecte et le journal technique ne sont pas affectés.")) },
            confirmButton = { TextButton(onClick = {
                action = null; busy = true
                scope.launch {
                    try {
                        val ok = withContext(Dispatchers.IO) {
                            if (history) CollectionPreferences(context).clearServerHistory() else clearPhoneCache(context)
                        }
                        notice = if (ok) {
                            if (history) t("History cleared.", "Historique effacé.") else t("Cache cleared.", "Cache vidé.")
                        } else t("Could not complete cleanup.", "Nettoyage incomplet.")
                    } finally { busy = false }
                }
            }) { Text(t("Clear", "Effacer")) } },
            dismissButton = { TextButton(onClick = { action = null }) { Text(t("Cancel", "Annuler")) } })
    }
}
