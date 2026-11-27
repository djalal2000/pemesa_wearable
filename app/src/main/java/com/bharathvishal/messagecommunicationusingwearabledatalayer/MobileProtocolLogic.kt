package com.bharathvishal.messagecommunicationusingwearabledatalayer

internal fun validCommandAck(expectedNode: String, sourceNode: String, result: String): Boolean =
    expectedNode == sourceNode && result in setOf("OK", "REJECTED", "UNKNOWN")

internal fun validInfluxStatus(status: InfluxWriteStatus): Boolean {
    val states = setOf("NONE", "OK", "ERROR")
    return status.activity in states && status.wearable in states &&
        status.pending >= 0 && status.failures >= 0
}

internal enum class InfluxDisplayState { UNAVAILABLE, FAILED, PREVIOUS_FAILURES, OK, PENDING, NO_WRITES }

internal fun influxDisplayState(status: InfluxWriteStatus?): InfluxDisplayState = when {
    status == null -> InfluxDisplayState.UNAVAILABLE
    status.activity == "ERROR" || status.wearable == "ERROR" -> InfluxDisplayState.FAILED
    status.failures > 0 -> InfluxDisplayState.PREVIOUS_FAILURES
    status.activity == "OK" && status.wearable == "OK" -> InfluxDisplayState.OK
    status.pending > 0 || status.activity == "OK" || status.wearable == "OK" -> InfluxDisplayState.PENDING
    else -> InfluxDisplayState.NO_WRITES
}

/** Reject stale push/poll snapshots; a correlated poll establishes the watch process stream. */
internal class InfluxStatusOrder {
    private var node = ""
    private var stream = ""
    private var revision = -1L
    @Synchronized fun accept(source: String, process: String, next: Long, solicited: Boolean): Boolean {
        // Older watch releases do not publish spontaneous events or sequence information.
        if (process.isEmpty() && next == -1L) return solicited && (node != source || stream.isEmpty())
        if (!validConfigVersion(process) || next <= 0L) return false
        if (node != source || stream != process) {
            if (!solicited) return false
            node = source
            stream = process
            revision = -1L
        }
        if (next <= revision) return false
        revision = next
        return true
    }
    @Synchronized fun clear() { node = ""; stream = ""; revision = -1L }
}

internal enum class InfluxAlert { NONE, LOST, RECOVERED }

/** Unknown status is not evidence of recovery. A new accepted write is required. */
internal class InfluxAlertTracker {
    private var failed = false
    fun update(status: InfluxWriteStatus?): InfluxAlert {
        if (status == null) return InfluxAlert.NONE
        if (status.activity == "ERROR" || status.wearable == "ERROR") {
            if (failed) return InfluxAlert.NONE
            failed = true
            return InfluxAlert.LOST
        }
        if (failed && (status.activity == "OK" || status.wearable == "OK")) {
            failed = false
            return InfluxAlert.RECOVERED
        }
        return InfluxAlert.NONE
    }
}
