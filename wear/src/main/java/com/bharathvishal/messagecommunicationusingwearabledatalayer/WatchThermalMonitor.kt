package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.widget.Toast

/** Monitor Android thermal status without modifying acquisition settings. */
internal class WatchThermalMonitor(private val context: Context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var cleanup: (() -> Unit)? = null
    var status: Int = -1
        private set

    fun start() {
        if (Build.VERSION.SDK_INT < 29 || cleanup != null) return
        try {
            val listener = PowerManager.OnThermalStatusChangedListener { level -> update(level) }
            power.addThermalStatusListener(context.mainExecutor, listener)
            cleanup = { power.removeThermalStatusListener(listener) }
            update(power.currentThermalStatus)
        } catch (_: Exception) {
            close()
            status = -1
        }
    }

    private fun update(level: Int) {
        if (level !in 0..6 || level == status || cleanup == null) return
        val previous = status
        status = level
        when {
            level >= 4 && previous < 4 -> {
                HealthJournal.add(HealthCode.THERMAL_CRITICAL, level.toLong())
                Toast.makeText(context, "PEMESA: critical thermal state. Stop safely and let the watch cool.", Toast.LENGTH_LONG).show()
            }
            level >= 3 && previous < 3 -> {
                HealthJournal.add(HealthCode.THERMAL_HIGH, level.toLong())
                Toast.makeText(context, "PEMESA: watch overheating. Let it cool.", Toast.LENGTH_LONG).show()
            }
            level < 3 && previous >= 3 -> HealthJournal.add(HealthCode.THERMAL_RECOVERED, level.toLong())
        }
    }

    fun close() {
        val remove = cleanup
        cleanup = null
        if (remove != null) runCatching { remove() }
    }
}
