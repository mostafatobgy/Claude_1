package com.example.prayeralarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.prayeralarm.LocaleHelper
import com.example.prayeralarm.data.Repeat
import com.example.prayeralarm.data.Store
import com.example.prayeralarm.prayer.Prayer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Handles alarms firing, the heads-up reminder before them, and "skip" from that reminder. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(rawContext: Context, intent: Intent) {
        val context = LocaleHelper.wrap(rawContext)
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty()
        val trigger = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER, System.currentTimeMillis())
        val sessionEnd = intent.getLongExtra(AlarmScheduler.EXTRA_SESSION_END, 0L)
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> fire(context, intent, id, label, trigger, sessionEnd)
            AlarmScheduler.ACTION_REMIND -> Notifications.showReminder(context, id, label, trigger, sessionEnd)
            AlarmScheduler.ACTION_SKIP -> AlarmScheduler.skipSession(context, id, sessionEnd)
            AlarmScheduler.ACTION_KEEP -> AlarmScheduler.cancelNotifications(context, id)
            AlarmScheduler.ACTION_WATCH -> WakeWatchService.start(context, trigger)
            AlarmScheduler.ACTION_PRAYER_COUNTDOWN, AlarmScheduler.ACTION_PRAYER_ELAPSED -> {
                val prayer = Prayer.entries.getOrNull(id) ?: return
                val store = Store(context)
                val minutes = store.appSettings.prayerCountdownMinutes
                if (minutes <= 0) return
                val elapsed = intent.action == AlarmScheduler.ACTION_PRAYER_ELAPSED
                Notifications.showPrayerStatus(context, prayer, trigger, minutes, elapsed)
                // After the prayer time, arm the next day's countdown for this prayer.
                if (elapsed) {
                    val from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(trigger).plusSeconds(1), ZoneId.systemDefault())
                    AlarmScheduler.schedulePrayerCountdown(context, store.settings, prayer, minutes, from)
                }
            }
        }
    }

    private fun fire(context: Context, intent: Intent, id: Int, label: String, trigger: Long, sessionEnd: Long) {
        val kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND) ?: return
        val store = Store(context)
        // Look for the next occurrence strictly after this one.
        val from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(trigger).plusSeconds(1), ZoneId.systemDefault())
        when (kind) {
            AlarmScheduler.KIND_GROUP -> {
                val group = store.group(id) ?: return
                val index = intent.getIntExtra(AlarmScheduler.EXTRA_INDEX, 0)
                val remaining = (group.count - 1 - index).coerceAtLeast(0)
                AlarmService.start(
                    context,
                    RingingAlarm(label, isPrayer = false, intent.getStringExtra(AlarmScheduler.EXTRA_SOUND), id, sessionEnd, remaining),
                )
                AlarmScheduler.cancelNotifications(context, id)
                if (group.repeat == Repeat.ONCE && remaining == 0) {
                    store.upsert(group.copy(enabled = false))
                    AlarmScheduler.cancelGroup(context, id)
                } else {
                    AlarmScheduler.scheduleGroup(context, group, from, store)
                }
            }
            AlarmScheduler.KIND_PRAYER -> {
                AlarmService.start(context, RingingAlarm(label, isPrayer = true))
                Prayer.entries.getOrNull(id)?.let { AlarmScheduler.schedulePrayer(context, store.settings, it, from) }
            }
            AlarmScheduler.KIND_SNOOZE -> AlarmService.start(
                context,
                RingingAlarm(
                    label,
                    isPrayer = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_PRAYER, false),
                    soundUri = intent.getStringExtra(AlarmScheduler.EXTRA_SOUND),
                ),
            )
        }
    }
}

/** Alarms are cleared on reboot, and fixed clock times shift when the time or timezone changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(rawContext: Context, intent: Intent) {
        val context = LocaleHelper.wrap(rawContext)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            -> AlarmScheduler.rescheduleAll(context)
        }
    }
}
