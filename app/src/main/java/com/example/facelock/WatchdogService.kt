package com.example.facelock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Backup detector: polls UsageStats so protection survives an Accessibility event being missed. */
class WatchdogService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastHandled = 0L
    private val loop = object : Runnable {
        override fun run() { poll(); handler.postDelayed(this, 600) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("guard", "App protection", NotificationManager.IMPORTANCE_MIN))
        val n: Notification = NotificationCompat.Builder(this, "guard")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("FaceLock is active")
            .setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        handler.removeCallbacks(loop); handler.post(loop)
        return START_STICKY
    }

    private fun poll() {
        if (!SecureStore.hasFace(this) || !Permissions.usage(this)) return
        val usm = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 3000, now)
        val e = UsageEvents.Event()
        var pkg: String? = null; var ts = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && e.timeStamp > lastHandled) {
                pkg = e.packageName; ts = e.timeStamp
            }
        }
        if (pkg != null) { lastHandled = ts; LockPolicy.onForeground(this, pkg) }
    }

    override fun onDestroy() { handler.removeCallbacks(loop); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchdogService::class.java))
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && SecureStore.hasFace(context) && Permissions.usage(context)) {
            WatchdogService.start(context)
        }
    }
}
