package com.bharathvishal.messagecommunicationusingwearabledatalayer

import java.net.URI

internal val DEFAULT_SENSOR_KEYS = setOf("accelerometer", "gyroscope", "gravity", "linear", "rotation", "heart", "steps", "light", "pressure", "fatigue")
internal val SENSOR_KEYS = DEFAULT_SENSOR_KEYS.toList() + "magnetometer"

internal fun validServerUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null &&
        (uri.port == -1 || uri.port in 1..65535)
}.getOrDefault(false)

internal fun validSensorSelection(keys: Set<String>): Boolean =
    keys.isNotEmpty() && keys.all { it in SENSOR_KEYS }

internal fun validAcquisition(hz: Int, interval: Int): Boolean =
    hz in setOf(25, 50, 100) && interval in setOf(5, 10, 20) && hz * interval <= 2000

internal fun batchSizeFor(hz: Int, interval: Int): Int {
    require(validAcquisition(hz, interval))
    return hz * interval
}
