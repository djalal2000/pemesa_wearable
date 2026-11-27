package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.bharathvishal.messagecommunicationusingwearabledatalayer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var communicationManager: WearCommunicationManager

    private val permissions = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION
    )

    private val permissionRequestCode = 1001

    private var backPressCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val resetBackPressRunnable = Runnable { backPressCount = 0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.keepScreenOn = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        communicationManager = WearCommunicationManager(this, binding)
        binding.pairPhoneButton.setOnClickListener { communicationManager.showPairingCode() }

        listAllSensors()

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, permissions, permissionRequestCode)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        communicationManager.registerListeners()
    }

    override fun onPause() {
        super.onPause()
        communicationManager.unregisterListeners()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        communicationManager.close()
        super.onDestroy()
    }

    private fun hasPermissions(): Boolean {
        return permissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            if (!hasPermissions()) {
                finish()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBackPress()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBackPress()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleBackPress() {
        backPressCount++
        if (backPressCount >= 3) {
            finish()
        } else {
            val remaining = 3 - backPressCount
            Toast.makeText(
                this,
                "Press $remaining more times to exit",
                Toast.LENGTH_SHORT
            ).show()
            handler.removeCallbacks(resetBackPressRunnable)
            handler.postDelayed(resetBackPressRunnable, 1000)
        }
    }

    private fun listAllSensors() {
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)

        sensors.forEach {
            Log.d("SensorList", "Sensor: ${it.name}, Type: ${it.type}")
        }
    }
}
