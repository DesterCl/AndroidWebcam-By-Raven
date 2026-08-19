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
    var bandwidthKbps: Int = 200_000 // Default 200 Mbps
    var jpegQuality: Int = 95

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
    }

    inner class ClientHandler(private val socket: Socket) : Thread() {
        var disconnected = false
        private var output: OutputStream? = null

        override fun run() {
            try {
                socket.tcpNoDelay = true
                socket.setSendBufferSize(4 * 1024 * 1024)
                val input = socket.getInputStream().bufferedReader()
                output = socket.getOutputStream()
                val requestLine = input.readLine() ?: return
                // Leer todos los headers
                val headers = mutableMapOf<String, String>()
                var line = input.readLine()
                while (line != null && line.isNotEmpty()) {
                    val parts = line.split(": ", limit = 2)
                    if (parts.size == 2) headers[parts[0].lowercase()] = parts[1]
                    line = input.readLine()
                }
                when {
                    requestLine.contains("GET /frame") -> serveFrame()
                    requestLine.contains("GET /stream") -> serveStream()
                    requestLine.contains("GET /snapshot") -> serveFrame()
                    else -> serveIndex()
                }
            } catch (e: Exception) {
            } finally {
                disconnected = true
                runCatching { socket.close() }
            }
        }

        private fun serveFrame() {
            val out = output ?: return
            val frame = latestFrame.get()
            if (frame != null) {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(frame)
            } else {
                out.write("HTTP/1.1 503 Service Unavailable\r\n\r\n".toByteArray(Charsets.UTF_8))
            }
            out.flush()
        }

        private fun serveStream() {
            val out = output ?: return
            val boundary = "frame"
            out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$boundary\r\nCache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nConnection: keep-alive\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            while (!disconnected && isRunning) {
                val frame = latestFrame.get() ?: run { Thread.sleep(10); return@run null } ?: continue
                try {
                    val header = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                    out.write(header.toByteArray(Charsets.UTF_8))
                    out.write(frame)
                    out.write("\r\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                    // Calcular delay según ancho de banda configurado
                    val frameSizeBits = frame.size * 8L
                    val bitsPerMs = bandwidthKbps.toLong()
                    val transmitMs = frameSizeBits / bitsPerMs
                    val sleepMs = maxOf(0L, 33L - transmitMs) // target 30fps
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                } catch (e: Exception) {
                    disconnected = true
                    break
                }
            }
        }

        private fun serveIndex() {
            val out = output ?: return
            val html = """<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>AndroidWebcam</title>
  <style>
    *{margin:0;padding:0;box-sizing:border-box}
    body{background:#0a0a0a;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;color:#fff;font-family:system-ui,sans-serif}
    h1{font-size:20px;margin-bottom:16px;color:#4fc3f7;letter-spacing:1px}
    #wrap{position:relative;width:100%;max-width:1280px}
    canvas{width:100%;height:auto;display:block;border:1px solid #222;background:#111}
    #hud{position:absolute;top:8px;left:8px;background:rgba(0,0,0,0.6);padding:4px 10px;border-radius:6px;font-size:13px;color:#4CAF50;font-weight:bold}
    #controls{display:flex;gap:12px;margin-top:14px;align-items:center;flex-wrap:wrap;justify-content:center}
    select,button{padding:8px 14px;border-radius:6px;border:none;font-size:14px;cursor:pointer}
    select{background:#1e1e1e;color:#fff;border:1px solid #333}
    button{background:#4CAF50;color:#fff;font-weight:bold}
    button:hover{background:#43A047}
    #status{font-size:12px;color:#aaa;margin-top:8px}
    a{color:#4af;margin-top:10px;font-size:13px}
  </style>
</head>
<body>
  <h1>📷 AndroidWebcam</h1>
  <div id="wrap">
    <canvas id="cv"></canvas>
    <div id="hud">FPS: <span id="fps">--</span></div>
  </div>
  <div id="controls">
    <label style="color:#aaa;font-size:13px">Ancho de banda:</label>
    <select id="bw">
      <option value="50000">50 Mbps</option>
      <option value="100000">100 Mbps</option>
      <option value="200000" selected>200 Mbps</option>
      <option value="400000">400 Mbps</option>
      <option value="600000">600 Mbps (máximo)</option>
    </select>
    <button onclick="applyBw()">Aplicar</button>
    <a href="/snapshot" target="_blank">📸 Foto</a>
  </div>
  <div id="status">Conectando...</div>

<script>
  const canvas = document.getElementById('cv');
  const ctx = canvas.getContext('2d');
  const fpsEl = document.getElementById('fps');
  const statusEl = document.getElementById('status');
  let frameCount = 0, lastFpsTime = Date.now(), running = true;

  function applyBw() {
    const bw = document.getElementById('bw').value;
    fetch('/config?bw=' + bw).catch(()=>{});
  }

  function nextFrame() {
    if (!running) return;
    const img = new Image();
    const url = '/frame?t=' + Date.now();
    img.onload = function() {
      if (canvas.width !== img.naturalWidth) {
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
      }
      ctx.drawImage(img, 0, 0);
      URL.revokeObjectURL(img.src.startsWith('blob') ? img.src : '');

      frameCount++;
      const now = Date.now();
      if (now - lastFpsTime >= 1000) {
        fpsEl.textContent = frameCount;
        frameCount = 0;
        lastFpsTime = now;
      }
      statusEl.textContent = '✅ Transmitiendo';
      requestAnimationFrame(nextFrame);
    };
    img.onerror = function() {
      statusEl.textContent = '⚠️ Reconectando...';
      setTimeout(nextFrame, 500);
    };
    img.src = url;
  }

  nextFrame();
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
