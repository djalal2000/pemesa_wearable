package com.bharathvishal.messagecommunicationusingwearabledatalayer

/** Main-thread-only arbitration. Never abandon an already submitted command to send Stop. */
internal class RecordingCommandGate {
    enum class Decision { SEND, QUEUE_STOP, BUSY, STOP_ALREADY_REQUESTED }

    private var active: String? = null
    private var queuedStop = false
    val busy: Boolean get() = active != null
    val stopping: Boolean get() = queuedStop || active == STOP

    fun request(message: String): Decision {
        if (stopping) return if (message == STOP) Decision.STOP_ALREADY_REQUESTED else Decision.BUSY
        if (busy) {
            if (message != STOP) return Decision.BUSY
            queuedStop = true
            return Decision.QUEUE_STOP
        }
        return Decision.SEND
    }

    fun begin(message: String) {
        check(!busy && !queuedStop)
        active = message
    }

    /** Called after success, rejection or timeout. A queued Stop gets the next send slot. */
    fun finish(): Boolean {
        active = null
        val nextIsStop = queuedStop
        queuedStop = false
        return nextIsStop
    }

    companion object {
        const val STOP = "Action: Stop"
    }
}

internal fun commandNeedsRecovery(message: String, result: String): Boolean = when (result) {
    "OK" -> false
    "REJECTED", "NO_CONNECTION" ->
        message == RecordingCommandGate.STOP || message.contains("Action: Start") ||
            message == "Action: Pause" || message == "Action: Resume"
    else -> true
}
