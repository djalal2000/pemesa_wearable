package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class HealthJournalTest {
    @Test fun boundedAndChronological() {
        var time = 0L
        val buffer = HealthBuffer(3) { time++ * 11000L }
        repeat(7) { buffer.add(HealthCode.WRITE_HTTP, it.toLong()) }
        assertEquals(listOf(4L, 5L, 6L), buffer.snapshot().map { it.value })
    }
    @Test fun groupsRepeatedErrorsWithoutGrowing() {
        var time = 1000L
        val buffer = HealthBuffer(500) { time++ }
        repeat(2000) { buffer.add(HealthCode.WRITE_HTTP, 503) }
        assertEquals(1, buffer.snapshot().size)
        assertEquals(2000L, buffer.snapshot().single().count)
        buffer.add(HealthCode.WRITE_HTTP, 401)
        assertEquals(2, buffer.snapshot().size)
        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
    }
    @Test fun snapshotsAreIndependent() {
        val buffer = HealthBuffer()
        buffer.add(HealthCode.START_OK)
        val snapshot = buffer.snapshot()
        buffer.add(HealthCode.STOP_OK)
        assertEquals(1, snapshot.size)
        assertEquals(2, buffer.snapshot().size)
    }
}
