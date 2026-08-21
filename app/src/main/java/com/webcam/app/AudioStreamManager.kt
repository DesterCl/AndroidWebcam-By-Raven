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
    private val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    private val bufSize = minBuf * 4

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
                val input  = socket.getInputStream().bufferedReader()
                val output = socket.getOutputStream()

                // Leer request HTTP
                val requestLine = input.readLine() ?: return@Thread
                var line = input.readLine()
                while (line != null && line.isNotEmpty()) { line = input.readLine() }

                when {
                    requestLine.contains("/audio") -> {
                        // Cabecera HTTP para WAV streaming
                        output.write(
                            ("HTTP/1.1 200 OK\r\n" +
                             "Content-Type: audio/wav\r\n" +
                             "Transfer-Encoding: chunked\r\n" +
                             "Cache-Control: no-cache\r\n" +
                             "Access-Control-Allow-Origin: *\r\n" +
                             "Connection: keep-alive\r\n\r\n")
                            .toByteArray(Charsets.UTF_8)
                        )
                        // Enviar WAV header como primer chunk
                        val wavHeader = buildWavHeader()
                        writeChunk(output, wavHeader)
                        output.flush()
                        clients.add(output)
                        // El hilo queda vivo mientras el cliente esté conectado
                        // Se elimina en broadcast cuando falla la escritura
                    }
                    else -> {
                        val body = "AndroidWebcam Audio - use /audio endpoint".toByteArray()
                        output.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                             "Content-Length: ${body.size}\r\n\r\n")
                            .toByteArray(Charsets.UTF_8)
                        )
                        output.write(body)
                        output.flush()
                        socket.close()
                    }
                }
            } catch (e: Exception) {
                Log.e("AUDIO", "client: ${e.message}")
                runCatching { socket.close() }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun startRecording() {
        Thread {
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize
                )
                audioRecord!!.startRecording()
                val buffer = ByteArray(bufSize)
                while (isRunning) {
                    val read = audioRecord!!.read(buffer, 0, buffer.size)
                    if (read > 0) broadcast(buffer, read)
                }
            } catch (e: Exception) {
                Log.e("AUDIO", "record: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun broadcast(data: ByteArray, len: Int) {
        val dead = mutableListOf<OutputStream>()
        for (out in clients) {
            try {
                writeChunk(out, data, len)
                out.flush()
            } catch (e: Exception) {
                dead.add(out)
            }
        }
        if (dead.isNotEmpty()) clients.removeAll(dead)
    }

    // Chunked transfer encoding: tamaño en hex + \r\n + datos + \r\n
    private fun writeChunk(out: OutputStream, data: ByteArray, len: Int = data.size) {
        out.write("${len.toString(16)}\r\n".toByteArray(Charsets.UTF_8))
        out.write(data, 0, len)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun buildWavHeader(): ByteArray {
        val header = ByteArray(44)
        val dataSize = Int.MAX_VALUE
        val byteRate = SAMPLE_RATE * 2
        fun wi(off: Int, v: Int) { for (i in 0..3) header[off+i] = (v shr (i*8) and 0xff).toByte() }
        fun ws(off: Int, v: Int) { header[off] = (v and 0xff).toByte(); header[off+1] = (v shr 8 and 0xff).toByte() }
        "RIFF".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        wi(4, 36 + dataSize)
        "WAVE".forEachIndexed { i, c -> header[8+i] = c.code.toByte() }
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
