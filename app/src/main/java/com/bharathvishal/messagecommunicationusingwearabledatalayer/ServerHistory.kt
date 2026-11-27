package com.bharathvishal.messagecommunicationusingwearabledatalayer

internal const val SERVER_HISTORY_LIMIT = 3
internal val SERVER_HISTORY_FIELDS = listOf("url", "org", "activity", "wearable")

internal data class ServerTarget(val url: String, val org: String, val activity: String, val wearable: String) {
    fun fields(): List<String> = listOf(url, org, activity, wearable)
}

internal data class ServerHistorySuggestions(val fields: Map<String, List<String>> = emptyMap())

internal fun previousFieldValues(history: List<String>, old: String?, current: String): List<String> =
    (listOfNotNull(old) + history).filter { it.isNotBlank() && it != current }.distinct().take(SERVER_HISTORY_LIMIT)
