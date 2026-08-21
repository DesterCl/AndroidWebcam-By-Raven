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
    private val clients = CopyOnWriteArrayList<Socket>()
    private var isRunning = false

    private val SAMPLE_RATE   = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    fun start() {
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
                        clients.add(client)
                        // Enviar cabecera WAV al conectar (PCM raw con header)
                        sendWavHeader(client.getOutputStream())
                    } catch (e: Exception) {
                        if (isRunning) Log.e("AUDIO", "accept: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("AUDIO", "server: ${e.message}")
            }
        }.start()
    }

    private fun sendWavHeader(out: OutputStream) {
        // WAV header para PCM 44100Hz mono 16bit streaming
        // DataSize = 0x7FFFFFFF (streaming, tamaño desconocido)
        val header = ByteArray(44)
        val dataSize = 0x7FFFFFFF
        val byteRate = SAMPLE_RATE * 2 // mono * 16bit/8
        header[ 0] = 'R'.code.toByte(); header[ 1] = 'I'.code.toByte()
        header[ 2] = 'F'.code.toByte(); header[ 3] = 'F'.code.toByte()
        writeInt(header,  4, 36 + dataSize)
        header[ 8] = 'W'.code.toByte(); header[ 9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt(header, 16, 16)           // subchunk size
        writeShort(header, 20, 1)          // PCM format
        writeShort(header, 22, 1)          // mono
        writeInt(header, 24, SAMPLE_RATE)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, 2)          // block align
        writeShort(header, 34, 16)         // bits per sample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeInt(header, 40, dataSize)
        try { out.write(header); out.flush() } catch (e: Exception) {}
    }

    private fun startRecording() {
        Thread {
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 4
                )
                audioRecord!!.startRecording()
                val buffer = ByteArray(bufferSize)
                while (isRunning) {
                    val read = audioRecord!!.read(buffer, 0, buffer.size)
                    if (read > 0) broadcast(buffer, read)
                }
            } catch (e: Exception) {
                Log.e("AUDIO", "record: ${e.message}")
            }
        }.start()
    }

    private fun broadcast(data: ByteArray, len: Int) {
        val dead = mutableListOf<Socket>()
        for (client in clients) {
            try {
                client.getOutputStream().write(data, 0, len)
                client.getOutputStream().flush()
            } catch (e: Exception) {
                dead.add(client)
            }
        }
        clients.removeAll(dead)
    }

    fun stop() {
        isRunning = false
        try { audioRecord?.stop(); audioRecord?.release(); audioRecord = null } catch (e: Exception) {}
        try { clients.forEach { it.close() }; clients.clear() } catch (e: Exception) {}
        try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
    }

    private fun writeInt(arr: ByteArray, offset: Int, value: Int) {
        arr[offset]     = (value and 0xff).toByte()
        arr[offset + 1] = (value shr 8  and 0xff).toByte()
        arr[offset + 2] = (value shr 16 and 0xff).toByte()
        arr[offset + 3] = (value shr 24 and 0xff).toByte()
    }

    private fun writeShort(arr: ByteArray, offset: Int, value: Int) {
        arr[offset]     = (value and 0xff).toByte()
        arr[offset + 1] = (value shr 8 and 0xff).toByte()
    }
}
