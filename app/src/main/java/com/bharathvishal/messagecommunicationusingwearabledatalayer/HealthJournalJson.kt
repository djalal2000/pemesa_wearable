package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.json.JSONArray
import org.json.JSONObject

internal fun List<HealthEvent>.toHealthJson(): JSONArray = JSONArray().also { array ->
    takeLast(500).forEach { event ->
        array.put(JSONObject().put("time", event.time).put("code", event.code.name)
            .put("value", event.value ?: JSONObject.NULL).put("count", event.count))
    }
}

internal fun parseHealthJson(array: JSONArray?): List<HealthEvent> {
    if (array == null) return emptyList()
    return (maxOf(0, array.length() - 500) until array.length()).mapNotNull { index ->
        runCatching {
            val item = array.getJSONObject(index)
            val code = HealthCode.valueOf(item.getString("code"))
            val time = item.getLong("time")
            val count = item.getLong("count")
            require(time > 0 && count > 0)
            HealthEvent(time, code, if (item.isNull("value")) null else item.getLong("value"), count)
        }.getOrNull()
    }
}
