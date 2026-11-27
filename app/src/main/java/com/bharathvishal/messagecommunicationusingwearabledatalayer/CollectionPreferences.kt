package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import org.json.JSONArray

/** Only settings are persisted; no acquired measurements. */
internal class CollectionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pemesa_collection_v2", Context.MODE_PRIVATE)
    companion object { private val configLock = Any() }
    private fun nextVersion() = java.util.UUID.randomUUID().toString()
    private fun versionLocked(): String {
        val current = prefs.getString("config_version", null)
        if (current != null && validConfigVersion(current)) return current
        return nextVersion().also { check(prefs.edit().putString("config_version", it).commit()) }
    }
    fun version(): String = synchronized(configLock) { versionLocked() }
    val custom: Boolean get() = prefs.getBoolean("custom", false)
    val url: String get() = prefs.getString("url", "") ?: ""
    val organization: String get() = prefs.getString("org", "") ?: ""
    val activity: String get() = prefs.getString("activity", "activity") ?: "activity"
    val wearable: String get() = prefs.getString("wearable", "wearable") ?: "wearable"
    val sensors: Set<String> get() = prefs.getStringSet("sensors", DEFAULT_SENSOR_KEYS)?.toSet() ?: DEFAULT_SENSOR_KEYS
    val hasToken: Boolean get() = prefs.contains("token")
    val serverConfigured: Boolean get() = custom && hasToken && validServerUrl(url) && organization.isNotBlank() &&
        activity.isNotBlank() && wearable.isNotBlank() && activity != wearable
    val hz: Int get() = prefs.getInt("hz", 50)
    val interval: Int get() = prefs.getInt("interval", 10)
    val project: String get() = prefs.getString("project", "") ?: ""
    val experiment: String get() = prefs.getString("experiment", "") ?: ""
    val site: String get() = prefs.getString("site", "") ?: ""
    val protocol: String get() = prefs.getString("protocol", "") ?: ""
    val vibration: Boolean get() = prefs.getBoolean("vibration", false)
    fun saveAcquisition(hz: Int, interval: Int): Boolean = synchronized(configLock) {
        validAcquisition(hz, interval) && ((this.hz == hz && this.interval == interval) ||
            prefs.edit().putInt("hz", hz).putInt("interval", interval).putString("config_version", nextVersion()).commit())
    }
    fun saveStudy(project: String, experiment: String, site: String, protocol: String): Boolean =
        listOf(project, experiment, site, protocol).all { it.length <= 120 && it.none(Char::isISOControl) } &&
        prefs.edit().putString("project", project).putString("experiment", experiment)
            .putString("site", site).putString("protocol", protocol).commit()
    fun saveVibration(value: Boolean): Boolean = prefs.edit().putBoolean("vibration", value).commit()
    fun exportSettings(): JSONObject = JSONObject().put("version", 1)
        .put("hz", hz).put("interval", interval).put("sensors", JSONArray(sensors.toList()))
        .put("project", project).put("experiment", experiment).put("site", site).put("protocol", protocol)
        .put("vibration", vibration)
    fun importSettings(input: JSONObject): Boolean = synchronized(configLock) { runCatching {
        require(input.getInt("version") == 1)
        val frequency = input.getInt("hz")
        val seconds = input.getInt("interval")
        val array = input.getJSONArray("sensors")
        val keys = (0 until array.length()).map { array.getString(it) }.toSet()
        val study = listOf("project", "experiment", "site", "protocol").associateWith { input.getString(it) }
        require(validAcquisition(frequency, seconds) && validSensorSelection(keys))
        require(study.values.all { it.length <= 120 && it.none(Char::isISOControl) })
        prefs.edit().putInt("hz", frequency).putInt("interval", seconds)
            .putStringSet("sensors", keys).putBoolean("vibration", input.getBoolean("vibration"))
            .putString("config_version", nextVersion())
            .apply { study.forEach { (key, value) -> putString(key, value) } }.commit()
    }.getOrDefault(false) }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("pemesa_influx_v2", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pemesa_influx_v2", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(): String = decryptValue(requireNotNull(prefs.getString("token", null)))
    private fun decryptValue(encoded: String): String {
        val parts = encoded.split(":")
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    private fun activeTarget() = ServerTarget(url, organization, activity, wearable)

    private fun historyJson(): JSONObject = runCatching {
        val encoded = prefs.getString("server_history", null) ?: return JSONObject()
        val json = JSONObject(decryptValue(encoded))
        if (json.has("tokens")) {
            json.remove("tokens")
            check(prefs.edit().putString("server_history", encrypt(json.toString())).commit())
        }
        json
    }.getOrDefault(JSONObject())

    fun removeLegacyTokenHistory() {
        synchronized(configLock) { historyJson() }
    }

    private fun historyField(json: JSONObject, name: String): List<String> {
        val array = json.optJSONArray(name) ?: return emptyList()
        return (0 until minOf(array.length(), SERVER_HISTORY_LIMIT)).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }.distinct()
    }

    fun serverHistory(): ServerHistorySuggestions = synchronized(configLock) {
        val json = historyJson()
        ServerHistorySuggestions(SERVER_HISTORY_FIELDS.associateWith { historyField(json, it) })
    }

    fun clearServerHistory(): Boolean = synchronized(configLock) {
        prefs.edit().remove("server_history").commit()
    }

    fun saveServer(url: String, org: String, activity: String, wearable: String, token: String): Boolean = synchronized(configLock) { runCatching {
        require(validServerUrl(url) && org.isNotBlank() && activity.isNotBlank() && wearable.isNotBlank() && activity != wearable)
        val target = ServerTarget(url, org, activity, wearable)
        val oldTarget = activeTarget().takeIf { custom }
        val oldToken = if (hasToken) runCatching { decrypt() }.getOrNull() else null
        val history = historyJson()
        val selected = when {
            token.isNotBlank() -> token
            oldTarget == target -> requireNotNull(oldToken)
            else -> error("A token is required for a different destination.")
        }
        require(selected.isNotBlank())
        val updated = JSONObject()
        SERVER_HISTORY_FIELDS.forEachIndexed { index, field ->
            updated.put(field, JSONArray(previousFieldValues(historyField(history, field),
                oldTarget?.fields()?.get(index), target.fields()[index])))
        }
        prefs.edit().putBoolean("custom", true).putString("url", url).putString("org", org)
            .putString("activity", activity).putString("wearable", wearable).putString("token", encrypt(selected))
            .putString("server_history", encrypt(updated.toString()))
            .putString("config_version", nextVersion()).commit()
    }.getOrDefault(false) }
    fun resetServer(): Boolean = synchronized(configLock) {
        prefs.edit().remove("custom").remove("url").remove("org")
            .remove("activity").remove("wearable").remove("token").putString("config_version", nextVersion()).commit()
    }
    fun saveSensors(keys: Set<String>): Boolean = synchronized(configLock) {
        validSensorSelection(keys) && (sensors == keys ||
            prefs.edit().putStringSet("sensors", keys.toSet()).putString("config_version", nextVersion()).commit())
    }
    fun configuration(): ConfigSnapshot = synchronized(configLock) {
        require(validSensorSelection(sensors) && validAcquisition(hz, interval))
        val json = JSONObject().put("sensors", JSONArray(sensors.sorted())).put("hz", hz).put("interval", interval)
        if (custom) {
            require(serverConfigured)
            val token = decrypt()
            require(token.isNotBlank())
            json.put("server", JSONObject().put("url", url).put("org", organization)
                .put("activity", activity).put("wearable", wearable).put("token", token))
        }
        val payload = json.toString()
        ConfigSnapshot(versionLocked(), configDigest(payload), payload)
    }
    fun envelope(session: String): JSONObject {
        require(validSensorSelection(sensors))
        require(validAcquisition(hz, interval))
        val result = JSONObject().put("session", session).put("sensors", JSONArray(sensors.toList()))
            .put("hz", hz).put("interval", interval)
        if (custom) result.put("server", JSONObject().put("url", url).put("org", organization)
            .put("activity", activity).put("wearable", wearable).put("token", decrypt()))
        return result
    }
}
