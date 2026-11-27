package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class RegisteredWatch(val node: String, val id: String, val name: String, val lastSeen: Long)

internal fun selectedReachableNode(selected: String?, pinned: String?, reachable: Collection<String>): String? =
    (pinned ?: selected)?.takeIf { it in reachable }

/** Configuration only. A pending or uncertain Start pins its destination across activity recreation. */
internal class WatchRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pemesa_watches_v1", Context.MODE_PRIVATE)
    companion object { private val lock = Any() }
    val selected: String? get() = prefs.getString("selected", null)
    val pinned: String? get() = prefs.getString("pinned", null)
    val target: String? get() = pinned ?: selected
    fun list(): List<RegisteredWatch> = synchronized(lock) {
        val array = runCatching { JSONArray(prefs.getString("watches", "[]")) }.getOrElse { JSONArray() }
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val obj = array.getJSONObject(index)
                RegisteredWatch(obj.getString("node"), obj.getString("id"), obj.getString("name"), obj.optLong("seen"))
            }.getOrNull()
        }
    }
    private fun encoded(items: List<RegisteredWatch>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("node", it.node).put("id", it.id).put("name", it.name).put("seen", it.lastSeen)) }
    }.toString()
    fun register(node: String, id: String, name: String): Boolean = synchronized(lock) {
        if (pinned != null || node.isBlank() || runCatching { java.util.UUID.fromString(id) }.isFailure) return false
        val old = list()
        val same = old.firstOrNull { it.id == id }
        val item = RegisteredWatch(node, id, same?.name ?: name.take(60), System.currentTimeMillis())
        val next = old.filterNot { it.node == node || it.id == id } + item
        if (next.size > 50) return false
        prefs.edit().putString("watches", encoded(next)).putString("selected", node).commit()
    }
    fun select(node: String): Boolean = synchronized(lock) {
        pinned == null && list().any { it.node == node } && prefs.edit().putString("selected", node).commit()
    }
    fun rename(node: String, name: String): Boolean = synchronized(lock) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 60 || trimmed.any(Char::isISOControl)) return false
        prefs.edit().putString("watches", encoded(list().map { if (it.node == node) it.copy(name = trimmed) else it })).commit()
    }
    fun seen(node: String) = synchronized(lock) {
        val items = list()
        if (items.any { it.node == node }) prefs.edit().putString("watches", encoded(items.map {
            if (it.node == node) it.copy(lastSeen = System.currentTimeMillis()) else it
        })).apply()
    }
    fun forget(node: String): Boolean = synchronized(lock) {
        if (pinned != null) return false
        val editor = prefs.edit().putString("watches", encoded(list().filterNot { it.node == node }))
        if (selected == node) editor.remove("selected")
        editor.commit()
    }
    fun pin(node: String): Boolean = synchronized(lock) {
        if (selected != node || (pinned != null && pinned != node)) return false
        prefs.edit().putString("pinned", node).commit()
    }
    fun release(node: String): Boolean = synchronized(lock) {
        if (pinned == null) return true
        pinned == node && prefs.edit().remove("pinned").commit()
    }
}
