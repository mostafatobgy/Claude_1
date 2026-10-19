package com.example.prayeralarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.prayeralarm.data.Store
import com.example.prayeralarm.prayer.Prayer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Fired by AlarmManager: starts ringing, then arms the next occurrence of the same alarm. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND) ?: return
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty()
        val trigger = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER, System.currentTimeMillis())
        val isPrayer = kind == AlarmScheduler.KIND_PRAYER || (kind == AlarmScheduler.KIND_SNOOZE && id == 1)

        AlarmService.start(context, label, isPrayer)

        // Look for the next occurrence after this minute so the same alarm isn't re-armed for now.
        val from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(trigger).plusSeconds(60), ZoneId.systemDefault())
        val store = Store(context)
        when (kind) {
            AlarmScheduler.KIND_CUSTOM -> {
                val alarm = store.alarm(id) ?: return
                if (alarm.days.isEmpty()) store.upsert(alarm.copy(enabled = false))
                else AlarmScheduler.scheduleCustom(context, alarm, from)
            }
            AlarmScheduler.KIND_PRAYER -> Prayer.entries.getOrNull(id)?.let {
                AlarmScheduler.schedulePrayer(context, store.settings, it, from)
            }
        }
    }
}

/** Alarms are cleared on reboot, and fixed clock times shift when the time or timezone changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
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
