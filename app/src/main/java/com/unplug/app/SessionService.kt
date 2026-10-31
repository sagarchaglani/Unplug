package com.unplug.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

/** Foreground service that keeps the session alive and ends it when the timer runs out. */
class SessionService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val remaining = SessionPrefs.end(this) - System.currentTimeMillis()
        if (remaining <= 0) { stopSelf(); return START_NOT_STICKY }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("unplug_session", "Focus session", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "unplug_session")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Unplug is guarding your phone")
            .setContentText("Only urgent things will ring through.")
            .setOngoing(true).setContentIntent(open).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, n, type)

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, remaining)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val start = SessionPrefs.start(this)
        SessionPrefs.clear(this)
        if (start > 0) {
            val ctx = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                val dao = UnplugDb.get(ctx).dao()
                dao.purgeOlder(System.currentTimeMillis() - 7 * 24 * 3600_000L)
                val count = dao.countSince(start)
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel("unplug_summary", "Session summary", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE)
                nm.notify(2, NotificationCompat.Builder(ctx, "unplug_summary")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Session over")
                    .setContentText("$count notification(s) held back. Tap to review your digest.")
                    .setContentIntent(open).setAutoCancel(true).build())
            }
        }
        super.onDestroy()
    }

    companion object {
        fun start(c: Context, minutes: Int, commitment: Boolean) {
            SessionPrefs.begin(c, minutes, commitment)
            c.startForegroundService(Intent(c, SessionService::class.java))
        }
        fun stop(c: Context) { c.stopService(Intent(c, SessionService::class.java)) }
    }
}
