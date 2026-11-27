package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class WriteRecoveryTest {
    @Test fun recoveryUsesBoundedExponentialBackoffAndHonorsServerDelay() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L),
            (0..6).map { recoveryDelayMs(it, WRITE_RETRY_BUDGET_MS) })
        assertNull(recoveryDelayMs(0, 999L))
        assertNull(recoveryDelayMs(3, 8000L))
        assertEquals(120000L, retryDelayMs(503, "120", 1))
        assertNull(retryDelayMs(503, "120", 1, 100000L))
        assertEquals(60000L, retryDelayMs(429, "Thu, 01 Jan 1970 00:01:00 GMT", 0, 300000L, 0L))
        assertNull(retryDelayMs(401, "1", 0))
    }
}
