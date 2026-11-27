package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class StartStopPriorityTest {
    private val start = "ID: TEST, Profession: AS, Activity: Bed bathing, FatigueLevel: 0, Action: Start"
    private val stop = RecordingCommandGate.STOP

    @Test fun stopTakesNextSlotDuringActivityChange() {
        val gate = RecordingCommandGate()
        val activity = "Activity: Bed bathing"
        assertEquals(RecordingCommandGate.Decision.SEND, gate.request(activity))
        gate.begin(activity)
        assertEquals(RecordingCommandGate.Decision.QUEUE_STOP, gate.request(stop))
        assertTrue(gate.stopping)
        assertEquals(RecordingCommandGate.Decision.BUSY, gate.request("FatigueLevel: 3"))
        assertEquals(RecordingCommandGate.Decision.BUSY, gate.request(start))
        assertTrue(gate.finish())
        assertEquals(RecordingCommandGate.Decision.SEND, gate.request(stop))
        gate.begin(stop)
        assertEquals(RecordingCommandGate.Decision.BUSY, gate.request(start))
        assertFalse(gate.finish())
        assertFalse(gate.busy)
    }

    @Test fun stopDuringStartOrPauseOrResumeIsNotLost() {
        for (pending in listOf(start, "Action: Pause", "Action: Resume")) {
            val gate = RecordingCommandGate()
            gate.begin(pending)
            assertEquals(RecordingCommandGate.Decision.QUEUE_STOP, gate.request(stop))
            assertTrue(gate.finish())
            gate.begin(stop)
            assertFalse(gate.finish())
        }
    }

    @Test fun repeatedStopIsCoalescedAndDoubleStartIsRejected() {
        val gate = RecordingCommandGate()
        gate.begin(start)
        assertEquals(RecordingCommandGate.Decision.BUSY, gate.request(start))
        assertEquals(RecordingCommandGate.Decision.QUEUE_STOP, gate.request(stop))
        assertEquals(RecordingCommandGate.Decision.STOP_ALREADY_REQUESTED, gate.request(stop))
        assertTrue(gate.finish())
        gate.begin(stop)
        assertEquals(RecordingCommandGate.Decision.STOP_ALREADY_REQUESTED, gate.request(stop))
        assertFalse(gate.finish())
        assertEquals(RecordingCommandGate.Decision.SEND, gate.request(start))
    }

    @Test fun missingOrRejectedStopAckRequiresRecovery() {
        for (result in listOf("UNKNOWN", "REJECTED", "NO_CONNECTION")) {
            assertTrue(commandNeedsRecovery(stop, result))
        }
        assertFalse(commandNeedsRecovery(stop, "OK"))
        assertTrue(commandNeedsRecovery(start, "UNKNOWN"))
        assertFalse(commandNeedsRecovery("Activity: Bed bathing", "REJECTED"))
    }

    @Test fun stopAfterTimedOutStartStillGetsNextSlot() {
        val gate = RecordingCommandGate()
        gate.begin(start)
        assertEquals(RecordingCommandGate.Decision.QUEUE_STOP, gate.request(stop))
        assertTrue(commandNeedsRecovery(start, "UNKNOWN"))
        assertTrue(gate.finish())
        assertEquals(RecordingCommandGate.Decision.SEND, gate.request(stop))
    }

    @Test fun stopCanBeRetriedAfterUnknownConfirmation() {
        val gate = RecordingCommandGate()
        gate.begin(stop)
        assertTrue(commandNeedsRecovery(stop, "UNKNOWN"))
        assertFalse(gate.finish())
        assertEquals(RecordingCommandGate.Decision.SEND, gate.request(stop))
    }
}
