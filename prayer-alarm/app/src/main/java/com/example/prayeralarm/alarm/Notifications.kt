package com.example.prayeralarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.example.prayeralarm.MainActivity
import com.example.prayeralarm.R
import com.example.prayeralarm.data.AlarmGroup
import com.example.prayeralarm.data.Occurrence
import com.example.prayeralarm.prayer.Prayer
import com.example.prayeralarm.ui.prayerName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Notifications {
    const val ALARM_CHANNEL = "alarms"
    private const val UPCOMING_CHANNEL = "upcoming"
    private const val WATCH_CHANNEL = "watch_v2"
    private const val EARLY_WAKE_CHANNEL = "early_wake"
    private const val PRAYER_STATUS_CHANNEL = "prayer_countdown"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // The service plays the sound itself, so the alarm channel stays silent.
        nm.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL, context.getString(R.string.alarms), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                UPCOMING_CHANNEL, context.getString(R.string.upcoming_alarms), NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        // Silent: shown while waiting for an unlock before an alarm (Android requires a notification for that).
        nm.deleteNotificationChannel("watch")
        nm.createNotificationChannel(
            NotificationChannel(WATCH_CHANNEL, context.getString(R.string.watch_channel), NotificationManager.IMPORTANCE_LOW)
        )
        // Silent: a countdown that updates by itself shouldn't make noise.
        nm.createNotificationChannel(
            NotificationChannel(PRAYER_STATUS_CHANNEL, context.getString(R.string.prayer_countdown_channel), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(EARLY_WAKE_CHANNEL, context.getString(R.string.early_wake_channel), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** "Alarm at 6:00 — rings in 5 min" with a Skip button. Disappears when the alarm rings. */
    fun showReminder(context: Context, groupId: Int, label: String, triggerAt: Long, sessionEnd: Long) {
        ensureChannels(context)
        val time = Instant.ofEpochMilli(triggerAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        val minutes = ((triggerAt - System.currentTimeMillis() + 30_000) / 60_000).coerceAtLeast(0)
        val skip = PendingIntent.getBroadcast(
            context, groupId,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmScheduler.ACTION_SKIP)
                .putExtra(AlarmScheduler.EXTRA_ID, groupId)
                .putExtra(AlarmScheduler.EXTRA_SESSION_END, sessionEnd),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, UPCOMING_CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(context.getString(R.string.upcoming_title, time))
            .setContentText(context.getString(R.string.upcoming_text, label, minutes.toInt()))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setTimeoutAfter((triggerAt - System.currentTimeMillis()).coerceAtLeast(1_000))
            .addAction(0, context.getString(R.string.skip), skip)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(AlarmScheduler.reminderNotificationId(groupId), notification)
        } catch (e: SecurityException) {
            // Notification permission not granted; the alarm itself still rings.
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun skipIntent(context: Context, action: String, groupId: Int, sessionEnd: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, groupId,
            Intent(context, AlarmReceiver::class.java)
                .setAction(action)
                .putExtra(AlarmScheduler.EXTRA_ID, groupId)
                .putExtra(AlarmScheduler.EXTRA_SESSION_END, sessionEnd),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Quiet notification while waiting for an unlock: "Alarm at 6:00 · Work", a countdown, and "Turn off today". */
    fun watching(context: Context, group: AlarmGroup?, next: Occurrence?): Notification {
        val builder = NotificationCompat.Builder(context, WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(openApp(context))
        if (group == null || next == null) return builder.setContentTitle(context.getString(R.string.app_name)).build()
        val time = next.time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        val millis = next.time.toInstant().toEpochMilli()
        return builder
            .setContentTitle(context.getString(R.string.upcoming_title, time))
            .setContentText(group.label.ifBlank { context.getString(R.string.alarm) })
            .setWhen(millis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .addAction(
                0, context.getString(R.string.turn_off_today),
                skipIntent(context, AlarmScheduler.ACTION_SKIP, group.id, next.sessionEnd.toInstant().toEpochMilli()),
            )
            .build()
    }

    /**
     * Before a prayer: "Asr -29:50" counting down to the adhan. From the prayer time: "Asr +0:10"
     * counting up, removed after [minutes]. Android's chronometer keeps the time ticking by itself.
     */
    fun showPrayerStatus(context: Context, prayer: Prayer, prayerTime: Long, minutes: Int, elapsed: Boolean) {
        ensureChannels(context)
        val name = context.prayerName(prayer)
        // Same instant on the chronometer's clock (time since boot).
        val base = SystemClock.elapsedRealtime() + (prayerTime - System.currentTimeMillis())
        // Keep "-29:50" together left-to-right inside Arabic text.
        val format = "$name \u2066${if (elapsed) "+" else "-"}%s\u2069"
        val view = RemoteViews(context.packageName, R.layout.notification_prayer_status).apply {
            setChronometer(R.id.prayer_chronometer, base, format, true)
            setChronometerCountDown(R.id.prayer_chronometer, !elapsed)
        }
        val endsAt = if (elapsed) prayerTime + minutes * 60_000L else prayerTime + 60_000L
        val notification = NotificationCompat.Builder(context, PRAYER_STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_mosque)
            .setContentTitle(name)
            .setCustomContentView(view)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(context))
            .setTimeoutAfter((endsAt - System.currentTimeMillis()).coerceAtLeast(1_000))
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(AlarmScheduler.PRAYER_STATUS_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted.
        }
    }

    /** "You're up before your alarm. Turn off the 6:00 alarm for today?" with Turn off / Keep. */
    fun showEarlyWake(context: Context, groupId: Int, label: String, next: Occurrence) {
        ensureChannels(context)
        val time = next.time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        fun action(action: String) = skipIntent(context, action, groupId, next.sessionEnd.toInstant().toEpochMilli())
        val notification = NotificationCompat.Builder(context, EARLY_WAKE_CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(context.getString(R.string.early_wake_title))
            .setContentText(context.getString(R.string.early_wake_text, time, label))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setTimeoutAfter((next.time.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1_000))
            .addAction(0, context.getString(R.string.turn_off_today), action(AlarmScheduler.ACTION_SKIP))
            .addAction(0, context.getString(R.string.keep_alarm), action(AlarmScheduler.ACTION_KEEP))
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(AlarmScheduler.earlyWakeNotificationId(groupId), notification)
        } catch (e: SecurityException) {
            // Notification permission not granted.
        }
    }
}
