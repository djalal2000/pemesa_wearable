package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context

// Localize interface text without changing protocol values or recorded data.
internal object AppLanguage {
    private const val PREFS = "app_language"
    private const val KEY = "language"
    const val ENGLISH = "English"
    const val FRENCH = "French"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, ENGLISH) ?: ENGLISH

    fun set(context: Context, language: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, language).apply()
    }

    fun languageName(language: String): String =
        if (language == FRENCH) "Français" else "English"

    fun translate(language: String, english: String): String =
        if (language == FRENCH) french[english] ?: english else english

    private val french = mapOf(
        "Selected language:" to "Langue sélectionnée :",
        "Select Language" to "Choisir la langue",
        "Confirm" to "Confirmer",
        "Cancel" to "Annuler",
        "Smartwatch" to "Montre connectée",
        "Settings" to "Paramètres",
        "Rename activity labels" to "Renommer les étiquettes d’activité",
        "Restore default labels" to "Rétablir les étiquettes par défaut",
        "Restore the default activity labels for this profession?" to "Rétablir les étiquettes par défaut de ce métier ?",
        "Rename label" to "Renommer l’étiquette",
        "Save" to "Enregistrer",
        "Enter a label" to "Saisissez une étiquette",
        "Do not use commas or colons" to "N’utilisez ni virgule ni deux-points",
        "This label already exists" to "Cette étiquette existe déjà",
        "About" to "À propos",
        "Exit" to "Quitter",
        "Connected Watches" to "Montres connectées",
        "No connected watch" to "Aucune montre connectée",
        "No connected watches" to "Aucune montre connectée",
        "Select a profession" to "Choisir un métier",
        "Refresh" to "Actualiser",
        "RN - Registered Nurse" to "RN - Infirmier / Infirmière",
        "AS - Certified Nursing Assistant" to "AS - Aide-soignant / Aide-soignante",
        "ASH - Environmental Services worker" to "ASH - Agent des services hospitaliers",
        "Measure temperature" to "Prendre la température",
        "Inject medication" to "Injecter un médicament",
        "Apply dressing" to "Poser un pansement",
        "Take blood pressure" to "Prendre la tension artérielle",
        "Activity 1" to "Activité 1",
        "Activity 2" to "Activité 2",
        "Activity 3" to "Activité 3",
        "Activity 4" to "Activité 4",
        "Activity 5" to "Activité 5",
        "Activity 6" to "Activité 6",
        "Bed-to-bed transfer" to "Transfert lit à lit",
        "Bed-to-chair transfer" to "Transfert lit à fauteuil",
        "Toilet assistance" to "Aide à la toilette",
        "Bed making" to "Réfection du lit",
        "Office work" to "Travail de bureau",
        "Bed bathing" to "Toilette au lit",
        "Bathroom bathing" to "Toilette en salle de bain",
        "Rest break" to "Pause",
        "Wheelchair transfer" to "Transfert en fauteuil roulant",
        "Bed preparation" to "Préparation du lit",
        "Deliver food" to "Distribution des repas",
        "Waste collection" to "Collecte des déchets",
        "Climbing the stairs" to "Monter les escaliers",
        "Room cleaning" to "Nettoyage de la chambre",
        "Floor cleaning" to "Nettoyage du sol",
        "Meal distribution" to "Distribution des repas",
        "Medication storage" to "Rangement des médicaments",
        "Connection status" to "Statut de la connexion",
        "Start Recording" to "Démarrer l’enregistrement",
        "Stop Recording" to "Arrêter l’enregistrement",
        "Unlabeled activity" to "Activité non étiquetée",
        "🚫 Unlabeled activity" to "🚫 Activité non étiquetée",
        "🛑 Unlabeled activity" to "🛑 Activité non étiquetée",
        "Fatigue Level:" to "Niveau de fatigue :",
        "Message received:" to "Message reçu :",
        "Watch connection lost. Stopping recording." to "Connexion à la montre perdue. Arrêt de l’enregistrement.",
        "⏸ Pause" to "⏸ Pause",
        "▶ Resume" to "▶ Reprendre",
        "Recording stopped" to "Enregistrement arrêté",
        "Recording started" to "Enregistrement démarré",
        "Watch not connected." to "Montre non connectée.",
        "Please start recording before setting the Activity." to "Démarrez l’enregistrement avant de définir l’activité.",
        "No recording in progress." to "Aucun enregistrement en cours.",
        "Recording paused" to "Enregistrement mis en pause",
        "Recording resumed" to "Enregistrement repris",
        "Start recording before choosing a Activity." to "Démarrez l’enregistrement avant de choisir une activité.",
        "❌ Watch not connected." to "❌ Montre non connectée.",
        "✅ Watch connected and app open." to "✅ Montre connectée et application ouverte.",
        "⚠️ Watch connected but app closed." to "⚠️ Montre connectée mais application fermée.",
        "Current Activity:" to "Activité actuelle :",
        "Page refreshed" to "Page actualisée",
        "Towards healthier workplaces" to "Pour des lieux de travail plus sains",
        "No watch connected" to "Aucune montre connectée",
        "Failed to send message" to "Échec de l’envoi du message"
    )
}

internal fun Context.localized(english: String): String =
    AppLanguage.translate(AppLanguage.get(this), english)
