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
import com.example.prayeralarm.R
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        watchUntil = maxOf(watchUntil, intent?.getLongExtra(EXTRA_UNTIL, 0L) ?: 0L)
        Notifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, Notifications.watching(this, watchUntil),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
        )
        val remaining = watchUntil - System.currentTimeMillis()
        if (remaining <= 0) {
            finish()
            return START_NOT_STICKY
        }
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

    private fun onUnlocked() {
        val store = Store(this)
        val window = store.appSettings.wakeCheckMinutes.toLong()
        val now = ZonedDateTime.now()
        val ctx = store.scheduleContext()
        store.groups().forEach { group ->
            val next = group.nextOccurrence(now, ctx) ?: return@forEach
            if (next.index == 0 && !next.time.isAfter(now.plusMinutes(window))) {
                Notifications.showEarlyWake(this, group.id, group.label.ifBlank { getString(R.string.alarm) }, next)
            }
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
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        if (registered) runCatching { unregisterReceiver(unlockReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_UNTIL = "until"
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
    }
}
