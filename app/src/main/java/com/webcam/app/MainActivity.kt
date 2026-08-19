package com.webcam.app

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var cameraManager: CameraStreamManager
    private lateinit var mjpegServer: MjpegHttpServer
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var spinnerQuality: Spinner
    private lateinit var spinnerCamera: Spinner
    private lateinit var tvFps: TextView
    private lateinit var tvOrientation: TextView

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var currentDeviceRotation = 0

    private var isStreaming = false
    private val PORT = 8080

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvUrl = findViewById(R.id.tvUrl)
        spinnerQuality = findViewById(R.id.spinnerQuality)
        spinnerCamera = findViewById(R.id.spinnerCamera)
        tvFps = findViewById(R.id.tvFps)
        tvOrientation = findViewById(R.id.tvOrientation)

        mjpegServer = MjpegHttpServer(PORT)
        cameraManager = CameraStreamManager(this, mjpegServer) { fps ->
            runOnUiThread { tvFps.text = "FPS: $fps" }
        }

        // Sensor de acelerómetro para detectar orientación
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setupSpinners()
        checkPermissions()

        btnToggle.setOnClickListener {
            if (isStreaming) stopStreaming() else startStreaming()
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]

        val newRotation = when {
            y > 5  -> 0    // Vertical normal
            y < -5 -> 180  // Vertical invertido
            x > 5  -> 270  // Horizontal izquierda
            x < -5 -> 90   // Horizontal derecha
            else   -> currentDeviceRotation // Sin cambio
        }

        if (newRotation != currentDeviceRotation) {
            currentDeviceRotation = newRotation
            cameraManager.setDeviceRotation(currentDeviceRotation)

            val label = when (newRotation) {
                0   -> "📱 Vertical"
                180 -> "📱 Vertical invertido"
                90  -> "📱 Horizontal ←"
