package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class AcquisitionLogicTest {
    private val start = "ID: TEST, Profession: AS, Activity: Bed bathing, FatigueLevel: 0, Action: Start"

    @Test fun startRequiresMetadataAndStoppedState() {
        assertEquals("Bed bathing", validatedCommand(start, false)?.get("Activity"))
        assertEquals("0", validatedCommand(start, false)?.get("FatigueLevel"))
        assertNull(validatedCommand(start, true))
        assertNull(validatedCommand("Action: Start", false))
    }

    @Test fun pauseResumeStopAndRefresh() {
        for (action in listOf("Pause", "Resume")) {
            assertNotNull(validatedCommand("Action: $action", true))
            assertNull(validatedCommand("Action: $action", false))
        }
        for (action in listOf("Stop", "Refresh")) {
            assertNotNull(validatedCommand("Action: $action", true))
            assertNotNull(validatedCommand("Action: $action", false))
        }
    }

    @Test fun invalidCommandsAndFatigueAreRejected() {
        for (payload in listOf("", "Action: Bogus", "Action: Stop, Action: Start",
            "Activity:", "Activity: label:invalid", "Unexpected: value", "FatigueLevel: -1",
            "FatigueLevel: 6", "FatigueLevel: NaN", "FatigueLevel: 2.5")) {
            assertNull(payload, validatedCommand(payload, true))
        }
        for (level in 0..5) assertNotNull(validatedCommand("FatigueLevel: $level", true))
        assertNull(validatedCommand("Activity: Rest break", false))
        assertNotNull(validatedCommand("Activity: Rest break", true))
    }

    @Test fun shortRecordingFlushesEveryRemainingSample() {
        val buffer = SampleBatch<Int>(500)
        repeat(300) { buffer.add(it) }
        assertFalse(buffer.isFull())
        assertEquals((0 until 300).toList(), buffer.drain())
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun fullAndPartialBatchesKeepOrderingAndLabels() {
        val buffer = SampleBatch<Data>(500)
        val output = mutableListOf<List<Data>>()
        repeat(1300) { i ->
            buffer.add(Data(person_id = "TEST", label = if (i < 601) "Bed bathing" else "Rest break",
                acceX = i.toFloat()))
            if (buffer.isFull()) output.add(buffer.drain())
        }
        output.add(buffer.drain())
        assertEquals(listOf(500, 500, 300), output.map { it.size })
        val samples = output.flatten()
        assertEquals((0 until 1300).map { it.toFloat() }, samples.map { it.acceX })
        assertEquals("Bed bathing", samples[600].label)
        assertEquals("Rest break", samples[601].label)
        assertEquals(0, buffer.size)
    }

    @Test fun eightHourSyntheticAcquisitionKeepsBufferBounded() {
        val buffer = SampleBatch<Int>(500)
        var submitted = 0
        repeat(8 * 3600 * 50) { i ->
            buffer.add(i)
            if (buffer.isFull()) {
                val batch = buffer.drain()
                assertEquals(submitted, batch.first())
                submitted += batch.size
            }
        }
        submitted += buffer.drain().size
        assertEquals(1440000, submitted)
        assertEquals(0, buffer.size)
    }

    @Test fun duplicateCommandsReuseResultPerNodeAndEvictOldEntries() {
        val cache = CommandResults(2)
        var executions = 0
        fun call(node: String, id: String) = cache.result(node, id) { executions++; "OK" }
        assertEquals("OK", call("watchA", "id"))
        assertEquals("OK", call("watchA", "id"))
        assertEquals(1, executions)
        call("watchB", "id")
        call("watchA", "id2")
        call("watchA", "id")
        assertEquals(4, executions)
    }

    @Test fun httpRetryOnlyForTransientErrorsAndHonorsDelay() {
        for (code in listOf(408, 429, 500, 502, 503, 599)) {
            assertEquals(1000L, retryDelayMs(code, null, 0))
            assertEquals(2000L, retryDelayMs(code, null, 1))
            assertEquals(30000L, retryDelayMs(code, null, 8))
            assertNull(retryDelayMs(code, null, 8, 30000L))
        }
        for (code in listOf(204, 400, 401, 403, 404, 422)) assertNull(retryDelayMs(code, null, 0))
        assertEquals(5000L, retryDelayMs(429, "5", 0))
        assertEquals(30000L, retryDelayMs(503, "30", 0))
        assertEquals(31000L, retryDelayMs(429, "31", 0))
        assertNull(retryDelayMs(429, "301", 0))
        for (header in listOf("-1", "unknown", "999999999999999999999999")) {
            assertNull(retryDelayMs(429, header, 0))
        }
    }
}
