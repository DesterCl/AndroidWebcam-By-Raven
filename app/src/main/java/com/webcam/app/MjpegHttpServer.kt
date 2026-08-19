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
        private var isStreamClient = false

        override fun run() {
            try {
                socket.tcpNoDelay = true
                socket.setSendBufferSize(1024 * 1024)
                val input = socket.getInputStream().bufferedReader()
                output = socket.getOutputStream()
                val requestLine = input.readLine() ?: return
                while (input.readLine()?.isNotEmpty() == true) {}
                when {
                    requestLine.contains("/stream") -> { isStreamClient = true; serveStream() }
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
            val boundary = "frame"
            out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$boundary\r\nCache-Control: no-cache, no-store, must-revalidate\r\nPragma: no-cache\r\nExpires: 0\r\nAccess-Control-Allow-Origin: *\r\nConnection: keep-alive\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.flush()

            // Enviar frames en loop hasta que el cliente se desconecte
            while (!disconnected && isRunning) {
                val frame = latestFrame.get()
                if (frame != null) {
                    try {
                        val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                        out.write(header.toByteArray(Charsets.UTF_8))
                        out.write(frame)
                        out.write("\r\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    } catch (e: Exception) {
                        disconnected = true
                        break
                    }
                }
                Thread.sleep(33) // ~30fps máximo
            }
        }

        fun sendFrame(jpegBytes: ByteArray) {
            // Solo usado para clientes legacy, el stream loop maneja los de /stream
        }

        private fun serveSnapshot() {
            val out = output ?: return
            val frame = latestFrame.get()
            if (frame != null) {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(frame)
            } else {
                out.write("HTTP/1.1 503 Service Unavailable\r\n\r\n".toByteArray(Charsets.UTF_8))
            }
            out.flush()
        }

        private fun serveIndex() {
            val out = output ?: return
            val html = """<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>AndroidWebcam</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body { background: #000; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100vh; color: #fff; font-family: sans-serif; }
    h1 { font-size: 18px; margin-bottom: 12px; color: #4fc3f7; }
    #stream { max-width: 100%; max-height: 90vh; border: 2px solid #333; }
    #info { margin-top: 10px; font-size: 13px; color: #aaa; }
    #fps { color: #4CAF50; font-weight: bold; }
    a { color: #4af; margin-top: 12px; display: inline-block; }
  </style>
</head>
<body>
  <h1>📷 AndroidWebcam</h1>
  <img id="stream" src="/stream" alt="Cargando stream..."/>
  <div id="info">FPS: <span id="fps">--</span> | <span id="status">Conectando...</span></div>
  <a href="/snapshot" target="_blank">📸 Capturar foto</a>
  <script>
    const img = document.getElementById('stream');
    const fpsEl = document.getElementById('fps');
    const statusEl = document.getElementById('status');
    let frameCount = 0;
    let lastTime = Date.now();

    img.onload = function() {
      frameCount++;
      const now = Date.now();
      if (now - lastTime >= 1000) {
        fpsEl.textContent = frameCount;
        frameCount = 0;
        lastTime = now;
      }
      statusEl.textContent = 'Transmitiendo ✅';
    };

    img.onerror = function() {
      statusEl.textContent = 'Reconectando...';
      setTimeout(() => { img.src = '/stream?t=' + Date.now(); }, 1000);
    };
  </script>
</body>
</html>"""
            val bytes = html.toByteArray(Charsets.UTF_8)
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }

        fun disconnect() { disconnected = true; runCatching { socket.close() } }
    }
}
