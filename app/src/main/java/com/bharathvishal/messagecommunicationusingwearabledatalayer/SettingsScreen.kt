package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val SettingsBackground = Color(0xFFF1F6F7)
private val SettingsInk = Color(0xFF1E3935)
private val SettingsPistachio = Color(0xFFD6EDA5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(language: String, onBack: () -> Unit, onLanguageClick: () -> Unit, initialPage: String? = null) {
    val context = LocalContext.current
    val namePrefs = remember(context) {
        context.getSharedPreferences(PROFESSION_NAMES_PREFERENCES, Context.MODE_PRIVATE)
    }
    val activityPrefs = remember(context) {
        context.getSharedPreferences(ActivityLabels.PREFERENCES, Context.MODE_PRIVATE)
    }
    val professions = ActivityLabels.professions
    val names = remember(namePrefs) {
        mutableStateMapOf<String, String>().apply {
            professions.forEach { option ->
                val code = option.substringBefore(" - ")
                namePrefs.getString(code, null)?.let { put(code, it) }
            }
        }
    }
    val labels = remember(activityPrefs) {
        mutableStateMapOf<String, String>().apply {
            professions.forEach { option ->
                val code = option.substringBefore(" - ")
                ActivityLabels.defaults(code).indices.forEach { index ->
                    val key = ActivityLabels.key(code, index)
                    activityPrefs.getString(key, null)?.let { put(key, it) }
                }
            }
        }
    }

    fun tr(text: String) = AppLanguage.translate(language, text)
    fun ui(en: String, fr: String) = if (language == AppLanguage.FRENCH) fr else en
    fun name(code: String): String = names[code] ?: tr(professions.first { it.substringBefore(" - ") == code })
    fun label(code: String, index: Int): String =
        labels[ActivityLabels.key(code, index)] ?: tr(ActivityLabels.defaults(code)[index])

    var selectedCode by rememberSaveable { mutableStateOf(initialPage) }
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var resetTarget by rememberSaveable { mutableStateOf<String?>(null) }

    fun goBack() {
        val current = selectedCode
        editingIndex = null
        resetTarget = null
        when {
            current == null -> onBack()
            professions.any { it.substringBefore(" - ") == current } -> selectedCode = "professions"
            else -> selectedCode = null
        }
    }
    BackHandler(onBack = ::goBack)
    // Old saved navigation state may point to a page removed in this version.
    val code = selectedCode?.takeUnless { it in setOf("reliability", "study", "backup") }
    LaunchedEffect(selectedCode) {
        if (selectedCode != code) selectedCode = code
    }
    val title = when (code) {
        null -> tr("Settings")
        "watches" -> ui("Smartwatch management", "Gestion des montres")
        "watch_config" -> ui("Watch configuration", "Configuration de la montre")
        "server" -> "InfluxDB"
        "sensors" -> ui("Sensors", "Capteurs")
        "professions" -> ui("Professions & activities", "Métiers et activités")
        "acquisition" -> ui("Acquisition", "Acquisition")
        "alerts" -> ui("Notifications", "Notifications")
        "diagnostics" -> "Diagnostics"
        "storage" -> ui("History & cache", "Historique et cache")
        else -> name(code)
    }

    Scaffold(
        containerColor = SettingsBackground,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 2, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = ::goBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = ui("Back", "Retour"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SettingsBackground)
            )
        }
    ) { innerPadding ->
        key(code) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (code == null) {
                    Surface(color = SettingsInk, shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("PEMESA", color = SettingsPistachio, style = MaterialTheme.typography.labelLarge)
                            Text(ui("Personalize your workspace", "Personnalisez votre espace"),
                                color = Color.White, style = MaterialTheme.typography.titleLarge)
                            Text(ui("Manage profession names, activity labels and language.",
                                "Gérez les noms des métiers, les étiquettes d’activité et la langue."),
                                color = Color(0xFFD8E7E4), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    SectionHeading(ui("Preferences", "Préférences"))
                    SettingsRow(
                        title = tr("Select Language"), subtitle = AppLanguage.languageName(language),
                        icon = Icons.Filled.Language, onClick = onLanguageClick
                    )
                    SettingsRow(title = ui("Smartwatch management", "Gestion des montres"), subtitle = ui("Pair and select a research watch", "Associer et sélectionner une montre"),
                        icon = Icons.Filled.Watch, onClick = { selectedCode = "watches" })
                    SectionHeading(ui("Data collection", "Collecte des données"))
                    SettingsRow(title = "InfluxDB", subtitle = ui("Server, organization, buckets and API token", "Serveur, organisation, buckets et jeton API"),
                        icon = Icons.Filled.Storage, onClick = { selectedCode = "server" })
                    SettingsRow(title = ui("Sensors", "Capteurs"), subtitle = ui("Choose signals to collect", "Choisir les signaux à collecter"),
                        icon = Icons.Filled.Tune, onClick = { selectedCode = "sensors" })
                    SettingsRow(title = ui("Acquisition", "Acquisition"), subtitle = ui("Sampling and transmission", "Fréquence et transmission"),
                        icon = Icons.Filled.Tune, onClick = { selectedCode = "acquisition" })
                    SettingsRow(title = ui("Watch configuration", "Configuration de la montre"), subtitle = ui("Saved settings and watch confirmation", "Réglages enregistrés et confirmation de la montre"),
                        icon = Icons.Filled.Watch, onClick = { selectedCode = "watch_config" })
                    SectionHeading(ui("Customization", "Personnalisation"))
                    SettingsRow(
                        title = ui("Professions & activities", "Métiers et activités"),
                        subtitle = ui("3 professions · Manage names and activity labels", "3 métiers · Gérer les noms et les étiquettes"),
                        icon = Icons.Filled.Folder, onClick = { selectedCode = "professions" }
                    )
                    SectionHeading(ui("System", "Système"))
                    SettingsRow(title = ui("Notifications", "Notifications"), subtitle = ui("Optional vibration alerts", "Alertes vibratoires facultatives"),
                        icon = Icons.Filled.Notifications, onClick = { selectedCode = "alerts" })
                    SettingsRow(title = "Diagnostics", subtitle = ui("Test and troubleshoot", "Tester et résoudre les problèmes"),
                        icon = Icons.Filled.Build, onClick = { selectedCode = "diagnostics" })
                    SettingsRow(title = ui("History & cache", "Historique et cache"), subtitle = ui("Clear saved suggestions or temporary files", "Effacer les suggestions ou fichiers temporaires"),
                        icon = Icons.Filled.Storage, onClick = { selectedCode = "storage" })
                } else if (code == "professions") {
                    SectionHeading(ui("Choose a profession", "Choisir un métier"))
                    professions.forEach { option ->
                        val itemCode = option.substringBefore(" - ")
                        SettingsRow(
                            title = name(itemCode),
                            subtitle = "$itemCode · " + ui("7 activity labels", "7 étiquettes d’activité"),
                            code = itemCode,
                            onClick = { selectedCode = itemCode }
                        )
                    }
                    Text(ui("Profession codes stay unchanged when you rename them.",
                        "Les codes des métiers restent inchangés lorsque vous les renommez."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (code == "watches") {
                    SmartwatchSettings(language)
                } else if (code == "watch_config") {
                    WatchConfigurationSettings(language)
                } else if (code == "server") {
                    ServerSettings(language)
                } else if (code == "sensors") {
                    SensorSettings(language)
                } else if (code == "storage") {
                    StorageSettings(language)
                } else if (code == "diagnostics") {
                    DiagnosticsSettings(language, onNavigate = { selectedCode = it })
                } else if (code in setOf("acquisition", "alerts")) {
                    AdvancedSettings(requireNotNull(code), language)
                } else {
                    SectionHeading(ui("Profession name", "Nom du métier"))
                    SettingsRow(title = name(code),
                        subtitle = ui("Internal code: $code", "Code interne : $code"), code = code,
                        trailing = Icons.Filled.Edit, onClick = { draft = name(code); editingIndex = -1 })
                    TextButton(onClick = { resetTarget = "name" }, enabled = names.containsKey(code)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(ui("Restore default name", "Rétablir le nom par défaut"))
                    }
                    SectionHeading(ui("Activity labels", "Étiquettes d’activité"))
                    ActivityLabels.defaults(code).indices.forEach { index ->
                        SettingsRow(
                            title = label(code, index),
                            subtitle = if (index == 6) ui("Unlabeled activity", "Activité non étiquetée")
                                else ui("Activity ${index + 1}", "Activité ${index + 1}"),
                            code = if (index == 6) "—" else (index + 1).toString(),
                            trailing = Icons.Filled.Edit,
                            onClick = { draft = label(code, index); editingIndex = index }
                        )
                    }
                    OutlinedButton(
                        onClick = { resetTarget = "labels" },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        enabled = ActivityLabels.defaults(code).indices.any {
                            labels.containsKey(ActivityLabels.key(code, it))
                        }
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Restore default labels"))
                    }
                    Text(ui("Changes apply to the next recording. Existing InfluxDB data is not modified.",
                        "Les changements s’appliquent au prochain enregistrement. Les données InfluxDB existantes ne sont pas modifiées."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    val index = editingIndex
    val isProfession = code != null && professions.any { it.substringBefore(" - ") == code }
    if (code != null && isProfession && index != null) {
        val otherNames = if (index == -1) {
            professions.map { it.substringBefore(" - ") }.filter { it != code }.map { name(it) }
        } else {
            ActivityLabels.defaults(code).indices.filter { it != index }.flatMap { other ->
                val custom = labels[ActivityLabels.key(code, other)]
                if (custom != null) listOf(custom)
                else listOf(ActivityLabels.defaults(code)[other], label(code, other))
            }
        }
        val error = if (index == -1) professionNameError(draft, otherNames) else activityNameError(draft, otherNames)
        val errorText = when (error) {
            SettingsNameError.EMPTY -> if (index == -1) ui("Enter a profession name", "Saisissez un nom de métier") else tr("Enter a label")
            SettingsNameError.DELIMITER -> tr("Do not use commas or colons")
            SettingsNameError.DUPLICATE -> if (index == -1) ui("This profession name already exists", "Ce nom de métier existe déjà") else tr("This label already exists")
            null -> null
        }
        AlertDialog(
            onDismissRequest = { editingIndex = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text(if (index == -1) ui("Rename profession", "Renommer le métier") else tr("Rename label")) },
            text = {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    label = { Text(if (index == -1) ui("Profession name", "Nom du métier") else ui("Activity label", "Étiquette d’activité")) },
                    singleLine = true, isError = error != null,
                    supportingText = { errorText?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
                )
            },
            confirmButton = {
                Button(enabled = error == null, onClick = {
                    val value = draft.trim()
                    if (index == -1) {
                        if (value != name(code)) {
                            namePrefs.edit().putString(code, value).apply()
                            names[code] = value
                        }
                    } else if (value != label(code, index)) {
                        val key = ActivityLabels.key(code, index)
                        activityPrefs.edit().putString(key, value).apply()
                        labels[key] = value
                    }
                    editingIndex = null
                }) { Text(tr("Save")) }
            },
            dismissButton = { TextButton(onClick = { editingIndex = null }) { Text(tr("Cancel")) } }
        )
    }

    val target = resetTarget
    if (code != null && isProfession && target != null) {
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text(if (target == "name") ui("Restore default name", "Rétablir le nom par défaut") else tr("Restore default labels")) },
            text = { Text(if (target == "name")
                ui("Restore the original profession name?", "Rétablir le nom d’origine du métier ?")
                else tr("Restore the default activity labels for this profession?")) },
            confirmButton = {
                Button(onClick = {
                    if (target == "name") {
                        namePrefs.edit().remove(code).apply()
                        names.remove(code)
                    } else {
                        val editor = activityPrefs.edit()
                        ActivityLabels.defaults(code).indices.forEach { index ->
                            val key = ActivityLabels.key(code, index)
                            editor.remove(key)
                            labels.remove(key)
                        }
                        editor.apply()
                    }
                    resetTarget = null
                }) { Text(tr("Confirm")) }
            },
            dismissButton = { TextButton(onClick = { resetTarget = null }) { Text(tr("Cancel")) } }
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = SettingsInk, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingsRow(
    title: String, subtitle: String, code: String? = null, icon: ImageVector? = null,
    trailing: ImageVector = Icons.Filled.ChevronRight, onClick: () -> Unit
) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(46.dp).background(SettingsPistachio, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center) {
                if (code != null) Text(code, color = SettingsInk, style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold)
                else if (icon != null) Icon(icon, contentDescription = null, tint = SettingsInk)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = SettingsInk, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Icon(trailing, contentDescription = null, tint = SettingsInk)
        }
    }
}
