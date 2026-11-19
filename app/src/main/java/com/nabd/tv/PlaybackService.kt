package com.nabd.tv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Lets the player screen ask the app to stop when the notification's Stop button is pressed. */
object PlaybackBus {
    @Volatile var stop: (() -> Unit)? = null
    /** True while a stream is playing. */
    @Volatile var active = false
}

/**
 * Foreground service that runs while a stream is playing. It keeps the process alive when the app is in
 * the background (audio-only listening, screen off) and shows a notification with a Stop button.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)   // works on API 21+
            stopSelf()
            Handler(Looper.getMainLooper()).post { PlaybackBus.stop?.invoke() }
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val title = intent?.getStringExtra("title") ?: "Nabd TV"
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("Nabd TV · قيد التشغيل")
            .setContentIntent(open)
            .addAction(0, "إيقاف", stop)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_STOP = "com.nabd.tv.PLAYBACK_STOP"
        private const val CHANNEL = "playback"
        private const val ID = 4711

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "التشغيل", NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun start(ctx: Context, title: String) {
            PlaybackBus.active = true
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java).putExtra("title", title)) }
        }

        fun stop(ctx: Context) {
            PlaybackBus.active = false
            runCatching { ctx.stopService(Intent(ctx, PlaybackService::class.java)) }
        }
    }
}
