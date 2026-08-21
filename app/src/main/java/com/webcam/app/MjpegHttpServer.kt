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
                var line = input.readLine()
                while (line != null && line.isNotEmpty()) { line = input.readLine() }
                when {
                    requestLine.contains("GET /frame")    -> serveFrame()
                    requestLine.contains("GET /stream")   -> serveStream()
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
            var lastFrameRef: ByteArray? = null
            while (!disconnected && isRunning) {
                val frame = latestFrame.get()
                if (frame != null && frame !== lastFrameRef) {
                    lastFrameRef = frame
                    try {
                        val header = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                        out.write(header.toByteArray(Charsets.UTF_8))
                        out.write(frame)
                        out.write("\r\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    } catch (e: Exception) {
                        disconnected = true; break
                    }
                } else {
                    Thread.sleep(2)
                }
            }
        }

        private fun serveIndex() {
            val out = output ?: return
            val audioPort = port + 1
            val html = """<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>AndroidWebcam</title>
  <style>
    *{margin:0;padding:0;box-sizing:border-box}
    body{background:#0a0a0a;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;color:#fff;font-family:system-ui,sans-serif}
    h1{font-size:20px;margin-bottom:12px;color:#4fc3f7}
    #wrap{position:relative;width:100%;max-width:1280px}
    canvas{width:100%;height:auto;display:block;border:1px solid #222;background:#111}
    #hud{position:absolute;top:8px;left:8px;background:rgba(0,0,0,0.6);padding:4px 10px;border-radius:6px;font-size:13px;color:#4CAF50;font-weight:bold}
    #bar{display:flex;gap:10px;margin-top:12px;align-items:center;flex-wrap:wrap;justify-content:center}
    button{padding:8px 16px;border-radius:6px;border:none;font-size:14px;cursor:pointer;background:#4CAF50;color:#fff;font-weight:bold}
    button:hover{opacity:0.85}
    #audioBtn{background:#1565C0}
    #status{font-size:12px;color:#aaa;margin-top:8px}
  </style>
</head>
<body>
  <h1>AndroidWebcam</h1>
  <div id="wrap">
    <canvas id="cv"></canvas>
    <div id="hud">FPS: <span id="fps">--</span></div>
  </div>
  <div id="bar">
    <button onclick="window.open('/snapshot','_blank')">Foto</button>
    <button id="audioBtn" onclick="toggleAudio()">Activar audio</button>
  </div>
  <div id="status">Conectando...</div>
  <audio id="aud" style="display:none"></audio>
<script>
  const canvas   = document.getElementById('cv');
  const ctx      = canvas.getContext('2d');
  const fpsEl    = document.getElementById('fps');
  const statusEl = document.getElementById('status');
  const audioEl  = document.getElementById('aud');
  const audioBtn = document.getElementById('audioBtn');
  let frameCount = 0, lastFpsTime = Date.now(), audioOn = false;
  const AUDIO_PORT = $audioPort;

  function nextFrame() {
    const img = new Image();
    img.onload = () => {
      if (canvas.width !== img.naturalWidth) {
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
      }
      ctx.drawImage(img, 0, 0);
      frameCount++;
      const now = Date.now();
      if (now - lastFpsTime >= 1000) {
        fpsEl.textContent = frameCount;
        frameCount = 0; lastFpsTime = now;
      }
      statusEl.textContent = 'Transmitiendo';
      requestAnimationFrame(nextFrame);
    };
    img.onerror = () => {
      statusEl.textContent = 'Reconectando...';
      setTimeout(nextFrame, 500);
    };
    img.src = '/frame?t=' + Date.now();
  }

  function toggleAudio() {
    if (audioOn) {
      audioEl.pause();
      audioEl.src = '';
      audioOn = false;
      audioBtn.textContent = 'Activar audio';
    } else {
      audioEl.src = 'http://' + location.hostname + ':' + AUDIO_PORT + '/audio';
      audioEl.play().catch(e => { statusEl.textContent = 'Error audio: ' + e.message; });
      audioOn = true;
      audioBtn.textContent = 'Audio ON';
    }
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
