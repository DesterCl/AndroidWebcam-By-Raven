package com.webcam.app

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class MjpegHttpServer(private val port: Int) {

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientHandler>()
    private val latestFrame = AtomicReference<ByteArray>(null)
    private var isRunning = false

    fun start() {
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    try {
                        val client = serverSocket!!.accept()
                        val handler = ClientHandler(client)
                        clients.add(handler)
                        handler.start()
                    } catch (e: Exception) { if (isRunning) e.printStackTrace() }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    fun stop() {
        isRunning = false
        clients.forEach { it.disconnect() }
        clients.clear()
        serverSocket?.close()
        serverSocket = null
    }

    fun updateFrame(jpegBytes: ByteArray) {
        latestFrame.set(jpegBytes)
        clients.removeAll { it.disconnected }
        clients.forEach { it.sendFrame(jpegBytes) }
    }

    inner class ClientHandler(private val socket: Socket) : Thread() {
        var disconnected = false
        private var output: OutputStream? = null

        override fun run() {
            try {
                val input = socket.getInputStream().bufferedReader()
                output = socket.getOutputStream()
                val requestLine = input.readLine() ?: return
                while (input.readLine()?.isNotEmpty() == true) {}
                when {
                    requestLine.contains("/video") -> serveStream()
                    requestLine.contains("/snapshot") -> serveSnapshot()
                    else -> serveIndex()
                }
            } catch (e: Exception) {
            } finally {
                disconnected = true
                runCatching { socket.close() }
            }
        }

        private fun serveStream() {
            val out = output ?: return
            val boundary = "mjpegboundary"
            out.write("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace;boundary=$boundary\r\nCache-Control: no-cache\r\n\r\n")
            out.flush()
            latestFrame.get()?.let { sendFrame(it) }
        }

        fun sendFrame(jpegBytes: ByteArray) {
            if (disconnected) return
            try {
                val out = output ?: return
                val header = "--mjpegboundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegBytes.size}\r\n\r\n"
                out.write(header.toByteArray())
                out.write(jpegBytes)
                out.write("\r\n".toByteArray())
                out.flush()
            } catch (e: Exception) { disconnected = true }
        }

        private fun serveSnapshot() {
            val out = output ?: return
            val frame = latestFrame.get()
            if (frame != null) {
                out.write("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                out.write(frame)
            } else {
                out.write("HTTP/1.0 503 Service Unavailable\r\n\r\n".toByteArray())
            }
            out.flush()
        }

        private fun serveIndex() {
            val out = output ?: return
            val html = """<!DOCTYPE html><html><head><title>AndroidWebcam</title>
<style>body{margin:0;background:#000;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;color:#fff;font-family:sans-serif}img{max-width:100%;max-height:90vh}a{color:#4af;margin-top:10px}</style>
</head><body><img src="/video"/><a href="/snapshot">📸 Capturar foto</a></body></html>"""
            out.write("HTTP/1.0 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\n\r\n$html".toByteArray())
            out.flush()
        }

        fun disconnect() { disconnected = true; runCatching { socket.close() } }
    }
}
