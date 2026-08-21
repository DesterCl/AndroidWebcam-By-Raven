package com.webcam.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class AudioStreamManager(private val port: Int) {

    private var audioRecord: AudioRecord? = null
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private var isRunning = false

    private val SAMPLE_RATE    = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT

    // Buffer MUY pequeño = latencia mínima
    // minBufferSize suele ser ~3500 bytes (~40ms a 44100Hz mono 16bit)
    private val minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    private val bufSize = minBuf  // sin multiplicar — mínima latencia posible

    fun start() {
        if (isRunning) return
        isRunning = true
        startServer()
        startRecording()
    }

    private fun startServer() {
        Thread {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    try {
                        val client = serverSocket!!.accept()
                        handleClient(client)
                    } catch (e: Exception) {
                        if (isRunning) Log.e("AUDIO", "accept: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("AUDIO", "server: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun handleClient(socket: Socket) {
        Thread {
            try {
                socket.tcpNoDelay = true
                // Buffer de envío pequeño — evita que el SO acumule datos
                socket.setSendBufferSize(bufSize * 2)
                val input  = socket.getInputStream().bufferedReader()
                val output = socket.getOutputStream()

                val requestLine = input.readLine() ?: return@Thread
                var line = input.readLine()
                while (line != null && line.isNotEmpty()) { line = input.readLine() }

                if (requestLine.contains("/audio")) {
                    // Responder con PCM raw sin contenedor
                    // El navegador lo maneja con Web Audio API
                    output.write(
                        ("HTTP/1.1 200 OK\r\n" +
                         "Content-Type: audio/wav\r\n" +
                         "Cache-Control: no-cache\r\n" +
                         "Access-Control-Allow-Origin: *\r\n" +
                         "Connection: keep-alive\r\n\r\n")
                        .toByteArray(Charsets.UTF_8)
                    )
                    // WAV header una sola vez al inicio
                    output.write(buildWavHeader())
                    output.flush()
                    // Registrar cliente para recibir PCM en tiempo real
                    clients.add(output)
                } else {
                    socket.close()
                }
            } catch (e: Exception) {
                Log.e("AUDIO", "handleClient: ${e.message}")
                runCatching { socket.close() }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun startRecording() {
        Thread {
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, // menor latencia que MIC
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize
                )
                // Prioridad máxima al hilo de audio
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                audioRecord!!.startRecording()
                val buffer = ByteArray(bufSize)
                while (isRunning) {
                    val read = audioRecord!!.read(buffer, 0, buffer.size)
                    if (read > 0) broadcast(buffer, read)
                }
            } catch (e: Exception) {
                Log.e("AUDIO", "record: ${e.message}")
            }
        }.also {
            it.isDaemon = true
            it.priority = Thread.MAX_PRIORITY
        }.start()
    }

    private fun broadcast(data: ByteArray, len: Int) {
        val dead = mutableListOf<OutputStream>()
        for (out in clients) {
            try {
                out.write(data, 0, len)
                out.flush() // flush inmediato por cada chunk
            } catch (e: Exception) {
                dead.add(out)
            }
        }
        if (dead.isNotEmpty()) clients.removeAll(dead)
    }

    private fun buildWavHeader(): ByteArray {
        val header   = ByteArray(44)
        val dataSize = Int.MAX_VALUE
        val byteRate = SAMPLE_RATE * 2
        fun wi(off: Int, v: Int) { for (i in 0..3) header[off+i] = (v shr (i*8) and 0xff).toByte() }
        fun ws(off: Int, v: Int) { header[off] = (v and 0xff).toByte(); header[off+1] = (v shr 8 and 0xff).toByte() }
        "RIFF".forEachIndexed { i, c -> header[i]   = c.code.toByte() }
        wi(4, 36 + dataSize)
        "WAVE".forEachIndexed { i, c -> header[8+i]  = c.code.toByte() }
        "fmt ".forEachIndexed { i, c -> header[12+i] = c.code.toByte() }
        wi(16, 16); ws(20, 1); ws(22, 1)
        wi(24, SAMPLE_RATE); wi(28, byteRate); ws(32, 2); ws(34, 16)
        "data".forEachIndexed { i, c -> header[36+i] = c.code.toByte() }
        wi(40, dataSize)
        return header
    }

    fun stop() {
        isRunning = false
        try { audioRecord?.stop(); audioRecord?.release(); audioRecord = null } catch (e: Exception) {}
        try { clients.clear() } catch (e: Exception) {}
        try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
    }
}
