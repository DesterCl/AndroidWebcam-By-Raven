package com.webcam.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream

class ScreenCaptureManager(
    private val context: Context,
    private val server: MjpegHttpServer,
    private val onFpsUpdate: (Int) -> Unit
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val thread  = HandlerThread("ScreenCapture").also { it.start() }
    private val handler = Handler(thread.looper)

    private var frameCount  = 0
    private var lastFpsTime = System.currentTimeMillis()
    private val frameBuffer = ByteArrayOutputStream()

    // En Android 14+ MediaProjection requiere callback registrado antes de usarse
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d("SCREEN", "MediaProjection stopped")
            stop()
        }
    }

    fun start(projection: MediaProjection, width: Int, height: Int, dpi: Int) {
        // Detener cualquier sesión previa
        stop()

        mediaProjection = projection

        // CRÍTICO en Android 14+: registrar callback ANTES de crear VirtualDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projection.registerCallback(projectionCallback, handler)
        }

        // Reducir resolución si es muy alta para mantener FPS
        val scale  = if (width > 1280) 1280f / width else 1f
        val capW   = (width  * scale).toInt()
        val capH   = (height * scale).toInt()

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane       = img.planes[0]
                val buf         = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride   = plane.rowStride
                val rowPadding  = rowStride - pixelStride * capW

                val bmp = Bitmap.createBitmap(
                    capW + rowPadding / pixelStride, capH, Bitmap.Config.RGB_565)
                bmp.copyPixelsFromBuffer(buf)

                val cropped = if (rowPadding > 0)
                    Bitmap.createBitmap(bmp, 0, 0, capW, capH)
                else bmp

                frameBuffer.reset()
                cropped.compress(Bitmap.CompressFormat.JPEG, 80, frameBuffer)
                if (cropped !== bmp) cropped.recycle()
                bmp.recycle()

                server.updateFrame(frameBuffer.toByteArray())
                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTime >= 1000) {
                    onFpsUpdate(frameCount); frameCount = 0; lastFpsTime = now
                }
            } catch (e: Exception) {
                Log.e("SCREEN", "frame: ${e.message}")
            } finally {
                img.close()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "WebcamScreen", capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler)

        Log.d("SCREEN", "Started ${capW}x${capH}")
    }

    fun stop() {
        try {
            virtualDisplay?.release();  virtualDisplay = null
            imageReader?.close();       imageReader = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop();    mediaProjection = null
            frameBuffer.reset()
        } catch (e: Exception) { Log.e("SCREEN", "stop: ${e.message}") }
    }
}
