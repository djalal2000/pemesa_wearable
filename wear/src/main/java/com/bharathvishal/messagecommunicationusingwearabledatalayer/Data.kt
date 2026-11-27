package com.bharathvishal.messagecommunicationusingwearabledatalayer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun escapeTag(value: String): String = value.replace("\\", "\\\\")
    .replace(",", "\\,").replace("=", "\\=").replace(" ", "\\ ")
    .replace("\n", " ").replace("\r", " ")

fun parseTimestampToMillis(timeStr: String?): Long {
    if (timeStr == null) return System.currentTimeMillis()
    return try {
        val formatter = SimpleDateFormat("yyyy-MM-dd:HH:mm:ss.SSS", Locale.getDefault())
        val date = formatter.parse(timeStr)
        date?.time ?: System.currentTimeMillis()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}

data class Data(
    val timestamp: String = SimpleDateFormat("yyyy-MM-dd:HH:mm:ss.SSS", Locale.getDefault()).format(
        Date()
    ),
    val person_id: String? = null,
    val acceX: Float? = null,
    val acceY: Float? = null,
    val acceZ: Float? = null,
    val gyroX: Float? = null,
    val gyroY: Float? = null,
    val gyroZ: Float? = null,
    val gravityX: Float? = null,
    val gravityY: Float? = null,
    val gravityZ: Float? = null,

    val linAccX: Float? = null,
    val linAccY: Float? = null,
    val linAccZ: Float? = null,
    val rotX: Float? = null,
    val rotY: Float? = null,
    val rotZ: Float? = null,
    val rotW: Float? = null,

    val bpm: String? = null,
    val label: String? = null,
    val fatigueLevel: String? = null,
    val steps: Int? = null,
    val light: Float? = null,
    val pressure: Float? = null,
    val epochMillis: Long? = null,
    val sessionId: String? = null,
    val sequence: Long? = null,
    val sessionFields: String? = null,
    val watchId: String? = null,
    val magX: Float? = null,
    val magY: Float? = null,
    val magZ: Float? = null
) {

    internal fun toSessionProtocol(): String {
        val fields = sessionFields ?: return ""
        val tags = "person_id=" + escapeTag(person_id ?: "unknown") + ",session_id=" + escapeTag(sessionId ?: "unknown") + (watchId?.let { ",watch_id=" + escapeTag(it) } ?: "")
        return "session_metadata,$tags $fields ${epochMillis ?: parseTimestampToMillis(timestamp)}"
    }

    private fun activityTags(): String = "person_id=" + escapeTag(person_id ?: "unknown") +
        (sessionId?.let { ",session_id=" + escapeTag(it) } ?: "") +
        (watchId?.let { ",watch_id=" + escapeTag(it) } ?: "") + ",label=" + escapeTag(label ?: "none")

    private fun StringBuilder.appendInertial(tags: String, timestampMs: Long) {
        val originalLength = length
        if (isNotEmpty()) append('\n')
        append("inertial_data,").append(tags).append(' ')
        val fieldStart = length
        fun field(name: String, value: Float?) {
            if (value == null || !value.isFinite()) return
            if (length > fieldStart) append(',')
            append(name).append('=').append(value)
        }
        field("acceX", acceX); field("acceY", acceY); field("acceZ", acceZ)
        field("gyroX", gyroX); field("gyroY", gyroY); field("gyroZ", gyroZ)
        field("gravityX", gravityX); field("gravityY", gravityY); field("gravityZ", gravityZ)
        field("linAccX", linAccX); field("linAccY", linAccY); field("linAccZ", linAccZ)
        field("rotX", rotX); field("rotY", rotY); field("rotZ", rotZ); field("rotW", rotW)
        field("magX", magX); field("magY", magY); field("magZ", magZ)
        if (length == fieldStart) {
            setLength(originalLength)
            return
        }
        sequence?.let { append(",sequence=").append(it).append('i') }
        append(' ').append(timestampMs)
    }

    private fun StringBuilder.appendActivity(tags: String, timestampMs: Long) {
        if (isNotEmpty()) append('\n')
        append("activity,").append(tags).append(" dummy=1")
        sequence?.let { append(",sequence=").append(it).append('i') }
        append(' ').append(timestampMs)
    }

    fun toLineProtocol(): String = buildString {
        appendInertial(activityTags(), epochMillis ?: parseTimestampToMillis(timestamp))
    }

    fun toLineProtocolActivity(): String = buildString {
        appendActivity(activityTags(), epochMillis ?: parseTimestampToMillis(timestamp))
    }

    /** Serialize both activity measurements with the same tags and timestamp. */
    internal fun appendActivityBatchLines(output: StringBuilder) {
        val tags = activityTags()
        val timestampMs = epochMillis ?: parseTimestampToMillis(timestamp)
        with(output) {
            appendInertial(tags, timestampMs)
            appendActivity(tags, timestampMs)
        }
    }

    fun toLineProtocolWearable(): String {
        val timestampMs = epochMillis ?: parseTimestampToMillis(timestamp)
        val safePersonId = escapeTag(person_id ?: "unknown") + (sessionId?.let { ",session_id=" + escapeTag(it) } ?: "") + (watchId?.let { ",watch_id=" + escapeTag(it) } ?: "")

        val lines = mutableListOf<String>()

        bpm?.toFloatOrNull()?.takeIf { it.isFinite() }?.let {
            lines.add("heart_rate,person_id=$safePersonId bpm=$it $timestampMs")
        }

        fatigueLevel?.toIntOrNull()?.takeIf { it in 0..5 }?.let {
            lines.add("fatigue_level,person_id=$safePersonId fatigue_level=$it $timestampMs")
        }

        // A missing step-counter reading is not a zero-step measurement.
        if (steps != null && steps >= 0) {
            lines.add("steps,person_id=$safePersonId steps=$steps $timestampMs")
        }

        light?.takeIf { it.isFinite() }?.let { lines.add("light,person_id=$safePersonId lux=$it $timestampMs") }
        pressure?.takeIf { it.isFinite() }?.let { lines.add("pressure,person_id=$safePersonId hPa=$it $timestampMs") }

        return lines.joinToString("\n")
    }

}
