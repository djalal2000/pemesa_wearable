package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class ServerHistoryTest {
    @Test fun previousValuesAreDistinctAndBounded() {
        assertEquals(listOf("b", "c", "d"), previousFieldValues(listOf("b", "c", "d", "e"), "b", "a"))
        assertEquals(listOf("b", "d"), previousFieldValues(listOf("c", "b", "d"), "b", "c"))
        assertTrue(previousFieldValues(emptyList(), "a", "a").isEmpty())
    }
}
