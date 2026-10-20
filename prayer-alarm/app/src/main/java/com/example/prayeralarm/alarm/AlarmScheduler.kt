package com.example.prayeralarm.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.prayeralarm.MainActivity
import com.example.prayeralarm.R
import com.example.prayeralarm.data.AlarmGroup
import com.example.prayeralarm.data.Occurrence
import com.example.prayeralarm.data.PrayerSettings
import com.example.prayeralarm.data.Repeat
import com.example.prayeralarm.data.Store
import com.example.prayeralarm.prayer.Prayer
import com.example.prayeralarm.ui.prayerName
import java.time.ZonedDateTime

object AlarmScheduler {
    const val ACTION_FIRE = "com.example.prayeralarm.FIRE"
    const val ACTION_REMIND = "com.example.prayeralarm.REMIND"
    const val ACTION_SKIP = "com.example.prayeralarm.SKIP"
    const val ACTION_KEEP = "com.example.prayeralarm.KEEP"
    const val ACTION_WATCH = "com.example.prayeralarm.WATCH"

    const val EXTRA_KIND = "kind"
    const val EXTRA_ID = "id"
    const val EXTRA_LABEL = "label"
    const val EXTRA_TRIGGER = "trigger"
    const val EXTRA_INDEX = "index"
    const val EXTRA_SESSION_END = "session_end"
    const val EXTRA_SOUND = "sound"
    const val EXTRA_IS_PRAYER = "is_prayer"
    const val KIND_GROUP = "group"
    const val KIND_PRAYER = "prayer"
    const val KIND_SNOOZE = "snooze"

    // Request codes: groups use their id; prayers, snooze and reminders sit far above that range.
    private const val PRAYER_REQUEST_BASE = 100_000
    private const val SNOOZE_REQUEST = 200_000
    private const val REMINDER_REQUEST_BASE = 300_000
    private const val WATCH_REQUEST_BASE = 400_000
    private const val EARLY_WAKE_NOTIFICATION_BASE = 500_000

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Re-arms everything. Called on app start, boot, time/timezone change and settings changes. */
    fun rescheduleAll(context: Context) {
        val store = Store(context)
        store.groups().forEach { scheduleGroup(context, it, store = store) }
        reschedulePrayers(context, store.settings)
    }

    fun reschedulePrayers(context: Context, settings: PrayerSettings = Store(context).settings) {
        Prayer.entries.forEach { schedulePrayer(context, settings, it) }
    }

