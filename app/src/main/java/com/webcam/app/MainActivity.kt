package com.webcam.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.input.video.CameraOpenException
import com.pedro.library.rtsp.RtspCamera2
import com.pedro.library.util.streamclient.RtspStreamClient
import com.pedro.rtsp.rtsp.VideoCodec
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var rtspCamera: RtspCamera2
    private lateinit var surfaceView: SurfaceView
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
    private var currentCameraFacing = CameraHelper.Facing.BACK

    private val RTSP_PORT = 1935
    private val cameras = mutableListOf<Pair<String, CameraHelper.Facing>>()

    // Resoluciones disponibles
    private val resolutions = listOf(
        Triple("4K (3840x2160)",   3840, 2160),
        Triple("2K (2560x1440)",   2560, 1440),
        Triple("Full HD (1920x1080)", 1920, 1080),
        Triple("HD (1280x720)",    1280, 720),
        Triple("480p (640x480)",    640,  480)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView    = findViewById(R.id.surfaceView)
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

        setupSpinners()

        // RtspCamera2: servidor RTSP integrado, H.264 por hardware
        rtspCamera = RtspCamera2(surfaceView, object : com.pedro.library.base.recording.BaseRecordController.Listener {
            override fun onStatusChange(status: com.pedro.library.base.recording.RecordController.Status) {}
        })

        rtspCamera.setReTries(10) // reconectar hasta 10 veces si hay error

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                if (!rtspCamera.isStreaming) {
                    rtspCamera.startPreview(currentCameraFacing)
                }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (rtspCamera.isStreaming) rtspCamera.stopStream()
                rtspCamera.stopPreview()
            }
        })

        sensorMgr     = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        btnToggle.setOnClickListener { if (streaming) stopStream() else startStream() }

        btnRotate.setOnClickListener {
            manualRotationOffset = (manualRotationOffset + 90) % 360
            applyRotation()
        }

        btnFlash.setOnClickListener {
            flashOn = !flashOn
            rtspCamera.enableLantern(flashOn)
            btnFlash.text = if (flashOn) "🔦 Flash ON" else "🔦 Flash OFF"
        }

        btnAudio.setOnClickListener {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                return@setOnClickListener
            }
            audioEnabled = !audioEnabled
            if (audioEnabled) rtspCamera.enableAudio() else rtspCamera.disableAudio()
            btnAudio.text = if (audioEnabled) "🎙️ Audio ON" else "🎙️ Audio OFF"
        }

        seekZoom.max = 100
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val maxZ = rtspCamera.maxZoom
                val zoom = 1f + (p / 100f) * (maxZ - 1f)
                rtspCamera.setZoom(zoom)
                tvZoom.text = "Zoom: ${"%.1f".format(zoom)}x"
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
        spinnerQuality.setSelection(2) // Full HD por defecto

        // Detectar cámaras disponibles
        cameras.clear()
        cameras.add(Pair("Trasera (principal)", CameraHelper.Facing.BACK))
        cameras.add(Pair("Frontal", CameraHelper.Facing.FRONT))
        spinnerCamera.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            cameras.map { it.first })

        spinnerCamera.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentCameraFacing = cameras[pos].second
                if (!streaming) {
                    rtspCamera.stopPreview()
                    rtspCamera.startPreview(currentCameraFacing)
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
        val (_, w, h) = resolutions[spinnerQuality.selectedItemPosition]

        // Bitrate adaptativo según resolución (en bps)
        val bitrate = when {
            w >= 3840 -> 20_000_000
            w >= 2560 -> 12_000_000
            w >= 1920 ->  6_000_000
            w >= 1280 ->  3_000_000
            else      ->  1_500_000
        }

        val prepared = if (audioEnabled) {
            rtspCamera.prepareAudio(128_000, 44100, true) &&
            rtspCamera.prepareVideo(w, h, 30, bitrate, 0)
        } else {
            rtspCamera.disableAudio()
            rtspCamera.prepareVideo(w, h, 30, bitrate, 0)
        }

        if (!prepared) {
            // Fallback a 1080p si la resolución pedida no es soportada
            val ok = rtspCamera.prepareVideo(1920, 1080, 30, 6_000_000, 0)
            if (!ok) { tvStatus.text = "❌ No se pudo preparar la cámara"; return }
        }

        applyRotation()

        // Iniciar servidor RTSP en el puerto configurado
        rtspCamera.startStream("rtsp://0.0.0.0:$RTSP_PORT/stream")

        // Iniciar servicio en segundo plano con WakeLock
        val svcIntent = Intent(this, StreamService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent)
        else
            startService(svcIntent)

        val ip = getLocalIp()
        tvUrl.text = "rtsp://$ip:$RTSP_PORT/stream"
        tvUrl.visibility = View.VISIBLE
        tvStatus.text = "🟢 Transmitiendo"
        tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        btnToggle.text = "Detener"
        btnFlash.visibility = View.VISIBLE
        seekZoom.visibility = View.VISIBLE
        tvZoom.visibility   = View.VISIBLE
        streaming = true
        startFpsCounter()
    }

    private fun stopStream() {
        rtspCamera.stopStream()
        stopService(Intent(this, StreamService::class.java))
        tvUrl.visibility = View.GONE
        tvStatus.text = "🔴 Detenido"
        tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        btnToggle.text = "Iniciar Stream"
        tvFps.text = "FPS: --"
        flashOn = false; btnFlash.text = "🔦 Flash OFF"
        seekZoom.progress = 0; tvZoom.text = "Zoom: 1.0x"
        streaming = false
    }

    private fun applyRotation() {
        val finalRot = (deviceRotation + manualRotationOffset) % 360
        rtspCamera.setOrientation(finalRot)
    }

    private fun startFpsCounter() {
        val handler = android.os.Handler(mainLooper)
        val runnable = object : Runnable {
            override fun run() {
                if (streaming) {
                    tvFps.text = "FPS: ${rtspCamera.streamClient.sentVideoFrames}"
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(runnable, 1000)
    }

    private fun getLocalIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress ?: "?.?.?.?"
        } catch (e: Exception) { "?.?.?.?" }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorMgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (!rtspCamera.isOnPreview && !streaming)
            rtspCamera.startPreview(currentCameraFacing)
    }

    override fun onPause() {
        super.onPause()
        sensorMgr.unregisterListener(this)
        // NO detenemos el stream al ir a segundo plano — el servicio lo mantiene vivo
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]
        val newRot = when {
            y >  5f -> 0;   y < -5f -> 180
            x >  5f -> 270; x < -5f -> 90
            else    -> deviceRotation
        }
        if (newRot == deviceRotation) return
        deviceRotation = newRot
        if (streaming) applyRotation()
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

    private fun hasPerm(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

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
        when (code) {
            101 -> if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
                audioEnabled = true
                rtspCamera.enableAudio()
                btnAudio.text = "🎙️ Audio ON"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (streaming) stopStream()
        rtspCamera.stopPreview()
    }
}
