package com.example.prayeralarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.prayeralarm.MainActivity
import com.example.prayeralarm.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Notifications {
    const val ALARM_CHANNEL = "alarms"
    private const val UPCOMING_CHANNEL = "upcoming"

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
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, UPCOMING_CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(context.getString(R.string.upcoming_title, time))
            .setContentText(context.getString(R.string.upcoming_text, label, minutes.toInt()))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
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
}
