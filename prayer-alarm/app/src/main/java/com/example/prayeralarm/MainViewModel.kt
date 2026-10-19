package com.example.prayeralarm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.prayeralarm.alarm.AlarmScheduler
import com.example.prayeralarm.data.Alarm
import com.example.prayeralarm.data.PrayerSettings
import com.example.prayeralarm.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZonedDateTime

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val _settings = MutableStateFlow(store.settings)
    private val _alarms = MutableStateFlow(store.alarms())
    val settings: StateFlow<PrayerSettings> = _settings
    val alarms: StateFlow<List<Alarm>> = _alarms

    /** Reloads from storage, e.g. after a one-time alarm switched itself off while the app was closed. */
    fun refresh() {
        _settings.value = store.settings
        _alarms.value = store.alarms()
    }

    fun updateSettings(transform: (PrayerSettings) -> PrayerSettings) {
        val updated = transform(_settings.value)
        store.settings = updated
        _settings.value = updated
        AlarmScheduler.reschedulePrayers(getApplication(), updated)
    }

    /** Adds an alarm and returns when it will ring. */
    fun addAlarm(hour: Int, minute: Int): ZonedDateTime {
        val alarm = Alarm(store.nextId(), hour, minute)
        store.upsert(alarm)
        _alarms.value = store.alarms()
        return AlarmScheduler.scheduleCustom(getApplication(), alarm)
    }

    /** Saves an edited alarm and returns when it will ring, or null if it is off. */
    fun updateAlarm(alarm: Alarm): ZonedDateTime? {
        store.upsert(alarm)
        _alarms.value = store.alarms()
        return if (alarm.enabled) AlarmScheduler.scheduleCustom(getApplication(), alarm)
        else null.also { AlarmScheduler.cancel(getApplication(), alarm.id) }
    }

    fun deleteAlarm(alarm: Alarm) {
        AlarmScheduler.cancel(getApplication(), alarm.id)
        store.delete(alarm.id)
        _alarms.value = store.alarms()
    }
}