    /** Arms the group's next alarm (and its heads-up reminder); returns it, or null if nothing is due. */
    fun scheduleGroup(
        context: Context,
        group: AlarmGroup,
        from: ZonedDateTime = ZonedDateTime.now(),
        store: Store = Store(context),
    ): Occurrence? {
        cancel(context, ACTION_REMIND, REMINDER_REQUEST_BASE + group.id)
        cancel(context, ACTION_WATCH, WATCH_REQUEST_BASE + group.id)
        val next = group.nextOccurrence(from, store.scheduleContext())
        if (next == null) {
            cancel(context, ACTION_FIRE, group.id)
            return null
        }
        val label = group.label.ifBlank { context.getString(R.string.alarm) }
        setAlarmClock(context, group.id, next.time, fireIntent(context, KIND_GROUP, group.id, label, next.time).apply {
            putExtra(EXTRA_INDEX, next.index)
            putExtra(EXTRA_SESSION_END, next.sessionEnd.toInstant().toEpochMilli())
            putExtra(EXTRA_SOUND, group.soundUri)
        })

        // Heads-up and the unlock check cover the first alarm of a session only, not each one in the group.
        val appSettings = store.appSettings
        val now = ZonedDateTime.now()
        val watchAt = next.time.minusMinutes(appSettings.wakeCheckMinutes.toLong())
        if (appSettings.wakeCheckMinutes > 0 && next.index == 0 && watchAt.isAfter(now)) {
            val intent = receiverIntent(context, ACTION_WATCH)
                .putExtra(EXTRA_ID, group.id)
                .putExtra(EXTRA_TRIGGER, next.time.toInstant().toEpochMilli())
            setReminder(context, WATCH_REQUEST_BASE + group.id, watchAt, intent)
        }
        val reminder = appSettings.reminderMinutes
        val remindAt = next.time.minusMinutes(reminder.toLong())
        if (reminder > 0 && next.index == 0 && remindAt.isAfter(now)) {
            val intent = receiverIntent(context, ACTION_REMIND)
                .putExtra(EXTRA_ID, group.id)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_TRIGGER, next.time.toInstant().toEpochMilli())
                .putExtra(EXTRA_SESSION_END, next.sessionEnd.toInstant().toEpochMilli())
            setReminder(context, REMINDER_REQUEST_BASE + group.id, remindAt, intent)
        }
        return next
    }

    /** Skips the rest of a session (or the next one): "skip next" in the app/reminder, or "I'm awake". */
    fun skipSession(context: Context, groupId: Int, sessionEndMillis: Long) {
        val store = Store(context)
        val group = store.group(groupId) ?: return
        val updated = if (group.repeat == Repeat.ONCE) group.copy(enabled = false)
        else group.copy(skipUntil = maxOf(group.skipUntil, sessionEndMillis))
        store.upsert(updated)
        scheduleGroup(context, updated, store = store)
        cancelNotifications(context, groupId)
    }

    fun skipNext(context: Context, group: AlarmGroup) {
        val next = group.nextOccurrence(ZonedDateTime.now(), Store(context).scheduleContext()) ?: return
        skipSession(context, group.id, next.sessionEnd.toInstant().toEpochMilli())
    }

    fun cancelGroup(context: Context, groupId: Int) {
        cancel(context, ACTION_FIRE, groupId)
        cancel(context, ACTION_REMIND, REMINDER_REQUEST_BASE + groupId)
        cancel(context, ACTION_WATCH, WATCH_REQUEST_BASE + groupId)
        cancelNotifications(context, groupId)
    }

    /** Removes the heads-up and "you're up early" notifications of a group. */
    fun cancelNotifications(context: Context, groupId: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(reminderNotificationId(groupId))
        nm.cancel(earlyWakeNotificationId(groupId))
    }

    fun reminderNotificationId(groupId: Int) = REMINDER_REQUEST_BASE + groupId

    fun earlyWakeNotificationId(groupId: Int) = EARLY_WAKE_NOTIFICATION_BASE + groupId

    fun schedulePrayer(context: Context, settings: PrayerSettings, prayer: Prayer, from: ZonedDateTime = ZonedDateTime.now()) {
        val requestCode = PRAYER_REQUEST_BASE + prayer.ordinal
        val next = settings.nextPrayerTrigger(prayer, from)
        if (next == null) {
            cancel(context, ACTION_FIRE, requestCode)
            return
        }
        val name = context.prayerName(prayer)
        val before = settings.alarmFor(prayer).minutesBefore
        val label = if (before > 0) context.getString(R.string.prayer_in_minutes, name, before)
        else context.getString(R.string.prayer_now, name)
        setAlarmClock(context, requestCode, next, fireIntent(context, KIND_PRAYER, prayer.ordinal, label, next))
    }

    fun scheduleSnooze(context: Context, alarm: RingingAlarm, minutes: Int) {
        val at = ZonedDateTime.now().plusMinutes(minutes.toLong())
        setAlarmClock(context, SNOOZE_REQUEST, at, fireIntent(context, KIND_SNOOZE, -1, alarm.label, at).apply {
            putExtra(EXTRA_IS_PRAYER, alarm.isPrayer)
            putExtra(EXTRA_SOUND, alarm.soundUri)
        })
    }

    private fun receiverIntent(context: Context, action: String) =
        Intent(context, AlarmReceiver::class.java).setAction(action)

    private fun fireIntent(context: Context, kind: String, id: Int, label: String, at: ZonedDateTime) =
        receiverIntent(context, ACTION_FIRE)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_TRIGGER, at.toInstant().toEpochMilli())

    private fun cancel(context: Context, action: String, requestCode: Int) {
        val pi = PendingIntent.getBroadcast(
            context, requestCode, receiverIntent(context, action),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    private fun setAlarmClock(context: Context, requestCode: Int, at: ZonedDateTime, intent: Intent) {
        val triggerAt = at.toInstant().toEpochMilli()
        val operation = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(AlarmManager::class.java)
        if (canScheduleExact(context)) {
            // setAlarmClock is exact, survives Doze, and shows the alarm icon in the status bar.
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    private fun setReminder(context: Context, requestCode: Int, at: ZonedDateTime, intent: Intent) {
        val triggerAt = at.toInstant().toEpochMilli()
        val operation = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(AlarmManager::class.java)
        if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
    }
}
