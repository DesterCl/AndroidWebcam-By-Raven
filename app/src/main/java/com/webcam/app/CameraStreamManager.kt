package com.webcam.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
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
    private val thread = HandlerThread("Cam").also { it.start() }
    private val handler = Handler(thread.looper)
    private var frameCount = 0
    private var lastFps = System.currentTimeMillis()
    private var sensorOrientation = 0
    private var isFront = false
    private var deviceRotation = 0

    fun setDeviceRotation(r: Int) { deviceRotation = r }

    @SuppressLint("MissingPermission")
    fun startCamera(width: Int, height: Int, useFront: Boolean) {
        isFront = useFront
        try {
            val id = cameraManager.cameraIdList.firstOrNull { camId ->
                val facing = cameraManager.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.LENS_FACING)
                if (useFront) facing == CameraCharacteristics.LENS_FACING_FRONT
                else facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: run { onErrorMessage("No se encontró cámara"); return }

            val ch  = cameraManager.getCameraCharacteristics(id)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

            // Resolución más conservadora: pedir YUV
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            val best  = sizes
                .filter { it.width <= width && it.height <= height }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.first()

            Log.d("CAM", "Opening ${best.width}x${best.height}")

            imageReader = ImageReader.newInstance(best.width, best.height, ImageFormat.YUV_420_888, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    // Convertir YUV → JPEG en el mismo hilo del reader
                    val yPlane = img.planes[0]
                    val uPlane = img.planes[1]
                    val vPlane = img.planes[2]
                    val w = img.width; val h = img.height
                    val nv21 = ByteArray(w * h * 3 / 2)
                    val yBuf = yPlane.buffer; val yStride = yPlane.rowStride
                    var idx = 0
                    for (row in 0 until h) {
                        yBuf.position(row * yStride)
                        val take = minOf(w, yBuf.remaining())
                        yBuf.get(nv21, idx, take); idx += take
                    }
                    val vBuf = vPlane.buffer; val uBuf = uPlane.buffer
                    val uvStride = uPlane.rowStride; val uvPixel = uPlane.pixelStride
                    for (row in 0 until h / 2) for (col in 0 until w / 2) {
                        val p = row * uvStride + col * uvPixel
                        if (p < vBuf.limit()) nv21[idx++] = vBuf.get(p)
                        if (p < uBuf.limit()) nv21[idx++] = uBuf.get(p)
                    }
                    val yuv  = YuvImage(nv21, ImageFormat.NV21, w, h, null)
                    val baos = ByteArrayOutputStream()
                    yuv.compressToJpeg(Rect(0, 0, w, h), 90, baos)
                    var jpeg = baos.toByteArray()

                    // Rotar si hace falta
                    val rot = if (isFront) (sensorOrientation + deviceRotation + 360) % 360
                              else         (sensorOrientation - deviceRotation + 360) % 360
                    if (rot != 0 || isFront) {
                        try {
                            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
                                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                            if (bmp != null) {
                                val mat = Matrix().apply {
                                    if (rot != 0) postRotate(rot.toFloat())
                                    if (isFront)  postScale(-1f, 1f)
                                }
                                val out2 = ByteArrayOutputStream()
                                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mat, false)
                                    .compress(Bitmap.CompressFormat.JPEG, 90, out2)
                                bmp.recycle()
                                jpeg = out2.toByteArray()
                            }
                        } catch (_: Exception) {}
                    }

                    server.updateFrame(jpeg)
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastFps >= 1000) { onFpsUpdate(frameCount); frameCount = 0; lastFps = now }
                } finally {
                    img.close()
                }
            }, handler)

            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    cameraDevice = cam
                    try {
                        cam.createCaptureSession(listOf(imageReader!!.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    captureSession = s
                                    try {
                                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(imageReader!!.surface)
                                            set(CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                            set(CaptureRequest.CONTROL_AE_MODE,
                                                CaptureRequest.CONTROL_AE_MODE_ON)
                                            set(CaptureRequest.CONTROL_AWB_MODE,
                                                CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                        }
                                        s.setRepeatingRequest(req.build(), null, handler)
                                    } catch (e: Exception) {
                                        Log.e("CAM", "req: ${e.message}")
                                        onErrorMessage("Error al configurar cámara: ${e.message}")
                                    }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    onErrorMessage("Fallo al configurar sesión")
                                }
                            }, handler)
                    } catch (e: Exception) {
                        Log.e("CAM", "session: ${e.message}")
                        onErrorMessage("Error sesión: ${e.message}")
                    }
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); cameraDevice = null }
                override fun onError(cam: CameraDevice, error: Int) {
                    val msg = when (error) {
                        1 -> "Error interno HAL"
                        2 -> "Error servicio cámara"
                        3 -> "Demasiadas cámaras abiertas"
                        4 -> "Cámara deshabilitada"
                        5 -> "Cámara en uso por otra app"
                        else -> "Error $error"
                    }
                    Log.e("CAM", "onError: $msg")
                    onErrorMessage(msg)
                    cam.close(); cameraDevice = null
                }
            }, handler)
        } catch (e: Exception) {
            Log.e("CAM", "startCamera: ${e.message}")
            onErrorMessage("Error: ${e.message}")
        }
    }

    fun stopCamera() {
        try {
            captureSession?.close(); captureSession = null
            cameraDevice?.close();   cameraDevice = null
            imageReader?.close();    imageReader = null
        } catch (e: Exception) { Log.e("CAM", "stop: ${e.message}") }
    }
}
