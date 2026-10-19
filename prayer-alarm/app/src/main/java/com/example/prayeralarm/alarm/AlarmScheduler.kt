package com.example.prayeralarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.prayeralarm.MainActivity
import com.example.prayeralarm.R
import com.example.prayeralarm.data.Alarm
import com.example.prayeralarm.data.PrayerSettings
import com.example.prayeralarm.data.Store
import com.example.prayeralarm.prayer.Prayer
import com.example.prayeralarm.ui.prayerName
import java.time.ZonedDateTime

object AlarmScheduler {
    const val EXTRA_KIND = "kind"
    const val EXTRA_ID = "id"
    const val EXTRA_LABEL = "label"
    const val EXTRA_TRIGGER = "trigger"
    const val KIND_CUSTOM = "custom"
    const val KIND_PRAYER = "prayer"
    const val KIND_SNOOZE = "snooze"

    // Request codes: custom alarms use their id; prayers and snooze sit far above that range.
    private const val PRAYER_REQUEST_BASE = 100_000
    private const val SNOOZE_REQUEST = 200_000

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Re-arms everything. Called on app start, boot, time/timezone change and settings changes. */
    fun rescheduleAll(context: Context) {
        val store = Store(context)
        store.alarms().forEach { if (it.enabled) scheduleCustom(context, it) else cancel(context, it.id) }
        reschedulePrayers(context, store.settings)
    }

    fun reschedulePrayers(context: Context, settings: PrayerSettings = Store(context).settings) {
        Prayer.entries.forEach { schedulePrayer(context, settings, it) }
    }

    fun scheduleCustom(context: Context, alarm: Alarm, from: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val next = alarm.nextTrigger(from)
        set(context, alarm.id, next, KIND_CUSTOM, alarm.id, alarm.label.ifBlank { context.getString(R.string.alarm) })
        return next
    }

    fun schedulePrayer(context: Context, settings: PrayerSettings, prayer: Prayer, from: ZonedDateTime = ZonedDateTime.now()) {
        val requestCode = PRAYER_REQUEST_BASE + prayer.ordinal
        val next = settings.nextPrayerTrigger(prayer, from)
        if (next == null) {
            cancel(context, requestCode)
            return
        }
        val name = context.prayerName(prayer)
        val before = settings.alarmFor(prayer).minutesBefore
        val label = if (before > 0) context.getString(R.string.prayer_in_minutes, name, before)
        else context.getString(R.string.prayer_now, name)
        set(context, requestCode, next, KIND_PRAYER, prayer.ordinal, label)
    }

    fun scheduleSnooze(context: Context, label: String, isPrayer: Boolean, minutes: Long) {
        val at = ZonedDateTime.now().plusMinutes(minutes)
        set(context, SNOOZE_REQUEST, at, KIND_SNOOZE, if (isPrayer) 1 else 0, label)
    }

    fun cancel(context: Context, requestCode: Int) {
        val pi = PendingIntent.getBroadcast(
            context, requestCode, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    private fun set(context: Context, requestCode: Int, at: ZonedDateTime, kind: String, id: Int, label: String) {
        val triggerAt = at.toInstant().toEpochMilli()
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_TRIGGER, triggerAt)
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
}
