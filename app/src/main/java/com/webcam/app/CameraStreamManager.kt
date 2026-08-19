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
    private val onFpsUpdate: (Int) -> Unit
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
    private var activeArraySize: Rect? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var afState = 0

    fun setDeviceRotation(rotation: Int) {
        currentRotation = rotation
    }

    @SuppressLint("MissingPermission")
    fun startCamera(width: Int, height: Int, useFrontCamera: Boolean) {
        isFrontCamera = useFrontCamera
        val cameraId = getCameraId(useFrontCamera) ?: run {
            Log.e("Camera", "No se encontró cámara"); return
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val supportedSizes = map.getOutputSizes(ImageFormat.JPEG)
        val bestSize = supportedSizes
            .filter { it.width <= width && it.height <= height }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: supportedSizes.minByOrNull { Math.abs(it.width - width) + Math.abs(it.height - height) }
            ?: return

        Log.d("Camera", "Resolución: ${bestSize.width}x${bestSize.height}, sensor: $sensorOrientation°")

        imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.JPEG, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            encodeHandler.post {
                try { processJpegImage(image) }
                catch (e: Exception) { Log.e("Camera", "Error frame: ${e.message}") }
                finally { image.close() }
            }
        }, captureHandler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession(camera)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("Camera", "Error: $error"); camera.close()
            }
        }, captureHandler)
    }

    private fun calculateRotation(): Int {
        // Calcula la rotación final combinando orientación del sensor + rotación del dispositivo
        return if (isFrontCamera) {
            (sensorOrientation + currentRotation + 360) % 360
        } else {
            (sensorOrientation - currentRotation + 360) % 360
        }
    }

    private fun processJpegImage(image: Image) {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val rotation = calculateRotation()
        val finalBytes = if (rotation != 0) rotateBitmap(bytes, rotation, isFrontCamera) else bytes

        server.updateFrame(finalBytes)

        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            onFpsUpdate(frameCount)
            frameCount = 0
            lastFpsTime = now
        }
    }

    private fun rotateBitmap(jpegBytes: ByteArray, rotation: Int, isFront: Boolean): ByteArray {
        return try {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return jpegBytes
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (isFront) postScale(-1f, 1f)
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
            bitmap.recycle(); rotated.recycle()
            out.toByteArray()
        } catch (e: Exception) { jpegBytes }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        try {
            camera.createCaptureSession(listOf(imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(imageReader!!.surface)
                            // FPS
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                            // Auto exposición
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            // Balance de blancos automático
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            // AF continuo para video — reenfoca solo constantemente
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            // Zona de enfoque: centro de la imagen (coordenadas van de -1000 a 1000)
                            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(
                                MeteringRectangle(Rect(-300, -300, 300, 300), MeteringRectangle.METERING_WEIGHT_MAX)
                            ))
                            // Zona de exposición: igual, centrada
                            set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(
                                MeteringRectangle(Rect(-300, -300, 300, 300), MeteringRectangle.METERING_WEIGHT_MAX)
                            ))
                            // Calidad
                            set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                            // Estabilización
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                        }

                        session.setRepeatingRequest(captureRequestBuilder!!.build(), afCallback, captureHandler)
                    } catch (e: Exception) {
                        Log.e("Camera", "Error request: ${e.message}")
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("Camera", "Sesión fallida")
                }
            }, captureHandler)
        } catch (e: Exception) {
            Log.e("Camera", "Error sesión: ${e.message}")
        }
    }

    // Callback que monitorea el estado del AF y fuerza re-enfoque si se pierde
    private val afCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
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
