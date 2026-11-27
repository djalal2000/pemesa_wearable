package com.bharathvishal.messagecommunicationusingwearabledatalayer

import java.security.SecureRandom

/** One-use, time-limited association challenge. No code is persisted or logged. */
internal class PairingChallenge(private val clock: () -> Long, private val random: SecureRandom = SecureRandom()) {
    private var code: String? = null
    private var expires = 0L
    private var attempts = 0
    @Synchronized fun open(): String {
        val value = random.nextInt(1000000).toString().padStart(6, '0')
        code = value; expires = clock() + 120000L; attempts = 0
        return value
    }
    @Synchronized fun cancel() { code = null; expires = 0L }
    @Synchronized fun verify(candidate: String): Boolean {
        val expected = code ?: return false
        if (clock() >= expires || attempts >= 5) { cancel(); return false }
        attempts++
        if (candidate == expected) { cancel(); return true }
        if (attempts >= 5) cancel()
        return false
    }
}
