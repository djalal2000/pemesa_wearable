package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class DataProtocolTest {
    @Test fun noArtificialSteps() {
        assertFalse(Data(bpm = "80").toLineProtocolWearable().contains("steps,"))
        assertTrue(Data(steps = 0).toLineProtocolWearable().contains("steps=0"))
    }
    @Test fun initialFatigueZeroIsValid() {
        assertTrue(Data(fatigueLevel = "0").toLineProtocolWearable().contains("fatigue_level=0"))
        assertFalse(Data(fatigueLevel = "6").toLineProtocolWearable().contains("fatigue_level"))
    }
    @Test fun nonFiniteValuesAreOmitted() {
        assertEquals("", Data(acceX = Float.NaN).toLineProtocol())
        assertEquals("", Data(pressure = Float.POSITIVE_INFINITY, bpm = "NaN").toLineProtocolWearable())
        assertTrue(Data(acceX = 1f, gyroX = Float.NaN).toLineProtocol().contains("acceX=1.0"))
    }
    @Test fun labelsAndTimestampRemainPresent() {
        val data = Data(timestamp = "2026-01-01:12:00:00.000", person_id = "P01",
            label = "Bed bathing", acceX = 1f)
        val line = data.toLineProtocol()
        assertTrue(line.contains("person_id=P01,label=Bed\\ bathing"))
        assertTrue(line.endsWith(parseTimestampToMillis(data.timestamp).toString()))
        assertTrue(data.toLineProtocolActivity().contains("dummy=1"))
    }
}
