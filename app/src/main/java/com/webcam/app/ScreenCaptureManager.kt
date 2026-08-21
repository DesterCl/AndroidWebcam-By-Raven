package com.webcam.app

import android.content.Context
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

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stop() }
    }

    fun start(projection: MediaProjection, width: Int, height: Int, dpi: Int) {
        stop()
        mediaProjection = projection

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projection.registerCallback(projectionCallback, handler)
        }

        // Escalar a máximo 1280px de ancho para mantener FPS
        val scale = if (width > 1280) 1280f / width else 1f
        val capW  = (width  * scale).toInt()
        val capH  = (height * scale).toInt()

        // RGBA_8888 es el único formato garantizado por VirtualDisplay
        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane       = img.planes[0]
                val buffer      = plane.buffer
                val pixelStride = plane.pixelStride  // siempre 4 para RGBA
                val rowStride   = plane.rowStride    // puede ser > capW * 4

                // Ancho real del buffer (puede tener padding al final de cada fila)
                val bufferWidth = rowStride / pixelStride

                // Crear bitmap con el ancho real del buffer
                val bmp = Bitmap.createBitmap(bufferWidth, capH, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buffer)

                // Recortar al ancho real de la pantalla si hay padding
                val final = if (bufferWidth > capW)
                    Bitmap.createBitmap(bmp, 0, 0, capW, capH)
                else bmp

                frameBuffer.reset()
                final.compress(Bitmap.CompressFormat.JPEG, 80, frameBuffer)

                if (final !== bmp) { final.recycle() }
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
            imageReader!!.surface, null, handler
        )

        Log.d("SCREEN", "Started ${capW}x${capH} dpi=$dpi")
    }

    fun stop() {
        try {
            virtualDisplay?.release();  virtualDisplay = null
            imageReader?.close();       imageReader = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection?.unregisterCallback(projectionCallback)
            }
            mediaProjection?.stop();    mediaProjection = null
            frameBuffer.reset()
        } catch (e: Exception) { Log.e("SCREEN", "stop: ${e.message}") }
    }
}
