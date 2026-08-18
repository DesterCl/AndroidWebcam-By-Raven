package com.webcam.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
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

    private val backgroundThread = HandlerThread("CameraBackground").also { it.start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    @SuppressLint("MissingPermission")
    fun startCamera(width: Int, height: Int, useFrontCamera: Boolean) {
        val cameraId = getCameraId(useFrontCamera) ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val supportedSizes = map.getOutputSizes(ImageFormat.JPEG)
        val bestSize = supportedSizes
            .filter { it.width <= width && it.height <= height }
            .maxByOrNull { it.width * it.height }
            ?: supportedSizes.minByOrNull { Math.abs(it.width - width) + Math.abs(it.height - height) }!!

        imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.JPEG, 3)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                processFrame(image, sensorOrientation, useFrontCamera)
            } finally {
                image.close()
            }
        }, backgroundHandler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession(camera)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("Camera", "Error: $error"); camera.close()
            }
        }, backgroundHandler)
    }

    private fun processFrame(image: Image, sensorOrientation: Int, isFront: Boolean) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val finalBytes = if (sensorOrientation != 0) rotateBitmap(bytes, sensorOrientation, isFront) else bytes
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
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (isFront) postScale(-1f, 1f)
            }
            val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val out = ByteArrayOutputStream()
            rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            bitmap.recycle(); rotated.recycle()
            out.toByteArray()
        } catch (e: Exception) { jpegBytes }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        camera.createCaptureSession(listOf(imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(imageReader!!.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                }
                session.setRepeatingRequest(request.build(), null, backgroundHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("Camera", "Session config failed")
            }
        }, backgroundHandler)
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
    }
}
