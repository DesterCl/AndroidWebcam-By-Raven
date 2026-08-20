package com.webcam.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import java.io.ByteArrayOutputStream

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
    private var supportedFpsRanges: Array<Range<Int>>? = null
    private var stabModesSupported = false
    private var hasFlash     = false
    private var flashEnabled = false
    private var maxZoom      = 1.0f
    private var currentZoom  = 1.0f

    private var lastWidth  = 1280
    private var lastHeight = 720
    private var lastFront  = false

    // Rotación calculada UNA SOLA VEZ al abrir la cámara
    // Solo se recalcula si el usuario gira el teléfono o pulsa el botón manual
    @Volatile private var cachedRotation = 0
    @Volatile private var needsRotation  = false

    // Reusar el mismo ByteArrayOutputStream evita allocations por frame
    private val frameBuffer = ByteArrayOutputStream(640 * 480 * 2)

    // ── Cámaras disponibles ──────────────────────────────────────────────────
    data class CameraInfo(val id: String, val label: String)
    private val _availableCameras = mutableListOf<CameraInfo>()
    val availableCameras: List<CameraInfo> get() = _availableCameras

    fun scanCameras(): List<CameraInfo> {
        _availableCameras.clear()
        for (id in cameraManager.cameraIdList) {
            try {
                val ch     = cameraManager.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                val focal  = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val minF   = focal?.minOrNull() ?: 0f
                val label  = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT    -> "Frontal"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "Externa #$id"
                    else -> when {
                        minF > 0 && minF < 2f  -> "Gran angular (${String.format("%.1f", minF)}mm)"
                        minF >= 2f && minF < 4f -> "Principal (${String.format("%.1f", minF)}mm)"
                        minF >= 4f              -> "Teleobjetivo (${String.format("%.1f", minF)}mm)"
                        else -> "Trasera #$id"
                    }
                }
                _availableCameras.add(CameraInfo(id, label))
            } catch (e: Exception) {
                _availableCameras.add(CameraInfo(id, "Cámara #$id"))
            }
        }
        return _availableCameras
    }

    // ── Controles ────────────────────────────────────────────────────────────
    fun setDeviceRotation(r: Int) {
        currentRotation = r
        updateCachedRotation()
    }

    fun rotateManual(deg: Int) {
        manualRotationOffset = (manualRotationOffset + deg + 360) % 360
        updateCachedRotation()
    }

    // Precalcula la rotación para no hacerlo en cada frame
    private fun updateCachedRotation() {
        val rot = if (isFrontCamera)
            (sensorOrientation + currentRotation + manualRotationOffset + 360) % 360
        else
            (sensorOrientation - currentRotation + manualRotationOffset + 360) % 360
        cachedRotation = rot
        needsRotation  = (rot != 0 || isFrontCamera)
    }

    fun getMaxZoom() = maxZoom
    fun hasFlash()   = hasFlash

    fun setFlash(enable: Boolean) {
        if (!hasFlash) return
        flashEnabled = enable
        applyControls()
    }

    fun setZoom(zoom: Float) {
        currentZoom = zoom.coerceIn(1f, maxZoom)
        applyControls()
    }

    private fun applyControls() {
        val b = captureRequestBuilder ?: return
        val s = captureSession       ?: return
        try {
            if (hasFlash)
                b.set(CaptureRequest.FLASH_MODE,
                    if (flashEnabled) CaptureRequest.FLASH_MODE_TORCH
                    else CaptureRequest.FLASH_MODE_OFF)
            activeArraySize?.let { arr ->
                val ratio = 1f / currentZoom
                val cw = (arr.width()  * ratio).toInt()
                val ch = (arr.height() * ratio).toInt()
                val l  = (arr.width()  - cw) / 2
                val t  = (arr.height() - ch) / 2
                b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + cw, t + ch))
            }
            s.setRepeatingRequest(b.build(), captureCallback, camHandler)
        } catch (e: Exception) { Log.e("CAM", "applyControls: ${e.message}") }
    }

    // ── Apertura ─────────────────────────────────────────────────────────────
    fun startCameraById(id: String, width: Int, height: Int) {
        lastWidth = width; lastHeight = height
        openById(id, width, height)
    }

    @SuppressLint("MissingPermission")
    fun startCamera(width: Int, height: Int, useFront: Boolean) {
        lastWidth = width; lastHeight = height; lastFront = useFront
        val id = getCameraId(useFront) ?: run { onErrorMessage("No se encontró cámara"); return }
        openById(id, width, height)
    }

    @SuppressLint("MissingPermission")
    private fun openById(cameraId: String, width: Int, height: Int) {
        try {
            val ch  = cameraManager.getCameraCharacteristics(cameraId)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: run { onErrorMessage("No se pudo leer config. de cámara"); return }

            sensorOrientation  = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            activeArraySize    = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxAfRegions       = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            maxAeRegions       = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            supportedFpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            stabModesSupported = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
            hasFlash      = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            maxZoom       = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            isFrontCamera = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            currentZoom   = 1f
            flashEnabled  = false

            // Precalcular rotación inicial
            updateCachedRotation()

            val sizes    = map.getOutputSizes(ImageFormat.YUV_420_888)
            val bestSize = sizes.filter { it.width <= width && it.height <= height }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.minByOrNull { Math.abs(it.width - width) + Math.abs(it.height - height) }
                ?: run { onErrorMessage("Sin resolución compatible"); return }

            Log.d("CAM", "${bestSize.width}x${bestSize.height} rot=$cachedRotation needsRot=$needsRotation")

            imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.YUV_420_888, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try   { processYuv(img) }
                catch (e: Exception) { Log.e("CAM", "frame: ${e.message}") }
                finally { img.close() }
            }, camHandler)

            cameraManager.openCamera(cameraId, stateCallback, camHandler)

        } catch (e: CameraAccessException) {
            onErrorMessage("Acceso denegado (${e.reason})")
        } catch (e: Exception) {
            Log.e("CAM", "openById: ${e.message}")
            onErrorMessage("Error abriendo cámara: ${e.message}")
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            try {
                val surface = imageReader?.surface ?: return
                camera.createCaptureSession(listOf(surface), sessionCallback, camHandler)
            } catch (e: Exception) {
                Log.e("CAM", "createSession: ${e.message}")
                onErrorMessage("Error creando sesión: ${e.message}")
            }
        }
        override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
        override fun onError(camera: CameraDevice, error: Int) {
            val msg = when (error) {
                1 -> "Error interno HAL"
                2 -> "Error servicio cámara"
                3 -> "Demasiadas cámaras abiertas"
                4 -> "Cámara deshabilitada"
                5 -> "Cámara en uso por otra app"
                else -> "Error $error"
            }
            Log.e("CAM", "onError: $msg"); onErrorMessage(msg)
            camera.close(); cameraDevice = null
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            try {
                captureRequestBuilder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).also { b ->
                    b.addTarget(imageReader!!.surface)
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chooseFpsRange())
                    b.set(CaptureRequest.CONTROL_MODE,     CaptureRequest.CONTROL_MODE_AUTO)
                    b.set(CaptureRequest.CONTROL_AE_MODE,  CaptureRequest.CONTROL_AE_MODE_ON)
                    b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    b.set(CaptureRequest.CONTROL_AF_MODE,  CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    activeArraySize?.let { rect ->
                        if (maxAfRegions > 0 || maxAeRegions > 0) {
                            val cx   = rect.width()  / 2
                            val cy   = rect.height() / 2
                            val half = minOf(rect.width(), rect.height()) / 6
                            val mr   = MeteringRectangle(
                                (cx - half).coerceAtLeast(0), (cy - half).coerceAtLeast(0),
                                half * 2, half * 2, MeteringRectangle.METERING_WEIGHT_MAX)
                            if (maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(mr))
                            if (maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(mr))
                        }
                    }
                    b.set(CaptureRequest.EDGE_MODE,            CaptureRequest.EDGE_MODE_FAST)
                    b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                    if (stabModesSupported)
                        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                }
                session.setRepeatingRequest(captureRequestBuilder!!.build(), captureCallback, camHandler)
            } catch (e: Exception) {
                Log.e("CAM", "startRepeating: ${e.message}")
                onErrorMessage("Error iniciando captura: ${e.message}")
            }
        }
        override fun onConfigureFailed(session: CameraCaptureSession) {
            onErrorMessage("Fallo configurando sesión")
        }
    }

    private fun chooseFpsRange(): Range<Int> {
        val ranges = supportedFpsRanges
        if (ranges.isNullOrEmpty()) return Range(15, 30)
        // Buscar el rango más alto disponible (no solo 30)
        return ranges.maxByOrNull { it.upper } ?: Range(15, 30)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val state = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            if (state == afState) return
            afState = state
            if (state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) triggerAf()
        }
        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
            Log.e("CAM", "captureFailed reason=${f.reason}")
        }
    }

    private fun triggerAf() {
        val s = captureSession ?: return; val b = captureRequestBuilder ?: return
        try {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, camHandler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            s.setRepeatingRequest(b.build(), captureCallback, camHandler)
        } catch (e: Exception) { Log.e("CAM", "triggerAf: ${e.message}") }
    }

    // ── Procesamiento de frames ───────────────────────────────────────────────
    private fun processYuv(image: Image) {
        val nv21 = yuv420ToNv21(image)
        val yuv  = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)

        // Reusar buffer — evita crear un nuevo ByteArrayOutputStream cada frame
        frameBuffer.reset()
        // Calidad 85: imperceptible vs 92 pero ~15% más rápido de comprimir
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 85, frameBuffer)

        val jpeg = frameBuffer.toByteArray()

        // Rotar solo si hace falta (cachedRotation se precalculó al abrir la cámara)
        val final = if (needsRotation) rotateJpeg(jpeg, cachedRotation) else jpeg

        server.updateFrame(final)
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) { onFpsUpdate(frameCount); frameCount = 0; lastFpsTime = now }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val w = image.width; val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val yp = image.planes[0]; val up = image.planes[1]; val vp = image.planes[2]
        var idx = 0
        val yBuf = yp.buffer; val yStride = yp.rowStride
        for (row in 0 until h) {
            yBuf.position(row * yStride)
            val rem = minOf(w, yBuf.remaining())
            yBuf.get(nv21, idx, rem); idx += rem
        }
        val uBuf = up.buffer; val vBuf = vp.buffer
        val uvRowStride = up.rowStride; val uvPixel = up.pixelStride
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            val pos = row * uvRowStride + col * uvPixel
            if (pos < vBuf.limit()) nv21[idx++] = vBuf.get(pos)
            if (pos < uBuf.limit()) nv21[idx++] = uBuf.get(pos)
        }
        return nv21
    }

    private fun rotateJpeg(jpeg: ByteArray, rotation: Int): ByteArray {
        return try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bmp  = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return jpeg
            val mat  = Matrix().apply {
                if (rotation != 0) postRotate(rotation.toFloat())
                if (isFrontCamera) postScale(-1f, 1f)
            }
            val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mat, false)
            val out = ByteArrayOutputStream()
            rot.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle(); rot.recycle()
            out.toByteArray()
        } catch (e: Exception) { jpeg }
    }

    private fun getCameraId(useFront: Boolean): String? =
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
                .let { if (useFront) it == CameraCharacteristics.LENS_FACING_FRONT
                       else          it == CameraCharacteristics.LENS_FACING_BACK }
        }

    fun stopCamera() {
        try {
            captureSession?.close();  captureSession = null
            cameraDevice?.close();    cameraDevice = null
            imageReader?.close();     imageReader = null
            captureRequestBuilder = null
            flashEnabled = false
            frameBuffer.reset()
        } catch (e: Exception) { Log.e("CAM", "stop: ${e.message}") }
    }
}
