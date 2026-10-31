package com.unplug.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class UnplugListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!SessionPrefs.isActive(this)) return
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val pkg = sbn.packageName
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) { pkg }

        scope.launch {
            val dao = UnplugDb.get(this@UnplugListenerService).dao()
            val d = DecisionEngine.decide(pkg, title, text, n.category, dao.rulesNow())
            if (d.ring) alert(app, title, text, d.reason)
            else {
                cancelNotification(sbn.key)
                dao.insertHeld(HeldNotification(pkg = pkg, app = app, sender = title,
                    text = text, time = System.currentTimeMillis(), reason = d.reason))
            }
        }
    }

    /** The original notification is left untouched; we add a loud alert on top. */
    private fun alert(app: String, title: String, text: String, reason: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("unplug_alert", "Urgent alerts", NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(this, "unplug_alert")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Urgent · $app")
            .setContentText("$title: $text")
            .setSubText(reason)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true).build()
        nm.notify(System.currentTimeMillis().toInt(), n)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
