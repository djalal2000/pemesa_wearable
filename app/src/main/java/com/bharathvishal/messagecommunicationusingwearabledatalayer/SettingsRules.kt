package com.bharathvishal.messagecommunicationusingwearabledatalayer

internal const val PROFESSION_NAMES_PREFERENCES = "profession_display_names"

internal enum class SettingsNameError { EMPTY, DELIMITER, DUPLICATE }

internal fun professionNameError(name: String, otherNames: List<String>): SettingsNameError? =
    when {
        name.isBlank() -> SettingsNameError.EMPTY
        otherNames.any { it.equals(name.trim(), ignoreCase = true) } -> SettingsNameError.DUPLICATE
        else -> null
    }

internal fun activityNameError(name: String, otherNames: List<String>): SettingsNameError? =
    when {
        name.isBlank() -> SettingsNameError.EMPTY
        name.any { it == ',' || it == ':' || it == '\n' || it == '\r' } -> SettingsNameError.DELIMITER
        otherNames.any { it == name.trim() } -> SettingsNameError.DUPLICATE
        else -> null
    }
