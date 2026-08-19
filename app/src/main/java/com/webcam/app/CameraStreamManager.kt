package com.webcam.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
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
    private var frameWidth = 0
    private var frameHeight = 0

    @SuppressLint("MissingPermission")
    fun startCamera(width: Int, height: Int, useFrontCamera: Boolean) {
        isFrontCamera = useFrontCamera
        val cameraId = getCameraId(useFrontCamera) ?: run {
            Log.e("Camera", "No se encontró cámara")
            return
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // Usar JPEG directamente — más simple y estable que YUV
        val supportedSizes = map.getOutputSizes(ImageFormat.JPEG)
        val bestSize = supportedSizes
            .filter { it.width <= width && it.height <= height }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: supportedSizes.minByOrNull { Math.abs(it.width - width) + Math.abs(it.height - height) }
            ?: return

        frameWidth = bestSize.width
        frameHeight = bestSize.height
        Log.d("Camera", "Resolución: ${frameWidth}x${frameHeight}, orientación: $sensorOrientation")

        imageReader = ImageReader.newInstance(frameWidth, frameHeight, ImageFormat.JPEG, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            encodeHandler.post {
                try {
                    processJpegImage(image)
                } catch (e: Exception) {
                    Log.e("Camera", "Error procesando frame: ${e.message}")
                } finally {
                    image.close()
                }
            }
        }, captureHandler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession(camera)
            }
            override fun onDisconnected(camera: CameraDevice) {
                Log.w("Camera", "Cámara desconectada")
                camera.close()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("Camera", "Error de cámara: $error")
                camera.close()
            }
        }, captureHandler)
    }

    private fun processJpegImage(image: Image) {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // Rotar solo si es necesario
        val finalBytes = if (sensorOrientation != 0 && sensorOrientation != 360) {
            rotateBitmap(bytes, sensorOrientation, isFrontCamera)
        } else {
            bytes
        }

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
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565 // Menos memoria
            }
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                ?: return jpegBytes
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (isFront) postScale(-1f, 1f)
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
            bitmap.recycle()
            rotated.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.e("Camera", "Error rotando: ${e.message}")
            jpegBytes
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        try {
            camera.createCaptureSession(
                listOf(imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(imageReader!!.surface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                            }
                            session.setRepeatingRequest(request.build(), null, captureHandler)
                        } catch (e: Exception) {
                            Log.e("Camera", "Error creando request: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("Camera", "Fallo configurando sesión")
                    }
                },
                captureHandler
            )
        } catch (e: Exception) {
            Log.e("Camera", "Error creando sesión: ${e.message}")
        }
    }

    private fun getCameraId(useFront: Boolean): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val facing = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                if (useFront) facing == CameraCharacteristics.LENS_FACING_FRONT
                else facing == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            Log.e("Camera", "Error buscando cámara: ${e.message}")
            null
        }
    }

    fun stopCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e("Camera", "Error deteniendo cámara: ${e.message}")
        }
    }
}
