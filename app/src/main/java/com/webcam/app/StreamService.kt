package com.webcam.app

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class StreamService : Service() {

    companion object {
        const val CHANNEL_ID  = "webcam_stream"
        const val NOTIF_ID    = 1
        const val ACTION_STOP = "com.webcam.app.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val stopIntent = Intent(this, StreamService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroidWebcam activo")
            .setContentText("Transmitiendo en segundo plano")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopPi)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notif)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Stream de cámara",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene el stream activo en segundo plano"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
