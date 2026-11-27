package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class ConfigurationVersionTest {
    private val v1 = "00000000-0000-4000-8000-000000000001"
    private val v2 = "00000000-0000-4000-8000-000000000002"

    @Test fun versionAndDigestMustBothMatch() {
        val hash = configDigest("settings")
        assertTrue(configMatches(v1, hash, v1, hash))
        assertFalse(configMatches(v1, hash, v2, hash))
        assertFalse(configMatches(v1, hash, v1, configDigest("changed")))
        assertFalse(configMatches("", "", "", ""))
    }
    @Test fun digestIsDeterministicAndSensitiveToChanges() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", configDigest("abc"))
        assertNotEquals(configDigest("50"), configDigest("100"))
    }
    @Test fun staleUpdatesAreRejected() {
        val a = configDigest("A")
        val b = configDigest("B")
        assertTrue(canReplaceConfig("", "", "", ""))
        assertTrue(canReplaceConfig(v1, a, v1, a))
        assertFalse(canReplaceConfig(v1, a, v2, b))
        assertFalse(canReplaceConfig("", "", v2, b))
    }
}
