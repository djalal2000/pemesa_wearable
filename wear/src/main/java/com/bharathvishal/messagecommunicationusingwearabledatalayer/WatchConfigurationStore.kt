package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class StoredConfiguration(val version: String, val hash: String, val payload: String) {
    fun json(): JSONObject = JSONObject(payload)
}

internal fun validConfigPayload(payload: String): Boolean = runCatching {
    require(payload.length in 1..16384)
    val json = JSONObject(payload)
    val array = json.getJSONArray("sensors")
    val keys = (0 until array.length()).map { array.getString(it) }.toSet()
    require(keys.size == array.length() && validSensorSelection(keys))
    require(validAcquisition(json.getInt("hz"), json.getInt("interval")))
    if (json.has("server")) {
        val server = json.getJSONObject("server")
        val a = server.getString("activity")
        val w = server.getString("wearable")
        require(validServerUrl(server.getString("url")) && server.getString("org").isNotBlank() &&
            a.isNotBlank() && w.isNotBlank() && a != w && server.getString("token").isNotBlank())
    }
    true
}.getOrDefault(false)

/** Only configuration is persisted. The complete record is authenticated and encrypted at rest. */
internal class WatchConfigurationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pemesa_config_sync_v1", Context.MODE_PRIVATE)
    private val alias = "pemesa_watch_config_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun load(owner: String): StoredConfiguration? = runCatching {
        val parts = (prefs.getString("encrypted", null) ?: return null).split(':')
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val obj = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        require(obj.getString("owner") == owner)
        val version = obj.getString("version")
        val hash = obj.getString("hash")
        val payload = obj.getString("payload")
        require(validConfigVersion(version) && configDigest(payload) == hash && validConfigPayload(payload))
        StoredConfiguration(version, hash, payload)
    }.getOrNull()
    @Synchronized fun save(owner: String, value: StoredConfiguration): Boolean = runCatching {
        require(validConfigVersion(value.version) && configDigest(value.payload) == value.hash && validConfigPayload(value.payload))
        val obj = JSONObject().put("owner", owner).put("version", value.version).put("hash", value.hash).put("payload", value.payload)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(obj.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        if (!prefs.edit().putString("encrypted", encoded).commit()) return false
        val verified = load(owner) ?: return false
        configMatches(value.version, value.hash, verified.version, verified.hash)
    }.getOrDefault(false)
}
