package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class MagnetometerProtocolTest {
    @Test fun magneticAxesUseExistingMeasurementTagsAndTimestamp() {
        val sample = Data(timestamp = "", person_id = "P01", sessionId = "S1", watchId = "W1",
            label = "Walking", epochMillis = 1700000000000L, sequence = 2,
            magX = 12.5f, magY = -4.25f, magZ = 39f)
        assertEquals("inertial_data,person_id=P01,session_id=S1,watch_id=W1,label=Walking magX=12.5,magY=-4.25,magZ=39.0,sequence=2i 1700000000000", sample.toLineProtocol())
        assertFalse(sample.toLineProtocolActivity().contains("magX"))
        assertEquals("", sample.toLineProtocolWearable())
    }

    @Test fun disabledOrMissingSensorDoesNotInventValues() {
        val sample = Data(timestamp = "", acceX = 1f, epochMillis = 1700000000000L)
        assertEquals("inertial_data,person_id=unknown,label=none acceX=1.0 1700000000000", sample.toLineProtocol())
        assertEquals("", Data(timestamp = "", magX = Float.NaN, magY = Float.POSITIVE_INFINITY).toLineProtocol())
        assertEquals("", Data(timestamp = "").toLineProtocol())
    }
}
