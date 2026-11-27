package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class AcquisitionPerformanceTest {
    @Test fun lateEventsCannotEnterAnotherSessionOrPausedSession() {
        assertTrue(acquisitionCallbackAllowed(5, 5, false, false))
        assertFalse(acquisitionCallbackAllowed(4, 5, false, false))
        assertFalse(acquisitionCallbackAllowed(5, 5, true, false))
        assertFalse(acquisitionCallbackAllowed(5, 5, false, true))
    }

    @Test fun transferringBatchOwnershipPreservesAllSubmittedValues() {
        val batch = SampleBatch<Int>(500)
        repeat(500) { batch.add(it) }
        val first = batch.drain()
        repeat(500) { batch.add(it + 500) }
        val second = batch.drain()
        batch.add(1000)
        batch.clear()
        assertEquals((0 until 500).toList(), first)
        assertEquals((500 until 1000).toList(), second)
        assertTrue(batch.drain().isEmpty())
    }

    @Test fun combinedWriterPreservesExactFieldsLabelsAndTimestamps() {
        val samples = listOf(
            Data(timestamp = "", person_id = "P 1", label = "A,B", sessionId = "S", watchId = "W", epochMillis = 100L, sequence = 0, acceX = 1f, magZ = -2.5f),
            Data(timestamp = "", person_id = "P 1", label = "C", epochMillis = 120L, sequence = 1, magX = Float.NaN),
            Data(timestamp = "", epochMillis = 140L, gyroY = 3.25f)
        )
        val actual = buildString { samples.forEach { it.appendActivityBatchLines(this) } }
        assertEquals(samples.flatMap { listOf(it.toLineProtocol(), it.toLineProtocolActivity()) }.filter { it.isNotBlank() }.joinToString("\n"), actual)
        assertTrue(actual.startsWith("inertial_data,person_id=P\\ 1,session_id=S,watch_id=W,label=A\\,B acceX=1.0,magZ=-2.5,sequence=0i 100\n"))
        assertFalse(actual.contains("NaN"))
    }
}
