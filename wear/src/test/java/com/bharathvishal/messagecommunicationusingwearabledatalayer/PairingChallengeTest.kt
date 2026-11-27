package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test
class PairingChallengeTest {
    @Test fun codeIsSixDigitsAndSingleUse() {
        val challenge = PairingChallenge(clock = { 1000L })
        val code = challenge.open()
        assertTrue(code.matches(Regex("[0-9]{6}")))
        assertTrue(challenge.verify(code))
        assertFalse(challenge.verify(code))
    }
    @Test fun expiryAndAttemptLimit() {
        var now = 0L
        val challenge = PairingChallenge(clock = { now })
        val code = challenge.open()
        now = 120000L
        assertFalse(challenge.verify(code))
        val second = challenge.open()
        repeat(5) { assertFalse(challenge.verify("invalid")) }
        assertFalse(challenge.verify(second))
    }
    @Test fun cancelDisablesAssociation() {
        val challenge = PairingChallenge(clock = { 0L })
        val code = challenge.open()
        challenge.cancel()
        assertFalse(challenge.verify(code))
    }
}
