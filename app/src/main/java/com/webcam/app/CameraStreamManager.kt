package com.webcam.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.renderscript.*
import android.util.Log
import android.util.Range
import java.io.ByteArrayOutputStream

class CameraStreamManager(
    private val context: Context,
    private val server: MjpegHttpServer,
    private val onFpsUpdate: (Int) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Dos hilos: uno para captura, otro para encoding
    private val captureThread = HandlerThread("CameraCapture").also { it.start() }
    private val encodeThread = HandlerThread("CameraEncode").also { it.start() }
    private val captureHandler = Handler(captureThread.looper)
    private val encodeHandler = Handler(encodeThread.looper)

    // Pool de ByteArrayOutputStream para evitar allocations
    private val baoPool = ArrayDeque<ByteArrayOutputStream>()

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var sensorOrientation = 0
    private var isFrontCamera = false

    @SuppressLint("MissingPermission")
    fun startCamera(width: Int, height: Int, useFrontCamera: Boolean) {
        isFrontCamera = useFrontCamera
        val cameraId = getCameraId(useFrontCamera) ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // Buscar mejor resolución soportada
        val supportedSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val bestSize = supportedSizes
            .filter { it.width <= width && it.height <= height }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: supportedSizes.minByOrNull { Math.abs(it.width - width) + Math.abs(it.height - height) }!!

        Log.d("Camera", "Resolución seleccionada: ${bestSize.width}x${bestSize.height}")

        // YUV_420_888: formato nativo de la cámara, sin conversión interna
        imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.YUV_420_888, 4)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            // Encoding en hilo separado
            encodeHandler.post {
                try {
                    encodeAndSend(image)
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
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("Camera", "Error: $error"); camera.close()
            }
        }, captureHandler)
    }

    private fun encodeAndSend(image: Image) {
        try {
            // Convertir YUV a Bitmap usando YuvImage (más rápido que BitmapFactory)
            val yuvBytes = yuv420ToNv21(image)
            val yuvImage = YuvImage(yuvBytes, ImageFormat.NV21, image.width, image.height, null)

            val bao = baoPool.removeLastOrNull() ?: ByteArrayOutputStream(image.width * image.height / 2)
            bao.reset()

            // Calidad 90: buen balance velocidad/calidad para streaming
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, bao)

            val jpegBytes = bao.toByteArray()
            baoPool.addLast(bao)

            // Rotar solo si es necesario (evitar rotación en resoluciones altas)
            val finalBytes = if (sensorOrientation != 0 && sensorOrientation != 360) {
                rotateFast(jpegBytes, sensorOrientation, isFrontCamera)
            } else {
                jpegBytes
            }

            server.updateFrame(finalBytes)

            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= 1000) {
                onFpsUpdate(frameCount)
                frameCount = 0
                lastFpsTime = now
            }
        } catch (e: Exception) {
            Log.e("Camera", "Error encoding: ${e.message}")
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + width * height / 2)

        // Copiar canal Y
        yBuffer.get(nv21, 0, ySize)

        // Intercalar V y U para NV21
        val vBytes = ByteArray(vSize)
        val uBytes = ByteArray(uSize)
        vBuffer.get(vBytes)
        uBuffer.get(uBytes)

        val uvStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride

        var pos = ySize
        var row = 0
        while (row < height / 2) {
            var col = 0
            while (col < width / 2) {
                val vIdx = row * uvStride + col * uvPixelStride
                val uIdx = row * uvStride + col * uvPixelStride
                if (vIdx < vBytes.size && uIdx < uBytes.size) {
                    nv21[pos++] = vBytes[vIdx]
                    nv21[pos++] = uBytes[uIdx]
                }
                col++
            }
            row++
        }
        return nv21
    }

    private fun rotateFast(jpegBytes: ByteArray, rotation: Int, isFront: Boolean): ByteArray {
        return try {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (isFront) postScale(-1f, 1f)
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bitmap.recycle(); rotated.recycle()
            out.toByteArray()
        } catch (e: Exception) { jpegBytes }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        camera.createCaptureSession(listOf(imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(imageReader!!.surface)
                    // Forzar máximo FPS
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                }
                session.setRepeatingRequest(request.build(), null, captureHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("Camera", "Session config failed")
            }
        }, captureHandler)
    }

    private fun getCameraId(useFront: Boolean): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (useFront) facing == CameraCharacteristics.LENS_FACING_FRONT
            else facing == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    fun stopCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
        baoPool.clear()
    }
}
