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

    private val captureThread = HandlerThread("CameraCapture").also { it.start() }
    private val encodeThread = HandlerThread("CameraEncode").also { it.start() }
    private val captureHandler = Handler(captureThread.looper)
    private val encodeHandler = Handler(encodeThread.looper)

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var sensorOrientation = 0
    private var isFrontCamera = false
    private var currentRotation = 0 // Rotación actual del dispositivo (0, 90, 180, 270)
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var afState = 0
    private var activeArraySize: Rect? = null
    private var maxAfRegions = 0
    private var maxAeRegions = 0
    private var supportedFpsRanges: Array<Range<Int>>? = null
    private var availableStabModes: IntArray? = null

    // Parámetros de la sesión actual (se guardan para poder reintentar en "modo seguro")
    private var lastWidth = 1280
    private var lastHeight = 720
    private var safeModeActive = false
    private var restartAttempted = false

    fun setDeviceRotation(rotation: Int) {
        currentRotation = rotation
    }

    private fun pickBestFpsRange(): Range<Int> {
        val ranges = supportedFpsRanges
        if (ranges.isNullOrEmpty()) return Range(24, 30)
        // Preferir un rango que cubra 30fps, priorizando el más "ajustado" (menor span)
        return ranges
            .filter { it.upper in 24..60 }
            .minByOrNull { (it.upper - it.lower) + Math.abs(30 - it.upper) }
            ?: ranges.maxByOrNull { it.upper } ?: Range(24, 30)
    }

    private fun pickSafestFpsRange(): Range<Int> {
        // En modo seguro preferimos el rango más "ancho" (más tolerante para el HAL),
        // en vez del más ajustado a 30fps.
        val ranges = supportedFpsRanges
        if (ranges.isNullOrEmpty()) return Range(15, 30)
        return ranges.maxByOrNull { it.upper - it.lower } ?: Range(15, 30)
    }

    @SuppressLint("MissingPermission")
    fun startCamera(width: Int, height: Int, useFrontCamera: Boolean) {
        safeModeActive = false
        restartAttempted = false
        lastWidth = width
        lastHeight = height
        isFrontCamera = useFrontCamera
        openAndConfigure(width, height, useFrontCamera, safeMode = false)
    }

    @SuppressLint("MissingPermission")
    private fun openAndConfigure(width: Int, height: Int, useFrontCamera: Boolean, safeMode: Boolean) {
        // Todo el cuerpo va envuelto en try/catch: abrir la cámara puede lanzar
        // CameraAccessException (p. ej. "CAMERA_IN_USE" si se reabre demasiado
        // rápido tras cerrarla) y, al ejecutarse en un HandlerThread, una
        // excepción sin capturar aquí tumba la app entera.
        try {
            val cameraId = getCameraId(useFrontCamera) ?: run {
                Log.e("Camera", "No se encontró cámara"); onErrorMessage("No se encontró cámara"); return
            }

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run {
                onErrorMessage("No se pudo leer config. de cámara"); return
            }
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            supportedFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            availableStabModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)

            // IMPORTANTE: se captura en YUV_420_888, NO en JPEG.
            // Pedir un flujo *repetido* (repeating request) de JPEG directamente a la
            // cámara no es un caso de uso estándar del Camera2 API — es para fotos
            // individuales — y muchos HAL (sobre todo MediaTek en equipos recientes,
            // como este Honor con chip MediaTek) lo rechazan en cada frame con
            // "reason=0", sin importar qué otros parámetros se quiten.
            // YUV_420_888 sí está garantizado para streaming continuo en cualquier
            // dispositivo Camera2, así que capturamos en ese formato y convertimos
            // cada frame a JPEG nosotros mismos en software.
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            val bestSize = yuvSizes
                .filter { it.width <= width && it.height <= height }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: yuvSizes.minByOrNull { Math.abs(it.width - width) + Math.abs(it.height - height) }
                ?: return

            Log.d("Camera", "Resolución: ${bestSize.width}x${bestSize.height}, sensor: $sensorOrientation°, safeMode=$safeMode")

            imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.YUV_420_888, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                encodeHandler.post {
                    try { processYuvImage(image) }
                    catch (e: Exception) { Log.e("Camera", "Error frame: ${e.message}") }
                    finally { image.close() }
                }
            }, captureHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        createCaptureSession(camera, safeMode)
                    } catch (e: Exception) {
                        Log.e("Camera", "Error creando sesión: ${e.message}")
                        onErrorMessage("Error creando sesión: ${e.message}")
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("Camera", "Error: $error"); onErrorMessage("Error cámara: código $error"); camera.close()
                }
            }, captureHandler)
        } catch (e: Exception) {
            Log.e("Camera", "Error abriendo cámara: ${e.message}")
            onErrorMessage("Error al abrir cámara: ${e.message}")
        }
    }

    private fun calculateRotation(): Int {
        // Calcula la rotación final combinando orientación del sensor + rotación del dispositivo
        return if (isFrontCamera) {
            (sensorOrientation + currentRotation + 360) % 360
        } else {
            (sensorOrientation - currentRotation + 360) % 360
        }
    }

    // Convierte un Image en formato YUV_420_888 a un array NV21, respetando
    // rowStride/pixelStride (algunos dispositivos añaden padding entre filas).
    private fun imageToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var idx = 0
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, idx, width)
            idx += width
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vuIndex = row * uvRowStride + col * uvPixelStride
                nv21[idx++] = vBuffer.get(vuIndex)
                nv21[idx++] = uBuffer.get(vuIndex)
            }
        }

        return nv21
    }

    private fun processYuvImage(image: Image) {
        val nv21 = imageToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val jpegOut = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, jpegOut)
        val jpegBytes = jpegOut.toByteArray()

        val rotation = calculateRotation()
        val finalBytes = if (rotation != 0 || isFrontCamera) rotateJpeg(jpegBytes, rotation, isFrontCamera) else jpegBytes

        server.updateFrame(finalBytes)

        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            onFpsUpdate(frameCount)
            frameCount = 0
            lastFpsTime = now
        }
    }

    private fun rotateJpeg(jpegBytes: ByteArray, rotation: Int, isFront: Boolean): ByteArray {
        return try {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return jpegBytes
            val matrix = Matrix().apply {
                if (rotation != 0) postRotate(rotation.toFloat())
                if (isFront) postScale(-1f, 1f)
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bitmap.recycle(); rotated.recycle()
            out.toByteArray()
        } catch (e: Exception) { jpegBytes }
    }

    private fun createCaptureSession(camera: CameraDevice, safeMode: Boolean) {
        try {
            val targets = listOfNotNull(imageReader!!.surface)
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val template = if (safeMode) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD
                        captureRequestBuilder = camera.createCaptureRequest(template).apply {
                            addTarget(imageReader!!.surface)
                            // FPS - usar un rango realmente soportado por el dispositivo
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, if (safeMode) pickSafestFpsRange() else pickBestFpsRange())
                            // Auto exposición
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            // Balance de blancos automático
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            // AF continuo para video — reenfoca solo constantemente
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

                            if (!safeMode) {
                                // Zona de enfoque/exposición: centro del sensor (coordenadas reales, no negativas)
                                // Solo se configura si el dispositivo realmente soporta regiones de AF/AE
                                activeArraySize?.let { rect ->
                                    val cx = rect.width() / 2
                                    val cy = rect.height() / 2
                                    val half = minOf(rect.width(), rect.height()) / 6
                                    val meteringRect = MeteringRectangle(
                                        (cx - half).coerceAtLeast(0),
                                        (cy - half).coerceAtLeast(0),
                                        half * 2,
                                        half * 2,
                                        MeteringRectangle.METERING_WEIGHT_MAX
                                    )
                                    if (maxAfRegions > 0) {
                                        set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                                    }
                                    if (maxAeRegions > 0) {
                                        set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
                                    }
                                }
                            }
                            // Bordes / reducción de ruido
                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                            // Estabilización — solo si el dispositivo la soporta y no estamos en modo seguro.
                            val stabSupported = availableStabModes?.contains(
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                            ) == true
                            if (!safeMode && stabSupported) {
                                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                            }
                        }

                        captureFailCount = 0
                        session.setRepeatingRequest(captureRequestBuilder!!.build(), afCallback, captureHandler)

                        if (safeMode) {
                            onErrorMessage("⚠️ Modo compatibilidad activado (algunos ajustes de cámara se desactivaron)")
                        }
                    } catch (e: Exception) {
                        Log.e("Camera", "Error request: ${e.message}")
                        onErrorMessage("Error al iniciar captura: ${e.message}")
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("Camera", "Sesión fallida")
                    if (!safeMode && !restartAttempted) {
                        restartAttempted = true
                        retryInSafeMode()
                    } else {
                        onErrorMessage("No se pudo configurar la sesión de cámara")
                    }
                }
            }, captureHandler)
        } catch (e: Exception) {
            Log.e("Camera", "Error sesión: ${e.message}")
            onErrorMessage("Error de sesión: ${e.message}")
        }
    }

    // Callback que monitorea fallos de captura por request (p. ej. rango de FPS no soportado)
    private var captureFailCount = 0
    private val SAFE_MODE_THRESHOLD = 4 // fallos consecutivos antes de reintentar en modo seguro

    // Callback que monitorea el estado del AF y fuerza re-enfoque si se pierde
    private val afCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            // Cualquier captura exitosa resetea el contador de fallos
            captureFailCount = 0
            val newAfState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            if (newAfState != afState) {
                afState = newAfState
                when (newAfState) {
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                        // Si el AF quedó bloqueado sin enfocar, forzar nuevo ciclo
                        triggerAutoFocus()
                    }
                }
            }
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            captureFailCount++
            Log.e("Camera", "Captura fallida (reason=${failure.reason}), total: $captureFailCount")
            if (captureFailCount == 1 && !safeModeActive) {
                onErrorMessage("La cámara rechaza los parámetros de captura (reason=${failure.reason})")
            }
            if (captureFailCount >= SAFE_MODE_THRESHOLD && !safeModeActive && !restartAttempted) {
                restartAttempted = true
                retryInSafeMode()
            }
        }
    }

    // Reintenta la sesión de cámara quitando todos los ajustes "opcionales" que
    // pueden no ser soportados por el HAL: sin regiones AF/AE, sin estabilización,
    // plantilla PREVIEW en vez de RECORD.
    private fun retryInSafeMode() {
        Log.d("Camera", "Reintentando en modo seguro")
        captureHandler.post {
            try {
                captureSession?.close(); captureSession = null
                cameraDevice?.close(); cameraDevice = null
                imageReader?.close(); imageReader = null
            } catch (e: Exception) { }
            safeModeActive = true
            captureFailCount = 0
            // Pequeño retraso: reabrir la cámara inmediatamente después de cerrarla
            // puede lanzar CameraAccessException (CAMERA_IN_USE) porque el sistema
            // aún no terminó de liberar el dispositivo.
            captureHandler.postDelayed({
                openAndConfigure(lastWidth, lastHeight, isFrontCamera, safeMode = true)
            }, 350)
        }
    }

    private fun triggerAutoFocus() {
        val session = captureSession ?: return
        val builder = captureRequestBuilder ?: return
        try {
            // Disparar AF
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, captureHandler)
            // Volver a modo continuo
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), afCallback, captureHandler)
        } catch (e: Exception) {
            Log.e("Camera", "Error AF: ${e.message}")
        }
    }

    private fun getCameraId(useFront: Boolean): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (useFront) facing == CameraCharacteristics.LENS_FACING_FRONT
                else facing == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) { null }
    }

    fun stopCamera() {
        try {
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
            imageReader?.close(); imageReader = null
            captureRequestBuilder = null
        } catch (e: Exception) {
            Log.e("Camera", "Error deteniendo: ${e.message}")
        }
    }
}
