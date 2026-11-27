package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class MagnetometerSelectionTest {
    @Test fun originalDefaultSelectionIsUnchanged() {
        assertEquals(setOf("accelerometer", "gyroscope", "gravity", "linear", "rotation", "heart",
            "steps", "light", "pressure", "fatigue"), DEFAULT_SENSOR_KEYS)
        assertFalse("magnetometer" in DEFAULT_SENSOR_KEYS)
        assertEquals(11, SENSOR_KEYS.size)
        assertTrue(validSensorSelection(DEFAULT_SENSOR_KEYS))
    }

    @Test fun magnetometerCanBeEnabledAloneOrWithExistingSensors() {
        assertTrue(validSensorSelection(setOf("magnetometer")))
        assertTrue(validSensorSelection(DEFAULT_SENSOR_KEYS + "magnetometer"))
        assertFalse(validSensorSelection(emptySet()))
        assertFalse(validSensorSelection(setOf("unknown")))
        assertEquals(500, batchSizeFor(50, 10))
    }
}
