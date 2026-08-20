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

    private val captureThread = HandlerThread("CameraCapture").also { it.start() }
    private val encodeThread  = HandlerThread("CameraEncode").also  { it.start() }
    private val captureHandler = Handler(captureThread.looper)
    private val encodeHandler  = Handler(encodeThread.looper)

    private var frameCount   = 0
    private var lastFpsTime  = System.currentTimeMillis()
    private var sensorOrientation = 0
    private var isFrontCamera     = false
    private var currentRotation   = 0
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var afState = -1

    private var activeArraySize: Rect? = null
    private var maxAfRegions = 0
    private var maxAeRegions = 0
    private var supportedFpsRanges: Array<Range<Int>>? = null
    private var stabModesSupported = false

    private var lastWidth  = 1280
    private var lastHeight = 720
    private var lastFront  = false

    // ── NUEVAS: estado de flash, zoom y rotación manual ─────────────────────
    private var flashEnabled  = false
    private var currentZoom   = 1.0f
    private var manualRotationOffset = 0   // offset añadido por el botón de girar
    private var maxZoom = 1.0f
    private var hasFlash = false

    // Lista de cámaras disponibles: Pair(cameraId, etiqueta legible)
    data class CameraInfo(val id: String, val label: String)
    private val _availableCameras = mutableListOf<CameraInfo>()
    val availableCameras: List<CameraInfo> get() = _availableCameras

    private var currentCameraId: String? = null

    // ── Escanear cámaras disponibles ─────────────────────────────────────────
    fun scanCameras(): List<CameraInfo> {
        _availableCameras.clear()
        for (id in cameraManager.cameraIdList) {
            try {
                val ch = cameraManager.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                val focalLengths = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val minFocal = focalLengths?.minOrNull() ?: 0f
                val maxFocal = focalLengths?.maxOrNull() ?: 0f

                val label = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Frontal"
                    CameraCharacteristics.LENS_FACING_BACK  -> when {
                        minFocal > 0 && minFocal < 2.0f -> "Gran angular (${String.format("%.1f", minFocal)}mm)"
                        minFocal >= 2.0f && minFocal < 4f -> "Principal (${String.format("%.1f", minFocal)}mm)"
                        minFocal >= 4f  -> "Teleobjetivo (${String.format("%.1f", minFocal)}mm)"
                        else -> "Trasera #$id"
                    }
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "Externa #$id"
                    else -> "Cámara #$id"
                }
                _availableCameras.add(CameraInfo(id, label))
                Log.d("CAM", "Cámara $id: $label focal=${minFocal}-${maxFocal}mm")
            } catch (e: Exception) {
                _availableCameras.add(CameraInfo(id, "Cámara #$id"))
            }
        }
        return _availableCameras
    }

    // ── Controles de flash, zoom y rotación manual ───────────────────────────
    fun setFlash(enable: Boolean) {
        if (!hasFlash) return
        flashEnabled = enable
        applyControls()
    }

    fun setZoom(zoom: Float) {
        currentZoom = zoom.coerceIn(1.0f, maxZoom)
        applyControls()
    }

    fun rotateManual(degrees: Int) {
        manualRotationOffset = (manualRotationOffset + degrees + 360) % 360
    }

    fun getMaxZoom() = maxZoom
    fun hasFlash()   = hasFlash

    private fun applyControls() {
        val b = captureRequestBuilder ?: return
        val s = captureSession ?: return
        try {
            // Flash
            if (hasFlash) {
                b.set(CaptureRequest.FLASH_MODE,
                    if (flashEnabled) CaptureRequest.FLASH_MODE_TORCH
                    else              CaptureRequest.FLASH_MODE_OFF)
            }
            // Zoom via crop region
            activeArraySize?.let { arr ->
                val ratio  = 1.0f / currentZoom
                val cropW  = (arr.width()  * ratio).toInt()
                val cropH  = (arr.height() * ratio).toInt()
                val left   = (arr.width()  - cropW) / 2
                val top    = (arr.height() - cropH) / 2
                b.set(CaptureRequest.SCALER_CROP_REGION,
                    Rect(left, top, left + cropW, top + cropH))
            }
            s.setRepeatingRequest(b.build(), captureCallback, captureHandler)
        } catch (e: Exception) { Log.e("CAM", "applyControls: ${e.message}") }
    }

    // ── Apertura por ID específico (para selector de lentes) ─────────────────
    @SuppressLint("MissingPermission")
    fun startCameraById(cameraId: String, width: Int, height: Int) {
        lastWidth  = width
        lastHeight = height
        currentCameraId = cameraId
        openById(cameraId, width, height)
    }

    fun setDeviceRotation(rotation: Int) { currentRotation = rotation }

    // ── Apertura principal (igual que antes, sin cambios) ────────────────────
    @SuppressLint("MissingPermission")
    fun startCamera(width: Int, height: Int, useFrontCamera: Boolean) {
        lastWidth  = width
        lastHeight = height
        lastFront  = useFrontCamera
        isFrontCamera = useFrontCamera

        try {
            val cameraId = getCameraId(useFrontCamera) ?: run {
                onErrorMessage("No se encontró cámara"); return
            }
            currentCameraId = cameraId
            openById(cameraId, width, height)
        } catch (e: CameraAccessException) {
            Log.e("CAM", "openCamera: ${e.message}")
            onErrorMessage("No se pudo acceder a la cámara (${e.reason})")
        } catch (e: Exception) {
            Log.e("CAM", "startCamera: ${e.message}")
            onErrorMessage("Error al abrir cámara: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openById(cameraId: String, width: Int, height: Int) {
        try {
            val ch  = cameraManager.getCameraCharacteristics(cameraId)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run {
                onErrorMessage("No se pudo leer configuración de cámara"); return
            }
            sensorOrientation  = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            activeArraySize    = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxAfRegions       = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            maxAeRegions       = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            supportedFpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            stabModesSupported = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
            hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            maxZoom  = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            isFrontCamera = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

            // Reset zoom/flash al cambiar de cámara
            currentZoom  = 1.0f
            flashEnabled = false

            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            val bestSize = yuvSizes
                .filter { it.width <= width && it.height <= height }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: yuvSizes.minByOrNull { Math.abs(it.width - width) + Math.abs(it.height - height) }
                ?: run { onErrorMessage("No hay resolución compatible"); return }

            Log.d("CAM", "Res=${bestSize.width}x${bestSize.height} sensor=$sensorOrientation flash=$hasFlash maxZoom=$maxZoom")

            imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.YUV_420_888, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                encodeHandler.post {
                    try   { processYuv(img) }
                    catch (e: Exception) { Log.e("CAM", "frame err: ${e.message}") }
                    finally { img.close() }
                }
            }, captureHandler)

            cameraManager.openCamera(cameraId, stateCallback, captureHandler)

        } catch (e: CameraAccessException) {
            Log.e("CAM", "openById: ${e.message}")
            onErrorMessage("No se pudo acceder a la cámara (${e.reason})")
        } catch (e: Exception) {
            Log.e("CAM", "openById: ${e.message}")
            onErrorMessage("Error al abrir cámara: ${e.message}")
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            try { createSession(camera) }
            catch (e: Exception) {
                Log.e("CAM", "createSession: ${e.message}")
                onErrorMessage("Error iniciando sesión: ${e.message}")
            }
        }
        override fun onDisconnected(camera: CameraDevice) {
            Log.w("CAM", "onDisconnected"); camera.close(); cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            val msg = when (error) {
                CameraDevice.StateCallback.ERROR_CAMERA_DEVICE      -> "Error interno de cámara (HAL)"
                CameraDevice.StateCallback.ERROR_CAMERA_SERVICE     -> "Error en servicio de cámara"
                CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Demasiadas cámaras en uso"
                CameraDevice.StateCallback.ERROR_CAMERA_DISABLED    -> "Cámara deshabilitada por política"
                CameraDevice.StateCallback.ERROR_CAMERA_IN_USE      -> "Cámara en uso por otra app"
                else -> "Error desconocido ($error)"
            }
            Log.e("CAM", "onError: $msg"); onErrorMessage(msg)
            camera.close(); cameraDevice = null
            if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE)
                captureHandler.postDelayed({ startCamera(lastWidth, lastHeight, lastFront) }, 800)
        }
    }

    private fun createSession(camera: CameraDevice) {
        val surface = imageReader?.surface ?: return
        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session; startRepeating(camera, session)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("CAM", "onConfigureFailed")
                onErrorMessage("No se pudo configurar la sesión de cámara")
            }
        }, captureHandler)
    }

    private fun startRepeating(camera: CameraDevice, session: CameraCaptureSession) {
        try {
            captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).also { b ->
                b.addTarget(imageReader!!.surface)
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chooseFpsRange())
                b.set(CaptureRequest.CONTROL_MODE,     CaptureRequest.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AE_MODE,  CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_MODE,  CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

                activeArraySize?.let { rect ->
                    if (maxAfRegions > 0 || maxAeRegions > 0) {
                        val cx = rect.width() / 2; val cy = rect.height() / 2
                        val half = minOf(rect.width(), rect.height()) / 6
                        val mr = MeteringRectangle(
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
            session.setRepeatingRequest(captureRequestBuilder!!.build(), captureCallback, captureHandler)
        } catch (e: Exception) {
            Log.e("CAM", "startRepeating: ${e.message}")
            onErrorMessage("Error iniciando captura: ${e.message}")
        }
    }

    private fun chooseFpsRange(): Range<Int> {
        val ranges = supportedFpsRanges
        if (ranges.isNullOrEmpty()) return Range(15, 30)
        return ranges.filter { it.upper >= 24 }
            .minByOrNull { Math.abs(it.upper - 30) * 10 + (it.upper - it.lower) }
            ?: ranges.maxByOrNull { it.upper }
            ?: Range(15, 30)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val state = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            if (state == afState) return
            afState = state
            if (state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) triggerAf()
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            Log.e("CAM", "captureFailed reason=${failure.reason}")
        }
    }

    private fun triggerAf() {
        val s = captureSession ?: return; val b = captureRequestBuilder ?: return
        try {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, captureHandler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            s.setRepeatingRequest(b.build(), captureCallback, captureHandler)
        } catch (e: Exception) { Log.e("CAM", "triggerAf: ${e.message}") }
    }

    // ── Procesamiento de frames (sin cambios) ────────────────────────────────
    private fun processYuv(image: Image) {
        val nv21 = yuv420ToNv21(image)
        val yuv  = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val baos = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 92, baos)
        val jpeg = baos.toByteArray()
        val rot  = calculateRotation()
        val final = if (rot != 0 || isFrontCamera) rotateJpeg(jpeg, rot) else jpeg
        server.updateFrame(final)
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) { onFpsUpdate(frameCount); frameCount = 0; lastFpsTime = now }
    }

    private fun calculateRotation(): Int {
        // manualRotationOffset permite al usuario corregir el sentido manualmente
        return if (isFrontCamera)
            (sensorOrientation + currentRotation + manualRotationOffset + 360) % 360
        else
            (sensorOrientation - currentRotation + manualRotationOffset + 360) % 360
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
            rot.compress(Bitmap.CompressFormat.JPEG, 92, out)
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
        } catch (e: Exception) { Log.e("CAM", "stop: ${e.message}") }
    }
}
