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
                while (line != null && line.isNotEmpty()) { line =
