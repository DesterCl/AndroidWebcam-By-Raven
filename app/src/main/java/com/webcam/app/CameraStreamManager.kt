package com.webcam.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class CameraStreamManager(
    private val context: Context,
    private val server: MjpegHttpServer,
    private val onFpsUpdate: (Int) -> Unit,
    private val onErrorMessage: (String) -> Unit = {}
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val camThread  = HandlerThread("CamThread").also { it.start() }
    private val camHandler = Handler(camThread.looper)

    private var frameCount  = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var sensorOrientation    = 0
    private var isFrontCamera        = false
    private var currentRotation      = 0
    private var manualRotationOffset = 0
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var afState = -1

    private var activeArraySize: Rect? = null
    private var maxAfRegions   = 0
    private var maxAeRegions   = 0
    private var supportedFpsRanges:
