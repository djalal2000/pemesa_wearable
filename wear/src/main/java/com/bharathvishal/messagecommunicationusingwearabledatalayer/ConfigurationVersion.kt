package com.bharathvishal.messagecommunicationusingwearabledatalayer

import java.security.MessageDigest
import java.util.UUID

internal const val CONFIG_PATH = "/pemesa/config-v1"
internal fun configDigest(payload: String): String = MessageDigest.getInstance("SHA-256")
    .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun validConfigVersion(version: String): Boolean =
    version.length == 36 && runCatching { UUID.fromString(version).toString() == version }.getOrDefault(false)
internal fun validConfigDigest(hash: String): Boolean = hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }
internal fun configMatches(expectedVersion: String, expectedHash: String, actualVersion: String, actualHash: String): Boolean =
    validConfigVersion(expectedVersion) && validConfigDigest(expectedHash) &&
        expectedVersion == actualVersion && expectedHash == actualHash
internal fun canReplaceConfig(expectedVersion: String, expectedHash: String, actualVersion: String, actualHash: String): Boolean =
    if (actualVersion.isEmpty() && actualHash.isEmpty()) expectedVersion.isEmpty() && expectedHash.isEmpty()
    else configMatches(expectedVersion, expectedHash, actualVersion, actualHash)
