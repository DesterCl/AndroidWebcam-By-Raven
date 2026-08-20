package com.webcam.app

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var camStream: CameraStreamManager
    private lateinit var server: MjpegHttpServer
    private lateinit var btnToggle: Button
    private lateinit var btnRotate: Button
    private lateinit var btnFlash: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvOrientation: TextView
    private lateinit var spinnerQuality: Spinner
    private lateinit var spinnerCamera: Spinner    // ahora lista TODAS las cámaras
    private lateinit var seekZoom: SeekBar
    private lateinit var tvZoom: TextView
    private lateinit var sensorMgr: SensorManager
    private var accelerometer: Sensor? = null
    private var deviceRotation = 0
    private var streaming = false
    private var flashOn = false
    private val PORT = 8080

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle    = findViewById(R.id.btnToggle)
        btnRotate    = findViewById(R.id.btnRotate)
        btnFlash     = findViewById(R.id.btnFlash)
        tvStatus     = findViewById(R.id.tvStatus)
        tvUrl        = findViewById(R.id.tvUrl)
        tvFps        = findViewById(R.id.tvFps)
        tvOrientation= findViewById(R.id.tvOrientation)
        spinnerQuality = findViewById(R.id.spinnerQuality)
        spinnerCamera  = findViewById(R.id.spinnerCamera)
        seekZoom     = findViewById(R.id.seekZoom)
        tvZoom       = findViewById(R.id.tvZoom)

        server = MjpegHttpServer(PORT)
        camStream = CameraStreamManager(
            context = this,
            server  = server,
            onFpsUpdate    = { fps -> runOnUiThread { tvFps.text = "FPS: $fps" } },
            onErrorMessage = { msg -> runOnUiThread {
                tvStatus.text = "❌ $msg"
                tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }}
        )

        sensorMgr     = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setupQualitySpinner()
        checkPermissions()

        btnToggle.setOnClickListener { if (streaming) stopStream() else startStream() }

        // Girar imagen 90° en sentido horario cada vez que se pulsa
        btnRotate.setOnClickListener {
            camStream.rotateManual(90)
        }

        // Flash on/off
        btnFlash.setOnClickListener {
            flashOn = !flashOn
            camStream.setFlash(flashOn)
            btnFlash.text = if (flashOn) "🔦 Flash ON" else "🔦 Flash OFF"
        }

        // Zoom
        seekZoom.max = 100
        seekZoom.progress = 0
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!streaming) return
                val maxZ = camStream.getMaxZoom().coerceAtLeast(1f)
                val zoom = 1f + (progress / 100f) * (maxZ - 1f)
                camStream.setZoom(zoom)
                tvZoom.text = "Zoom: ${"%.1f".format(zoom)}x"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorMgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorMgr.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]
        val newRot = when {
            y >  5f -> 0;   y < -5f -> 180
            x >  5f -> 270; x < -5f -> 90
            else -> deviceRotation
        }
        if (newRot == deviceRotation) return
        deviceRotation = newRot
        camStream.setDeviceRotation(deviceRotation)
        val label = when (newRot) {
            0   -> "📱 Vertical"
            180 -> "📱 Invertido"
            90  -> "📱 Horizontal ←"
            270 -> "📱 Horizontal →"
            else -> "📱 --"
        }
        runOnUiThread { tvOrientation.text = label }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun setupQualitySpinner() {
        spinnerQuality.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("4K (3840x2160)", "2K (2560x1440)", "Full HD (1920x1080)", "HD (1280x720)", "480p (640x480)")
        )
        spinnerQuality.setSelection(2)
    }

    private fun populateCameraSpinner() {
        val cameras = camStream.scanCameras()
        val labels  = cameras.map { it.label }
        spinnerCamera.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private fun startStream() {
        val res = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 640 to 480)
        val (w, h) = res[spinnerQuality.selectedItemPosition]

        // Usar la cámara seleccionada por ID directo
        val cameras = camStream.availableCameras
        val camId   = if (cameras.isNotEmpty() && spinnerCamera.selectedItemPosition < cameras.size)
            cameras[spinnerCamera.selectedItemPosition].id
        else null

        server.start()
        if (camId != null) camStream.startCameraById(camId, w, h)
        else               camStream.startCamera(w, h, false)

        // Actualizar UI de flash/zoom según la cámara elegida
        runOnUiThread {
            btnFlash.visibility = if (camStream.hasFlash()) View.VISIBLE else View.GONE
            val maxZ = camStream.getMaxZoom()
            seekZoom.visibility = if (maxZ > 1f) View.VISIBLE else View.GONE
            tvZoom.visibility   = if (maxZ > 1f) View.VISIBLE else View.GONE
            seekZoom.progress   = 0
            tvZoom.text = "Zoom: 1.0x"
            flashOn = false
            btnFlash.text = "🔦 Flash OFF"
        }

        val ip = NetworkUtils.getLocalIpAddress(this)
        tvUrl.text = "URL: http://$ip:$PORT"
        tvUrl.visibility = View.VISIBLE
        tvStatus.text = "🟢 Transmitiendo"
        tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        btnToggle.text = "Detener"
        streaming = true
    }

    private fun stopStream() {
        camStream.stopCamera(); server.stop()
        tvUrl.visibility = View.GONE
        tvStatus.text = "🔴 Detenido"
        tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        btnToggle.text = "Iniciar Stream"
        tvFps.text = "FPS: --"
        seekZoom.progress = 0
        tvZoom.text = "Zoom: 1.0x"
        flashOn = false
        btnFlash.text = "🔦 Flash OFF"
        streaming = false
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        } else {
            // Permisos ya concedidos: escanear cámaras ahora
            populateCameraSpinner()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.any { it != PackageManager.PERMISSION_GRANTED })
            tvStatus.text = "⚠️ Permiso de cámara requerido"
        else
            populateCameraSpinner()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (streaming) stopStream()
    }
}
