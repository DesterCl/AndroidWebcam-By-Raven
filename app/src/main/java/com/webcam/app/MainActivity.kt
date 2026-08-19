package com.webcam.app

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
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
        cameraManager = CameraStreamManager(this, mjpegServer,
            onFpsUpdate = { fps ->
                runOnUiThread { tvFps.text = "FPS: $fps" }
            },
            onErrorMessage = { msg ->
                runOnUiThread {
                    tvStatus.text = "❌ $msg"
                    tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                }
            }
        )

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
            y > 5  -> 0
            y < -5 -> 180
            x > 5  -> 270
            x < -5 -> 90
            else   -> currentDeviceRotation
        }

        if (newRotation != currentDeviceRotation) {
            currentDeviceRotation = newRotation
            cameraManager.setDeviceRotation(currentDeviceRotation)
            val label = when (newRotation) {
                0   -> "📱 Vertical"
                180 -> "📱 Vertical invertido"
                90  -> "📱 Horizontal ←"
                270 -> "📱 Horizontal →"
                else -> "📱 --"
            }
            runOnUiThread { tvOrientation.text = label }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun setupSpinners() {
        val qualities = arrayOf("4K (3840x2160)", "2K (2560x1440)", "Full HD (1920x1080)", "HD (1280x720)", "480p (640x480)")
        spinnerQuality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualities)
        spinnerQuality.setSelection(2)

        val cameras = arrayOf("Cámara Trasera", "Cámara Frontal")
        spinnerCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameras)
    }

    private fun startStreaming() {
        val qualityIndex = spinnerQuality.selectedItemPosition
        val useFrontCamera = spinnerCamera.selectedItemPosition == 1
        val resolutions = listOf(
            Pair(3840, 2160), Pair(2560, 1440), Pair(1920, 1080), Pair(1280, 720), Pair(640, 480)
        )
        val (width, height) = resolutions[qualityIndex]

        mjpegServer.start()
        cameraManager.startCamera(width, height, useFrontCamera)

        val ip = NetworkUtils.getLocalIpAddress(this)
        val url = "http://$ip:$PORT"

        tvUrl.text = "URL: $url"
        tvUrl.visibility = View.VISIBLE
        tvStatus.text = "🟢 Transmitiendo"
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        btnToggle.text = "Detener"
        isStreaming = true
    }

    private fun stopStreaming() {
        cameraManager.stopCamera()
        mjpegServer.stop()

        tvUrl.visibility = View.GONE
        tvStatus.text = "🔴 Detenido"
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        btnToggle.text = "Iniciar Stream"
        tvFps.text = "FPS: --"
        isStreaming = false
    }

    private fun checkPermissions() {
        val permissions = arrayOf(Manifest.permission.CAMERA)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED })
            tvStatus.text = "⚠️ Se requiere permiso de cámara"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isStreaming) stopStreaming()
    }
}
