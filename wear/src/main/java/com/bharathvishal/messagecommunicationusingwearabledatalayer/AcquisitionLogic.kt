package com.bharathvishal.messagecommunicationusingwearabledatalayer

internal fun validatedCommand(payload: String, running: Boolean): Map<String, String>? {
        val pairs = payload.split(',').map { it.split(':', limit = 2).map(String::trim) }
        if (pairs.any { it.size != 2 || it[0].isEmpty() || it[1].isEmpty() }) return null
        val fields = pairs.associate { it[0] to it[1] }
        if (fields.size != pairs.size || fields.keys.any { it !in setOf("ID", "Profession", "Activity", "FatigueLevel", "Action") }) return null
        if (fields.values.any { ':' in it }) return null
        val action = fields["Action"]
        val fatigue = fields["FatigueLevel"]
        if (fatigue != null && fatigue.toIntOrNull()?.let { it in 0..5 } != true) return null
        when (action) {
            "Start" -> if (running || listOf("ID", "Profession", "Activity", "FatigueLevel").any { it !in fields }) return null
            "Pause", "Resume" -> if (!running) return null
            "Stop", "Refresh" -> Unit
            null -> if (fields.keys.any { it !in setOf("Activity", "FatigueLevel") } ||
                ("Activity" in fields && !running)) return null
            else -> return null
        }
    return fields
}

/** Only the caller's recording lock may access this buffer. */
internal class SampleBatch<T>(private val capacity: Int) {
    init { require(capacity > 0) }
    private var items = ArrayList<T>(capacity)
    val size: Int get() = items.size
    fun add(sample: T) { items.add(sample) }
    fun isFull(): Boolean = items.size >= capacity
    fun clear() = items.clear()
    // Transfer batch ownership to the writer.
    fun drain(): List<T> {
        if (items.isEmpty()) return emptyList()
        val completed = items
        items = ArrayList(capacity)
        return completed
    }
}

internal class CommandResults(private val capacity: Int = 128) {
    private val results = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > capacity
    }
    fun result(node: String, id: String, execute: () -> String): String {
        val key = "$node:$id"
        return results[key] ?: execute().also { results[key] = it }
    }
}

/** Bound recovery time for each in-flight batch, without storing measurements on disk. */
internal const val WRITE_RETRY_BUDGET_MS = 300_000L

internal fun recoveryDelayMs(attempt: Int, remainingMs: Long, retryAfterMs: Long = 0L): Long? {
    if (attempt < 0 || remainingMs <= 0 || retryAfterMs < 0) return null
    val backoff = minOf(30_000L, 1000L shl attempt.coerceAtMost(5))
    val delay = maxOf(backoff, retryAfterMs)
    return delay.takeIf { it < remainingMs }
}

internal fun retryDelayMs(code: Int, retryHeader: String?, attempt: Int,
                          remainingMs: Long = WRITE_RETRY_BUDGET_MS,
                          nowMs: Long = System.currentTimeMillis()): Long? {
    if (code != 408 && code != 429 && code !in 500..599) return null
    val header = retryHeader?.trim()?.takeIf { it.isNotEmpty() }
    val afterMs = if (header == null) 0L else {
        val seconds = header.toLongOrNull()
        if (seconds != null) {
            if (seconds < 0 || seconds > Long.MAX_VALUE / 1000) return null
            seconds * 1000
        } else {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            format.isLenient = false
            val position = java.text.ParsePosition(0)
            val date = format.parse(header, position)
            if (date == null || position.index != header.length) return null
            (date.time - nowMs).coerceAtLeast(0L)
        }
    }
    return recoveryDelayMs(attempt, remainingMs, afterMs)
}

// Verify recording state before acknowledging a command.
internal fun recordingStateMatches(action: String?, timerRunning: Boolean, stopped: Boolean, paused: Boolean): Boolean =
    when (action) {
        "Start", "Resume" -> timerRunning && !stopped && !paused
        "Pause" -> timerRunning && paused
        "Stop", "Refresh" -> !timerRunning && stopped
        else -> true
    }

internal fun finiteSensorValues(values: FloatArray, required: Int): Boolean {
    if (required < 1 || values.size < required) return false
    for (index in 0 until required) if (!values[index].isFinite()) return false
    return true
}

/** Access under the recording lock. */
internal class WearableBatch<T>(private val capacity: Int = 64) {
    private val batch = SampleBatch<T>(capacity)
    val size: Int get() = batch.size
    fun add(value: T): Boolean {
        check(!batch.isFull())
        batch.add(value)
        return batch.isFull()
    }
    fun drain(): List<T> = if (batch.size == 0) emptyList() else batch.drain()
}

internal fun acquisitionCallbackAllowed(callbackGeneration: Long, activeGeneration: Long, stopped: Boolean, paused: Boolean): Boolean =
    callbackGeneration == activeGeneration && !stopped && !paused
