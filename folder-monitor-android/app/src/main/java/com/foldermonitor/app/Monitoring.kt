package com.foldermonitor.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.StorageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.storage.StorageManager
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
        val targets = Store.targets(ctx)
        val rules = Store.rules(ctx)
        val history = Store.loadHistory(ctx)
        // v4 measures the phone like Settings does (system included); older phone sizes aren't comparable.
        if (Store.phoneMeasureVersion(ctx) < 4) {
            history.remove(Alert.PHONE)
            Store.setPhoneMeasureVersion(ctx, 4)
        }
        history.keys.retainAll(targets.toSet())
        for (key in targets) {
            if (key !in Store.targets(ctx)) continue // removed while we were scanning
            val h = history.getOrPut(key) { FolderHistory(key) }
            val started = System.currentTimeMillis()
            // Files changed since the previous check are the candidates for "what caused it".
            val since = minOf(h.lastCheck ?: (started - rules.windowMs), started - rules.windowMs)
            val isPhone = key == Alert.PHONE
            val result = Scanner.scan(if (isPhone) PhoneStorage.root else File(key), since)
            val now = System.currentTimeMillis()
            if (result.error != null) { h.error = result.error; continue }
            h.error = ""
            // The phone is measured by used storage (includes app data we can't read).
            val size = if (isPhone) PhoneStorage.used(ctx) else result.total
            h.add(Sample(now, size, result.children), rules.windowMs)

            val previous = Store.loadSnapshot(ctx, key)
            Growth.check(h, rules, now)?.let { basic ->
                val folders = if (previous != null) Culprits.folders(previous, result.dirs, basic.growth) else emptyList()
                var unexplained = 0L
                if (isPhone && previous != null) {
                    val inFiles = result.total - (previous[""] ?: result.total)
                    val rest = basic.growth - maxOf(inFiles, 0L)
                    if (rest >= maxOf(basic.growth * 3 / 10, 100 * Growth.MB)) unexplained = rest
                }
                val alert = basic.copy(folders = folders, files = result.recent, unexplained = unexplained)
                h.lastAlertText = alert.text()
                Notifications.alert(ctx, alert)
            }
            Store.saveSnapshot(ctx, key, result.dirs)
        }
        val keep = Store.targets(ctx)
        history.keys.retainAll(keep.toSet())
        Store.pruneSnapshots(ctx, keep)
        Store.saveHistory(ctx, history)
        Store.setLastRun(ctx, System.currentTimeMillis())
    }
}

/**
 * The phone's storage, measured the way Android's Settings does: total is the advertised size
 * (e.g. 256 GB) and "used" includes the Android system itself. Files are read from [root].
 */
object PhoneStorage {
    val root: File get() = Environment.getExternalStorageDirectory()

    /**
     * Settings shows the advertised size in 1000-based GB (e.g. "256 GB" = 256,000,000,000 bytes)
     * and used = that minus free space. Android's own API reports 256 × 1024³ instead, so the size
     * is derived from the data partition, rounded up to the advertised capacity.
     */
    private fun stats(ctx: Context): Pair<Long, Long> {
        val data = StatFs(Environment.getDataDirectory().path)
        val total = advertisedSize(data.totalBytes)
        val free = try {
            ctx.getSystemService(StorageStatsManager::class.java).getFreeBytes(StorageManager.UUID_DEFAULT)
        } catch (e: Exception) {
            data.availableBytes
        }
        return total to free
    }

    fun used(ctx: Context): Long = stats(ctx).let { (total, free) -> total - free }
    fun total(ctx: Context): Long = stats(ctx).first
}

/** Wakes the phone at the next check time (daily at the chosen hour, or every N minutes). */
object Scheduler {
    private fun pending(ctx: Context, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or flags
    )

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = Store.rules(ctx).nextRun(System.currentTimeMillis())
        val pi = pending(ctx)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        Store.setNextRun(ctx, at)
    }

    /** Schedule only if no alarm is pending (e.g. after the app was force-stopped). */
    fun ensureScheduled(ctx: Context) {
        val missing = pending(ctx, PendingIntent.FLAG_NO_CREATE) == null
        if (missing || Store.nextRun(ctx) < System.currentTimeMillis()) scheduleNext(ctx)
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
        pending(ctx).cancel()
        Store.setNextRun(ctx, 0L)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Store.isEnabled(context)) return
        Scheduler.scheduleNext(context) // first, so the schedule survives anything below
        MonitorService.check(context)
    }
}

/** Alarms are wiped on reboot and shifted by clock/time-zone changes: set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store.isEnabled(context)) Scheduler.scheduleNext(context)
    }
}

/**
 * Runs one check as a foreground service so Android lets it finish even for big folders,
 * then goes away. Shown only while checking.
 */
class MonitorService : Service() {
    companion object {
        fun check(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
            } catch (e: Exception) {
                // Android refused a foreground service from the background: do the check anyway.
                val wl = ctx.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FolderMonitor:check")
                wl.acquire(10 * 60_000L)
                Checker.runAsync(ctx) { if (wl.isHeld) wl.release() }
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.CHECKING_ID, Notifications.checking(this), type)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FolderMonitor:service")
                .apply { acquire(30 * 60_000L) }
        }
        Checker.runAsync(this) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
