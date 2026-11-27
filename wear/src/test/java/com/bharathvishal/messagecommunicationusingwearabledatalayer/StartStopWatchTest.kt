package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class StartStopWatchTest {
    private val start = "ID: TEST, Profession: AS, Activity: Bed bathing, FatigueLevel: 0, Action: Start"

    @Test fun startAckRequiresRunningTimerAndUnpausedState() {
        assertTrue(recordingStateMatches("Start", true, false, false))
        assertFalse(recordingStateMatches("Start", false, false, false))
        assertFalse(recordingStateMatches("Start", true, true, false))
        assertFalse(recordingStateMatches("Start", true, false, true))
        assertNotNull(validatedCommand(start, false))
        assertNull(validatedCommand(start, true))
    }

    @Test fun stopAckRequiresCancelledTimerAndStoppedFlag() {
        assertTrue(recordingStateMatches("Stop", false, true, false))
        assertFalse(recordingStateMatches("Stop", true, true, false))
        assertFalse(recordingStateMatches("Stop", false, false, false))
        assertNotNull(validatedCommand("Action: Stop", true))
        assertNotNull(validatedCommand("Action: Stop", false))
    }

    @Test fun stopAfterThreeSecondsDrainsPartialBatchOnlyOnce() {
        val batch = SampleBatch<Int>(500)
        repeat(150) { batch.add(it) }
        assertEquals((0 until 150).toList(), batch.drain())
        assertTrue(batch.drain().isEmpty())
    }

    @Test fun startStopStartDoesNotCarryOldBatch() {
        val batch = SampleBatch<Int>(500)
        batch.add(1)
        assertEquals(listOf(1), batch.drain())
        batch.clear()
        batch.add(2)
        assertEquals(listOf(2), batch.drain())
        assertEquals(0, batch.size)
        assertNotNull(validatedCommand(start, false))
    }

    @Test fun stopIsValidWhilePausedAndDuplicateAckDoesNotExecuteAgain() {
        assertTrue(recordingStateMatches("Pause", true, false, true))
        assertNotNull(validatedCommand("Action: Stop", true))
        val results = CommandResults()
        var stops = 0
        repeat(2) {
            assertEquals("OK", results.result("phone", "stop-id") { stops++; "OK" })
        }
        assertEquals(1, stops)
    }
}
