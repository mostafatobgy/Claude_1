package com.foldermonitor.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Runs one measurement of every folder and raises alerts. */
object Checker {
    const val ACTION_UPDATED = "com.foldermonitor.app.UPDATED"
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    val isBusy get() = busy.get()

    /** Queue a check unless one is already running; [done] always runs afterwards. */
    fun runAsync(ctx: Context, done: () -> Unit = {}) {
        val app = ctx.applicationContext
        if (!busy.compareAndSet(false, true)) { done(); return }
        executor.execute {
            try { runNow(app) } catch (e: Exception) { } finally { busy.set(false); done() }
            app.sendBroadcast(Intent(ACTION_UPDATED).setPackage(app.packageName))
        }
    }

    private fun runNow(ctx: Context) {
        val folders = Store.folders(ctx)
        val rules = Store.rules(ctx)
        val history = Store.loadHistory(ctx)
        history.keys.retainAll(folders.toSet())
        for (path in folders) {
            if (path !in Store.folders(ctx)) continue // removed while we were scanning
            val h = history.getOrPut(path) { FolderHistory(path) }
            val result = Scanner.scan(File(path))
            val now = System.currentTimeMillis()
            if (result.error != null) { h.error = result.error; continue }
            h.error = ""
            h.add(Sample(now, result.total, result.children), rules.windowMs)
            Growth.check(h, rules, now)?.let { Notifications.alert(ctx, it) }
        }
        history.keys.retainAll(Store.folders(ctx).toSet())
        Store.saveHistory(ctx, history)
        Store.setLastRun(ctx, System.currentTimeMillis())
        Notifications.updateService(ctx)
    }
}

/** Wakes the phone at each interval (also in battery-saving deep sleep, as often as Android allows). */
object Scheduler {
    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + Store.rules(ctx).intervalMin * 60_000L
        val pi = pending(ctx)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Store.isEnabled(context)) return
        Scheduler.scheduleNext(context) // first, so the loop survives anything below
        MonitorService.start(context, MonitorService.ACTION_KEEPALIVE)
        val wl = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FolderMonitor:check")
        wl.acquire(10 * 60_000L)
        Checker.runAsync(context) { if (wl.isHeld) wl.release() }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store.isEnabled(context)) MonitorService.start(context)
    }
}

/**
 * Keeps the app alive while monitoring, shown as a quiet ongoing notification.
 * The actual timing comes from [Scheduler] alarms.
 */
class MonitorService : Service() {
    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_KEEPALIVE = "keepalive"

        fun start(ctx: Context, action: String? = null) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java).setAction(action))
            } catch (e: Exception) {
                // Android may refuse from the background; the alarms keep checking anyway.
            }
        }

        fun stop(ctx: Context) {
            Store.setEnabled(ctx, false)
            Scheduler.cancel(ctx)
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.SERVICE_ID, Notifications.service(this), type)
        if (intent?.action == ACTION_STOP || !Store.isEnabled(this)) {
            stop(this)
            sendBroadcast(Intent(Checker.ACTION_UPDATED).setPackage(packageName))
            stopSelf()
            return START_NOT_STICKY
        }
        // Fresh start (not an alarm-triggered keep-alive): check now and set the next alarm.
        if (intent?.action != ACTION_KEEPALIVE) {
            Scheduler.scheduleNext(this)
            if (!Checker.isBusy) Checker.runAsync(this)
        }
        return START_STICKY
    }
}
