package com.webcam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
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

    fun start(projection: MediaProjection, width: Int, height: Int, dpi: Int) {
        mediaProjection = projection

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane  = img.planes[0]
                val buf    = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride   = plane.rowStride
                val rowPadding  = rowStride - pixelStride * width
                val bmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buf)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
                bmp.recycle()
                frameBuffer.reset()
                cropped.compress(Bitmap.CompressFormat.JPEG, 85, frameBuffer)
                cropped.recycle()
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
            "WebcamScreen", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler)
    }

    fun stop() {
        try {
            virtualDisplay?.release(); virtualDisplay = null
            imageReader?.close();      imageReader = null
            mediaProjection?.stop();   mediaProjection = null
            frameBuffer.reset()
        } catch (e: Exception) { Log.e("SCREEN", "stop: ${e.message}") }
    }
}
