package com.bharathvishal.messagecommunicationusingwearabledatalayer

internal enum class HealthCode(val level: String, val module: String) {
    APP_OPEN("INFO", "System"), WATCH_CONNECTED("INFO", "Connection"),
    WATCH_LOST("WARNING", "Connection"), WATCH_TIMEOUT("ERROR", "Connection"),
    START_OK("INFO", "Recording"), STOP_OK("INFO", "Recording"),
    PAUSE_OK("INFO", "Recording"), RESUME_OK("INFO", "Recording"),
    COMMAND_REJECTED("ERROR", "Command"), COMMAND_UNKNOWN("ERROR", "Command"),
    CONFIG_INVALID("ERROR", "Settings"), PREFLIGHT_FAILED("ERROR", "InfluxDB"),
    SENSOR_REGISTER_FAILED("WARNING", "Sensors"), SENSOR_PERMISSION("WARNING", "Sensors"),
    SENSOR_MISSING("WARNING", "Sensors"), SENSOR_INVALID("WARNING", "Sensors"),
    WRITE_RETRY("WARNING", "InfluxDB"), WRITE_HTTP("ERROR", "InfluxDB"),
    WRITE_FAILED("ERROR", "InfluxDB"), QUEUE_FULL("CRITICAL", "InfluxDB"),
    WRITE_RECOVERED("INFO", "InfluxDB"), FAILURES_REPORTED("ERROR", "InfluxDB"),
    INTERNAL_ERROR("ERROR", "System"), DIAGNOSTIC_OK("INFO", "Diagnostics"),
    DIAGNOSTIC_FAILED("ERROR", "Diagnostics"), LOW_BATTERY("WARNING", "Battery"),
    LOW_MEMORY("WARNING", "Memory"), RATE_LOW("WARNING", "Acquisition"),
    THERMAL_HIGH("WARNING", "Thermal"), THERMAL_CRITICAL("CRITICAL", "Thermal"), THERMAL_RECOVERED("INFO", "Thermal")
}

internal data class HealthEvent(val time: Long, val code: HealthCode, val value: Long?, val count: Long = 1L)

/** Structured, bounded in-memory journal. No free-text payloads, identifiers or measurements. */
internal class HealthBuffer(private val capacity: Int = 500, private val clock: () -> Long = System::currentTimeMillis) {
    init { require(capacity in 1..500) }
    private val entries = ArrayDeque<HealthEvent>()
    @Synchronized fun add(code: HealthCode, value: Long? = null) {
        val now = clock()
        val last = entries.lastOrNull()
        if (last != null && last.code == code && last.value == value && now - last.time in 0L..10000L) {
            entries.removeLast()
            entries.addLast(last.copy(time = now, count = last.count + 1L))
        } else {
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(HealthEvent(now, code, value))
        }
    }
    @Synchronized fun snapshot(): List<HealthEvent> = entries.toList()
    @Synchronized fun clear() = entries.clear()
}

internal object HealthJournal {
    private val buffer = HealthBuffer()
    fun add(code: HealthCode, value: Long? = null) = buffer.add(code, value)
    fun snapshot(): List<HealthEvent> = buffer.snapshot()
    fun clear() = buffer.clear()
}
