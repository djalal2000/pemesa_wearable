package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class InfluxConnectionAlertTest {
    private fun status(a: String, w: String, failures: Long = 0) = InfluxWriteStatus(a, w, 0, failures)

    @Test fun temporaryFailureAlertsOnceAndRecoveryRequiresSuccessfulWrites() {
        val tracker = InfluxAlertTracker()
        assertEquals(InfluxAlert.NONE, tracker.update(status("OK", "OK")))
        assertEquals(InfluxAlert.LOST, tracker.update(status("ERROR", "OK")))
        assertEquals(InfluxAlert.NONE, tracker.update(status("ERROR", "ERROR")))
        assertEquals(InfluxAlert.NONE, tracker.update(null))
        assertEquals(InfluxAlert.NONE, tracker.update(status("NONE", "NONE")))
        assertEquals(InfluxAlert.NONE, tracker.update(status("OK", "ERROR")))
        assertEquals(InfluxAlert.RECOVERED, tracker.update(status("OK", "OK", 2)))
        assertEquals(InfluxAlert.NONE, tracker.update(status("OK", "OK", 2)))
        assertEquals(InfluxAlert.LOST, tracker.update(status("OK", "ERROR", 2)))
    }

    @Test fun partialConfigurationCanRecoverWithoutSecondBucketWriting() {
        val tracker = InfluxAlertTracker()
        assertEquals(InfluxAlert.LOST, tracker.update(status("ERROR", "NONE")))
        assertEquals(InfluxAlert.RECOVERED, tracker.update(status("OK", "NONE")))
    }

    @Test fun pushAndPollCannotOverwriteNewerStatus() {
        val order = InfluxStatusOrder()
        val stream = "00000000-0000-4000-8000-000000000001"
        val next = "00000000-0000-4000-8000-000000000002"
        assertFalse(order.accept("watch", stream, 1, false))
        assertTrue(order.accept("watch", stream, 2, true))
        assertTrue(order.accept("watch", stream, 4, false))
        assertFalse(order.accept("watch", stream, 3, true))
        assertFalse(order.accept("watch", stream, 4, false))
        assertFalse(order.accept("watch", next, 1, false))
        assertTrue(order.accept("watch", next, 2, true))
        assertFalse(order.accept("watch", stream, 5, false))
        order.clear()
        assertTrue(order.accept("watch", next, 3, true))
    }
}
