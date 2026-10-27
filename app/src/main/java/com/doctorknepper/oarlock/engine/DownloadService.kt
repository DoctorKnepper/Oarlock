package com.doctorknepper.oarlock.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.doctorknepper.oarlock.Ark
import com.doctorknepper.oarlock.MainActivity
import com.doctorknepper.oarlock.R
import com.doctorknepper.oarlock.core.humanBytes

/** Foreground service that keeps the download queue running with the screen off. */
class DownloadService : Service() {
    private var worker: Thread? = null
    private var lastNotify = 0L
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Ark.settings.pausedByUser = true
            Ark.engine.stop()
            stopSelfSafely()
            return START_NOT_STICKY
        }
        startInForeground(build("Preparing downloads", "", 0, 0))
        if (worker?.isAlive != true) {
            acquireLocks()
            worker = Thread({
                try {
                    Ark.engine.runQueue { notifyProgress() }
                } finally {
                    releaseLocks()
                    finishedNotification()
                    stopSelfSafely()
                }
            }, "ark-downloads").apply { start() }
        }
        return START_STICKY
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTE_ID, n, type)
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Oarlock:downloads").apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL
        wifi = applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(mode, "Oarlock:downloads")?.apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { wake?.release() }; runCatching { wifi?.release() }
    }

    private fun notifyProgress() {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        val s = Ark.engine.state.value
        val speed = if (s.bytesPerSec > 0) " · ${humanBytes(s.bytesPerSec)}/s" else ""
        val left = s.queue.size - 1
        val title = (if (s.defconActive) "DEFCON 1 · " else "") + s.currentTitle
        val text = s.step + speed + if (left > 0) " · $left more queued" else ""
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, build(title, text, s.done, s.total))
    }

    private fun finishedNotification() {
        val s = Ark.engine.state.value
        val problems = s.notes.size
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (s.queue.isEmpty()) "Downloads finished" else "Downloads paused")
            .setContentText(if (problems > 0) "$problems pack(s) need attention — open Oarlock to see why" else "Everything you queued is ready offline")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTE_DONE_ID, n)
    }

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("open", "downloads"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun build(title: String, text: String, done: Long, total: Long): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { "Oarlock downloads" })
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Pause", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (total > 0) b.setProgress(1000, ((done.toDouble() / total) * 1000).toInt().coerceIn(0, 1000), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        const val CHANNEL = "downloads"
        const val CHANNEL_DONE = "downloads_done"
        const val NOTE_ID = 42
        const val NOTE_DONE_ID = 43
        const val ACTION_STOP = "stop"

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads in progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Downloads finished", NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** Starts (or resumes) downloading. Every caller is a user action or an automatic restart that already checked the pause flag. */
        fun start(ctx: Context) {
            Ark.settings.pausedByUser = false
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_STOP))
        }
    }
}
