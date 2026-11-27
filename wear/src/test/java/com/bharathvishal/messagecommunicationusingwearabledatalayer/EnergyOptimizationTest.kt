package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class EnergyOptimizationTest {
    @Test fun wearableBatchPreservesEveryPointAndTimestamp() {
        val buffer = WearableBatch<Data>()
        val output = mutableListOf<Data>()
        val original = (0 until 201).map { i ->
            Data(timestamp = "", person_id = "test", pressure = 1000f + i, epochMillis = 1700000000000L + i)
        }
        original.forEach { if (buffer.add(it)) output.addAll(buffer.drain()) }
        assertEquals(9, buffer.size)
        output.addAll(buffer.drain()) // Stop flushes a partial batch.
        assertEquals(original, output)
        assertEquals(original.map { it.toLineProtocolWearable() }, output.map { it.toLineProtocolWearable() })
        assertEquals(0, buffer.size)
    }

    @Test fun fullBatchAndPartialFlushDoNotDuplicatePoints() {
        val buffer = WearableBatch<Int>(3)
        assertFalse(buffer.add(1))
        assertFalse(buffer.add(2))
        assertTrue(buffer.add(3))
        val first = buffer.drain()
        assertEquals(listOf(1, 2, 3), first)
        assertFalse(buffer.add(4))
        assertEquals(listOf(4), buffer.drain())
        assertTrue(buffer.drain().isEmpty())
        assertEquals(listOf(1, 2, 3), first)
    }

    @Test fun numericTimestampPreservesExistingLineProtocol() {
        val legacy = Data(timestamp = "2025-01-02:03:04:05.123", person_id = "test", acceX = 1.25f,
            pressure = 1010f, label = "activity", sessionId = "session", watchId = "watch", sequence = 4)
        val optimized = legacy.copy(timestamp = "", epochMillis = parseTimestampToMillis(legacy.timestamp))
        assertEquals(legacy.toLineProtocol(), optimized.toLineProtocol())
        assertEquals(legacy.toLineProtocolActivity(), optimized.toLineProtocolActivity())
        assertEquals(legacy.toLineProtocolWearable(), optimized.toLineProtocolWearable())
    }

    @Test fun sensorValidationHasSameRequiredFieldSemantics() {
        assertTrue(finiteSensorValues(floatArrayOf(1f, 2f, 3f), 3))
        assertTrue(finiteSensorValues(floatArrayOf(1f, 2f, 3f, Float.NaN), 3))
        assertFalse(finiteSensorValues(floatArrayOf(1f, Float.NaN, 3f), 3))
        assertFalse(finiteSensorValues(floatArrayOf(Float.POSITIVE_INFINITY), 1))
        assertFalse(finiteSensorValues(floatArrayOf(1f, 2f), 3))
    }
}
