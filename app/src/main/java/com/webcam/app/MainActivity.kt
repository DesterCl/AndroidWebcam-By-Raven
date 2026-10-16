package com.webcam.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtsp.RtspCamera2

class MainActivity : AppCompatActivity(), SensorEventListener, ConnectChecker {

    private lateinit var rtspCamera: RtspCamera2
    private lateinit var btnToggle: Button
    private lateinit var btnRotate: Button
    private lateinit var btnFlash: Button
    private lateinit var btnAudio: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvOrientation: TextView
    private lateinit var spinnerQuality: Spinner
    private lateinit var spinnerCamera: Spinner
    private lateinit var seekZoom: SeekBar
    private lateinit var tvZoom: TextView
    private lateinit var sensorMgr: SensorManager
    private var accelerometer: Sensor? = null
    private var deviceRotation = 0
    private var streaming = false
    private var audioEnabled = false
    private var flashOn = false
    private var manualRotationOffset = 0
    private var isFrontCamera = false
    private val RTSP_PORT = 1935
    private val resolutions = listOf(
        Triple("4K (3840x2160)",      3840, 2160),
        Triple("2K (2560x1440)",      2560, 1440),
        Triple("Full HD (1920x1080)", 1920, 1080),
        Triple("HD (1280x720)",       1280,  720),
        Triple("480p (640x480)",       640,  480)
    )

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        runOnUiThread {
            tvStatus.text = "🟢 Transmitiendo"
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        }
    }
    override fun onConnectionFailed(reason: String) {
        runOnUiThread { tvStatus.text = "❌ Error: $reason" }
        if (!rtspCamera.getStreamClient().reTry(5000, reason, null))
            runOnUiThread { stopStream() }
    }
    override fun onDisconnect() {
        runOnUiThread { tvStatus.text = "🟡 Desconectado" }
    }
    override fun onAuthError() {
        runOnUiThread { tvStatus.text = "❌ Error autenticación" }
    }
    override fun onAuthSuccess() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle      = findViewById(R.id.btnToggle)
        btnRotate      = findViewById(R.id.btnRotate)
        btnFlash       = findViewById(R.id.btnFlash)
        btnAudio       = findViewById(R.id.btnAudio)
        tvStatus       = findViewById(R.id.tvStatus)
        tvUrl          = findViewById(R.id.tvUrl)
        tvFps          = findViewById(R.id.tvFps)
        tvOrientation  = findViewById(R.id.tvOrientation)
        spinnerQuality = findViewById(R.id.spinnerQuality)
        spinnerCamera  = findViewById(R.id.spinnerCamera)
        seekZoom       = findViewById(R.id.seekZoom)
        tvZoom         = findViewById(R.id.tvZoom)

        rtspCamera = RtspCamera2(this, this)
        rtspCamera.getStreamClient().setReTries(10)

        setupSpinners()
        sensorMgr     = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        btnToggle.setOnClickListener { if (streaming) stopStream() else startStream() }
        btnRotate.setOnClickListener {
            manualRotationOffset = (manualRotationOffset + 90) % 360
        }
        btnFlash.setOnClickListener {
            flashOn = !flashOn
            if (flashOn) rtspCamera.enableLantern() else rtspCamera.disableLantern()
            btnFlash.text = if (flashOn) "🔦 Flash ON" else "🔦 Flash OFF"
        }
        btnAudio.setOnClickListener {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                return@setOnClickListener
            }
            toggleAudio()
        }
        seekZoom.max = 100
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                tvZoom.text = "Zoom: ${p}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        checkPermissions()
    }

    private fun setupSpinners() {
        spinnerQuality.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            resolutions.map { it.first })
        spinnerQuality.setSelection(2)

        spinnerCamera.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Trasera", "Frontal"))

        spinnerCamera.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                isFrontCamera = pos == 1
                if (!streaming) {
                    if (rtspCamera.isOnPreview) rtspCamera.stopPreview()
                    rtspCamera.startPreview(
                        if (isFrontCamera) CameraHelper.Facing.FRONT
                        else CameraHelper.Facing.BACK)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

     private fun startStream() {
      if (!hasPerm(Manifest.permission.CAMERA)) {
          tvStatus.text = "⚠️ Permiso de cámara requerido"
         return
     }
      try {
          val (_, w, h) = resolutions[spinnerQuality.selectedItemPosition]
          val bitrate = when {
            w >= 3840 -> 20_000_000
            w >= 2560 -> 12_000_000
            w >= 1920 ->  6_000_000
            w >= 1280 ->  3_000_000
            else      ->  1_500_000
          }
          val rot = (deviceRotation + manualRotationOffset) % 360

          // Intentar con resolución seleccionada, fallback a 720p
          var prepared = rtspCamera.prepareVideo(w, h, 30, bitrate, rot)
          if (!prepared) prepared = rtspCamera.prepareVideo(1280, 720, 30, 3_000_000, rot)
          if (!prepared) {
              tvStatus.text = "❌ No se pudo preparar video"
              return
          }

          rtspCamera.disableAudio()

          rtspCamera.startStream("rtsp://0.0.0.0:$RTSP_PORT/stream")

          val svc = Intent(this, StreamService::class.java)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
          else startService(svc)

          val ip = getLocalIp()
          tvUrl.text          = "rtsp://$ip:$RTSP_PORT/stream"
          tvUrl.visibility    = View.VISIBLE
          tvStatus.text       = "🟡 Iniciando..."
          tvStatus.setTextColor(getColor(android.R.color.holo_orange_light))
          btnToggle.text      = "Detener"
          btnFlash.visibility = View.VISIBLE
          seekZoom.visibility = View.VISIBLE
          tvZoom.visibility   = View.VISIBLE
          streaming = true

      } catch (e: Exception) {
          tvStatus.text = "❌ ${e.message}"
          streaming = false
        }
      }
          btnAudio.text = if (audioEnabled) "🎙️ Audio ON" else "🎙️ Audio OFF"
      }

    private fun getLocalIp(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && !it.hostAddress.contains(':') }
                ?.hostAddress ?: "?.?.?.?"
        } catch (e: Exception) { "?.?.?.?" }
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
        val label = when (newRot) {
            0   -> "📱 Vertical"
            180 -> "📱 Invertido"
            90  -> "📱 Horizontal ←"
            270 -> "📱 Horizontal →"
            else -> "📱 --"
        }
        runOnUiThread { tvOrientation.text = label }
     }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { !hasPerm(it) }
        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 101 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED)
            toggleAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (streaming) stopStream()
        if (rtspCamera.isOnPreview) rtspCamera.stopPreview()
    }
}
