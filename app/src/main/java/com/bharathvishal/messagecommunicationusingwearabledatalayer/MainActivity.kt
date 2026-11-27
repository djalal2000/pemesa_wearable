package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var currentLanguage by mutableStateOf(AppLanguage.ENGLISH)
    private var configurationMonitor: kotlinx.coroutines.Job? = null

    override fun onResume() {
        super.onResume()
        configurationMonitor?.cancel()
        configurationMonitor = lifecycleScope.launch {
            while (true) {
                // Transfer configuration only when revisions differ.
                if (WatchRegistry(this@MainActivity).pinned == null)
                    ConfigurationSync.synchronize(this@MainActivity)
                kotlinx.coroutines.delay(15000L)
            }
        }
    }

    override fun onPause() {
        configurationMonitor?.cancel()
        configurationMonitor = null
        super.onPause()
    }

    private fun tr(text: String) = AppLanguage.translate(currentLanguage, text)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentLanguage = AppLanguage.get(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            // PEMESA_UI_REFRESH_V1
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF096B69),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFD7F2EE),
                    onPrimaryContainer = Color(0xFF064C4B),
                    secondary = Color(0xFF36566B),
                    background = Color(0xFFF1F6F7),
                    onBackground = Color(0xFF142D3A),
                    surface = Color.White,
                    onSurface = Color(0xFF142D3A),
                    surfaceVariant = Color(0xFFE8F0F2),
                    onSurfaceVariant = Color(0xFF405963),
                    outline = Color(0xFF718991)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showLangDialog by remember { mutableStateOf(false) }
                    var showSettings by rememberSaveable { mutableStateOf(!CollectionPreferences(this).serverConfigured) }
                    var settingsPage by rememberSaveable { mutableStateOf<String?>(if (CollectionPreferences(this).serverConfigured) null else "server") }
                    val homeState = rememberSaveableStateHolder()

                    if (showLangDialog) {
                        LanguageDialog(
                            currentLanguage = currentLanguage,
                            onDismiss = { showLangDialog = false },
                            onLanguageSelected = { lang ->
                                AppLanguage.set(this, lang)
                                currentLanguage = lang
                                Toast.makeText(this, tr("Selected language:") + " " + AppLanguage.languageName(lang), Toast.LENGTH_SHORT).show()
                                showLangDialog = false
                            }
                        )
                    }

                    if (showSettings) {
                        SettingsScreen(
                            language = currentLanguage,
                            onBack = { showSettings = false },
                            onLanguageClick = { showLangDialog = true }, initialPage = settingsPage
                        )
                    } else {
                        homeState.SaveableStateProvider("home") {
                            MainPage(
                                language = currentLanguage,
                                onMenuItemClick = { item ->
                                    when (item) {
                                        "Exit" -> finish()
                                        "Smartwatch" -> { settingsPage = "watches"; showSettings = true }
                                        "Settings" -> { settingsPage = null; showSettings = true }
                                        "About" -> showAboutDialog()
                                    }
                                }
                            )
                        }
                    }

                }
            }
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this@MainActivity)
            .setTitle(tr("About"))
            .setMessage(
                if (currentLanguage == AppLanguage.FRENCH) {
                    "Développé par Djalal Eddine Bouchekif, doctorant au laboratoire IRIT (Institut de recherche en informatique de Toulouse).\n\n" +
                        "Sous la direction de M. Rémi Bastide et de Mme Imen Magdiche.\n\n" +
                        "Ce travail s’inscrit dans le cadre de ma thèse de doctorat en cours à l’école d’ingénieurs ISIS, réalisée dans le cadre du projet PEMESA, cofinancé par la Région Occitanie en France.\n\n" +
                        "Contexte du projet PEMESA :\n" +
                        "Le projet vise à étudier la pénibilité au travail, notamment dans les professions de santé, en exploitant les technologies de l’Internet des objets (IoT), l’intelligence artificielle et l’analyse de données.\n\n" +
                        "Il repose sur la collecte de données inertielles (accéléromètre et gyroscope) pour identifier les activités physiques et sur le suivi de la fréquence cardiaque à l’aide de montres connectées, complétés par les perceptions subjectives des travailleurs recueillies par questionnaire.\n\n" +
                        "L’objectif principal est d’identifier les facteurs clés contribuant à la pénibilité au travail afin d’améliorer les conditions de travail dans le secteur médical."
                } else "Developed by Djalal Eddine Bouchekif, a PhD student at the IRIT Laboratory (Institute of Computer Science Research of Toulouse).\n\n" +
                        "Supervised by Mr. Rémi Bastide and Ms. Imen Magdiche.\n\n" +
                        "This work is part of my ongoing doctoral thesis at the ISIS Engineering School, conducted within the framework of the PEMESA project, co-funded by the Occitanie Region in France.\n\n" +
                        "Context of the PEMESA Project:\n" +
                        "The project aims to study work-related hardship, particularly in healthcare professions, by leveraging Internet of Things (IoT) technologies, artificial intelligence, and data analysis.\n\n" +
                        "It involves collecting inertial data (accelerometer and gyroscope) to identify physical activities and monitoring heart rate through connected watches, complemented by workers’ subjective perceptions collected via questionnaires.\n\n" +
                        "The primary goal is to identify key factors contributing to work-related hardship in order to improve working conditions in the medical sector."
            )
            .setPositiveButton("OK", null)
            .show()
    }


    private fun showConnectedSmartwatches() {
        lifecycleScope.launch {
            val nodeList = withContext(Dispatchers.IO) {
                try {
                    Tasks.await(Wearable.getNodeClient(this@MainActivity).connectedNodes)
                } catch (e: Exception) {
                    Log.e("Wearable", "Error retrieving connected watches", e)
                    null
                }
            }

            if (nodeList != null && nodeList.isNotEmpty()) {
                val deviceNames = nodeList.joinToString("\n") { "• ${it.displayName} (${it.id})" }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle(tr("Connected Watches"))
                    .setIcon(R.drawable.ic_pemesa_round_watch)
                    .setMessage(deviceNames)
                    .setPositiveButton("OK", null)
                    .create()
                    .show()
            } else {
                Toast.makeText(this@MainActivity, tr("No connected watch"), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageDialog(
    currentLanguage: String,
    onDismiss: () -> Unit,
    onLanguageSelected: (String) -> Unit
) {
    val languages = listOf(AppLanguage.ENGLISH, AppLanguage.FRENCH)
    fun tr(text: String) = AppLanguage.translate(currentLanguage, text)
    var selectedLanguage by remember { mutableStateOf(currentLanguage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Select Language")) },
        text = {
            Column {
                languages.forEach { lang ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .clickable { selectedLanguage = lang }
                    ) {
                        RadioButton(
                            selected = (lang == selectedLanguage),
                            onClick = { selectedLanguage = lang }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = AppLanguage.languageName(lang))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onLanguageSelected(selectedLanguage) }) {
                Text(tr("Confirm"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Cancel"))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPage(onMenuItemClick: (String) -> Unit, language: String = AppLanguage.ENGLISH) {
    fun tr(text: String) = AppLanguage.translate(language, text)
    var id by rememberSaveable { mutableStateOf("") }
    var professionFull by rememberSaveable { mutableStateOf("") }

    val professions = ActivityLabels.professions

    val context = LocalContext.current

    fun getAbbreviation(fullProf: String): String {
        return fullProf.split(" - ")[0]
    }

    val formOpacity = remember { Animatable(0.82f) }
    LaunchedEffect(Unit) { formOpacity.animateTo(1f, tween(320)) }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(
                    Color(0xFFF1F6F7).copy(alpha = 0.10f),
                    Color(0xFFF1F6F7).copy(alpha = 0.35f),
                    Color(0xFFF1F6F7).copy(alpha = 0.94f)
                ))
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .zIndex(1f),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SimpleTopAppBar(onMenuItemClick, language)

            Spacer(modifier = Modifier.weight(1f))

            Surface(
                modifier = Modifier.fillMaxWidth().alpha(formOpacity.value),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it },
                        label = { Text("ID") },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    )

                    DropdownField(
                        label = tr("Select a profession"),
                        options = professions,
                        selectedOption = professionFull,
                        onOptionSelected = { professionFull = it },
                        displayOption = { tr(it) }
                    )

                    Button(
                        onClick = {
                            val intent = Intent(context, ValidationActivity::class.java).apply {
                                putExtra("id", id)
                                putExtra("profession", getAbbreviation(professionFull))
                                putExtra("Activities", ActivityLabels.defaults(getAbbreviation(professionFull)).take(6).toTypedArray())
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        enabled = id.isNotBlank() && professionFull.isNotBlank()
                    ) {
                        Text(tr("Confirm"), fontSize = 18.sp)
                    }

                    Button(
                        onClick = {
                            id = ""
                            professionFull = ""
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Text(tr("Refresh"), fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    displayOption: (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }
    // Display names do not alter protocol values.
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(PROFESSION_NAMES_PREFERENCES, android.content.Context.MODE_PRIVATE)
    }
    val customNames = remember(preferences, options) {
        options.associateWith { preferences.getString(it.substringBefore(" - "), null) }
    }
    fun displayProfession(option: String): String = customNames[option] ?: displayOption(option)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = displayProfession(selectedOption),
            onValueChange = {},
            readOnly = true,
            shape = RoundedCornerShape(14.dp),
            label = { Text(label) },
            trailingIcon = {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .padding(bottom = 16.dp)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(displayProfession(option)) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleTopAppBar(onMenuItemClick: (String) -> Unit, language: String = AppLanguage.ENGLISH) {
    fun tr(text: String) = AppLanguage.translate(language, text)
    var expanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("PEMESA", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            Box {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = "Menu",
                        modifier = Modifier.size(32.dp)
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                ) {
                    DropdownMenuItem(
                        text = { Text(tr("Smartwatch")) },
                        onClick = {
                            onMenuItemClick("Smartwatch")
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(painter = painterResource(R.drawable.ic_pemesa_round_watch), contentDescription = tr("Smartwatch"),
                                modifier = Modifier.size(24.dp), tint = Color.Unspecified)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Settings")) },
                        onClick = {
                            onMenuItemClick("Settings")
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Settings, contentDescription = tr("Settings"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("About")) },
                        onClick = {
                            onMenuItemClick("About")
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Info, contentDescription = tr("About"))
                        }
                    )
                    Divider()
                    DropdownMenuItem(
                        text = { Text(tr("Exit")) },
                        onClick = {
                            onMenuItemClick("Exit")
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.ExitToApp, contentDescription = tr("Exit"))
                        }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        )
    )
}


