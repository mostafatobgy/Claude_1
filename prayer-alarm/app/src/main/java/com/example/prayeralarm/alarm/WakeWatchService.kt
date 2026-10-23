package com.example.prayeralarm.alarm

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.prayeralarm.LocaleHelper
import com.example.prayeralarm.R
import com.example.prayeralarm.data.AlarmGroup
import com.example.prayeralarm.data.Occurrence
import com.example.prayeralarm.data.Store
import java.time.ZonedDateTime

/**
 * Runs during the window before an alarm (Settings → "Ask if I unlock before an alarm").
 * The first time the phone is unlocked in that window, it asks whether to turn the coming alarm off,
 * then stops. Android only delivers "phone unlocked" to running apps, hence the service.
 */
class WakeWatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finish() }
    private var watchUntil = 0L
    private var registered = false
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onUnlocked()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        watchUntil = maxOf(watchUntil, intent?.getLongExtra(EXTRA_UNTIL, 0L) ?: 0L)
        // The notification shows the nearest alarm, with "Turn off today" and tap-to-open.
        val next = upcoming().firstOrNull()
        Notifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, Notifications.watching(this, next?.first, next?.second),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
        )
        val remaining = watchUntil - System.currentTimeMillis()
        if (remaining <= 0 || next == null) {
            finish()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH) return START_NOT_STICKY
        if (!registered) {
            ContextCompat.registerReceiver(
                this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, remaining)
        // Already using the phone when the window opens: ask right away.
        if (isUnlocked()) onUnlocked()
        return START_NOT_STICKY
    }

    private fun isUnlocked(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Groups whose first alarm of the session rings within the check window, nearest first. */
    private fun upcoming(): List<Pair<AlarmGroup, Occurrence>> {
        val store = Store(this)
        val window = store.appSettings.wakeCheckMinutes.toLong()
        val now = ZonedDateTime.now()
        val ctx = store.scheduleContext()
        return store.groups().mapNotNull { group ->
            group.nextOccurrence(now, ctx)
                ?.takeIf { it.index == 0 && !it.time.isAfter(now.plusMinutes(window)) }
                ?.let { group to it }
        }.sortedBy { it.second.time }
    }

    private fun onUnlocked() {
        upcoming().forEach { (group, next) ->
            Notifications.showEarlyWake(this, group.id, group.label.ifBlank { getString(R.string.alarm) }, next)
        }
        finish()
    }

    private fun finish() {
        handler.removeCallbacks(timeout)
        if (registered) {
            runCatching { unregisterReceiver(unlockReceiver) }
            registered = false
        }
        watchUntil = 0L
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(timeout)
        if (registered) runCatching { unregisterReceiver(unlockReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_UNTIL = "until"
        private const val ACTION_REFRESH = "com.example.prayeralarm.WATCH_REFRESH"
        @Volatile private var running = false
        private const val NOTIFICATION_ID = 2

        fun start(context: Context, until: Long) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, WakeWatchService::class.java).putExtra(EXTRA_UNTIL, until),
                )
            } catch (e: IllegalStateException) {
                // Android refused a background start (e.g. exact-alarm permission revoked); the alarm still rings.
            }
        }

        /** After an alarm was turned off or changed: update the notification, or stop if nothing is left. */
        fun refresh(context: Context) {
            if (!running) return
            try {
                context.startService(Intent(context, WakeWatchService::class.java).setAction(ACTION_REFRESH))
            } catch (e: IllegalStateException) {
                // Not allowed right now; the watch ends at the alarm time anyway.
            }
        }
    }
}
