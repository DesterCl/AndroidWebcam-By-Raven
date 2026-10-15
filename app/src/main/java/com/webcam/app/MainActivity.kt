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
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtsp.RtspCamera2
import java.net.NetworkInterface

class MainActivity : AppCompatActivity(), SensorEventListener, ConnectChecker {

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
    private var isFrontCamera = false

    private val RTSP_PORT = 1935

    private val resolutions = listOf(
        Triple("4K (3840x2160)",      3840, 2160),
        Triple("2K (2560x1440)",      2560, 1440),
        Triple("Full HD (1920x1080)", 1920, 1080),
        Triple("HD (1280x720)",       1280,  720),
        Triple("480p (640x480)",       640,  480)
    )

    // ── ConnectChecker callbacks ──────────────────────────────────────────────
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        runOnUiThread { tvStatus.text = "🟢 Transmitiendo" }
    }
    override fun onConnectionFailed(reason: String) {
        runOnUiThread { tvStatus.text = "❌ Error: $reason" }
        // Reintentar automáticamente
        if (rtspCamera.getStreamClient().reTry(5000, reason, null))
            runOnUiThread { tvStatus.text = "🟡 Reintentando..." }
        else
            runOnUiThread { stopStream() }
    }
    override fun onDisconnect() {
        runOnUiThread { tvStatus.text = "🟡 Desconectado" }
    }
    override fun onAuthError() {
        runOnUiThread { tvStatus.text = "❌ Error de autenticación" }
    }
    override fun onAuthSuccess() {}

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

        // RtspCamera2 con ConnectChecker integrado en la propia Activity
        rtspCamera = RtspCamera2(surfaceView, this)
        rtspCamera.getStreamClient().setReTries(10)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChang