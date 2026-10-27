package com.unshoo.pixelmusic.data.service.speakers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Keeps network timing alive while the player is paused or the screen is off. */
class SpeakerStreamService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("network_speakers", "Wi-Fi speakers", NotificationManager.IMPORTANCE_LOW))
        val disconnect = PendingIntent.getService(this, 0,
            Intent(this, SpeakerStreamService::class.java).setAction("disconnect"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "network_speakers")
            .setSmallIcon(com.unshoo.pixelmusic.R.drawable.rounded_cast_24)
            .setContentTitle("PixelMusic · Wi-Fi speakers")
            .setContentText("Speaker connection active")
            .setOngoing(true)
            .addAction(0, "Disconnect", disconnect)
            .build()
        ServiceCompat.startForeground(this, 9072, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:speakers").apply { acquire() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "disconnect" || NetworkSpeakers.connections.value.isEmpty()) {
            NetworkSpeakers.disconnectAll(); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        NetworkSpeakers.disconnectAll()
        stopSelf()
    }
    override fun onDestroy() {
        NetworkSpeakers.disconnectAll()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

