package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class AdvancedCollectionRulesTest {
    @Test fun originalDefaultsStayUnchanged() {
        assertEquals(500, batchSizeFor(50, 10))
        assertEquals(125, batchSizeFor(25, 5))
        assertEquals(2000, batchSizeFor(100, 20))
        assertFalse(validAcquisition(0, 10))
        assertFalse(validAcquisition(100, 60))
    }
    @Test fun diagnosesAuthorizationAndNetworkErrors() {
        assertEquals("OK", diagnosticHttpIssue(204))
        assertEquals("AUTH", diagnosticHttpIssue(401))
        assertEquals("AUTH", diagnosticHttpIssue(403))
        assertEquals("BUCKET", diagnosticHttpIssue(404))
        assertEquals("SERVER", diagnosticHttpIssue(429))
        assertEquals("SERVER", diagnosticHttpIssue(503))
    }
}
