package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class MobileProtocolTest {
    @Test fun labelsRemainStableAcrossProfessions() {
        for (profession in listOf("RN", "AS", "ASH", "unknown")) {
            val labels = ActivityLabels.defaults(profession)
            assertEquals(7, labels.size)
            assertEquals("Unlabeled activity", labels.last())
            assertEquals(7, labels.toSet().size)
            assertEquals("$profession:6", ActivityLabels.key(profession, 6))
        }
        assertEquals("Bed bathing", ActivityLabels.defaults("AS")[0])
        assertEquals("Floor cleaning", ActivityLabels.defaults("ASH")[0])
        assertEquals("Measure temperature", ActivityLabels.defaults("RN")[0])
        assertNotEquals(ActivityLabels.key("AS", 0), ActivityLabels.key("ASH", 0))
    }

    @Test fun acknowledgementsRequireCorrectNodeAndKnownResult() {
        for (result in listOf("OK", "REJECTED", "UNKNOWN")) {
            assertTrue(validCommandAck("watch", "watch", result))
            assertFalse(validCommandAck("watch", "other", result))
        }
        assertFalse(validCommandAck("watch", "watch", "INVALID"))
        assertFalse(validCommandAck("watch", "watch", ""))
    }

    @Test fun independentBucketStatesAndHistoricalFailures() {
        fun state(a: String, w: String, pending: Long = 0, failures: Long = 0) =
            influxDisplayState(InfluxWriteStatus(a, w, pending, failures))
        assertEquals(InfluxDisplayState.UNAVAILABLE, influxDisplayState(null))
        assertEquals(InfluxDisplayState.NO_WRITES, state("NONE", "NONE"))
        assertEquals(InfluxDisplayState.PENDING, state("NONE", "NONE", 1))
        assertEquals(InfluxDisplayState.PENDING, state("OK", "NONE"))
        assertEquals(InfluxDisplayState.OK, state("OK", "OK"))
        assertEquals(InfluxDisplayState.FAILED, state("ERROR", "OK"))
        assertEquals(InfluxDisplayState.FAILED, state("OK", "ERROR"))
        assertEquals(InfluxDisplayState.PREVIOUS_FAILURES, state("OK", "OK", failures = 1))
        assertFalse(validInfluxStatus(InfluxWriteStatus("OK", "OK", -1, 0)))
        assertFalse(validInfluxStatus(InfluxWriteStatus("BAD", "OK", 0, 0)))
        assertFalse(validInfluxStatus(InfluxWriteStatus("OK", "OK", 0, -1)))
        assertTrue(validInfluxStatus(InfluxWriteStatus("OK", "OK", 0, 0)))
    }

    @Test fun localizationDoesNotChangeProtocolLabels() {
        val labels = ActivityLabels.defaults("AS")
        assertEquals("Bed bathing", labels.first())
        assertEquals("Bed bathing", AppLanguage.translate(AppLanguage.ENGLISH, labels.first()))
        assertEquals("Paramètres", AppLanguage.translate(AppLanguage.FRENCH, "Settings"))
        assertEquals("Unknown label", AppLanguage.translate(AppLanguage.FRENCH, "Unknown label"))
    }
}
