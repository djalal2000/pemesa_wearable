package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test
class WatchSelectionTest {
    @Test fun noFallbackToAnotherWatch() {
        assertNull(selectedReachableNode("A", null, listOf("B")))
        assertNull(selectedReachableNode(null, null, listOf("A")))
        assertEquals("A", selectedReachableNode("A", null, listOf("B", "A")))
    }
    @Test fun pinnedSessionAlwaysWins() {
        assertEquals("A", selectedReachableNode("B", "A", listOf("A", "B")))
        assertNull(selectedReachableNode("B", "A", listOf("B")))
    }
}
