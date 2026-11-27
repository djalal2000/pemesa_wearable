package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.app.Application
import com.google.android.material.color.DynamicColors

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Apply dynamic colors on Android 12 and later.
        DynamicColors.applyToActivitiesIfAvailable(this)
        Thread { CollectionPreferences(this).removeLegacyTokenHistory() }.start()
    }
}