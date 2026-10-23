package com.example.prayeralarm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.prayeralarm.alarm.AlarmScheduler
import com.example.prayeralarm.data.AlarmGroup
import com.example.prayeralarm.data.AppSettings
import com.example.prayeralarm.data.Occurrence
import com.example.prayeralarm.data.PrayerSettings
import com.example.prayeralarm.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val _settings = MutableStateFlow(store.settings)
    private val _appSettings = MutableStateFlow(store.appSettings)
    private val _groups = MutableStateFlow(store.groups())
    val settings: StateFlow<PrayerSettings> = _settings
    val appSettings: StateFlow<AppSettings> = _appSettings
    val groups: StateFlow<List<AlarmGroup>> = _groups

    /** Application context in the app's chosen language, for alarm labels and notifications. */
    private val context get() = LocaleHelper.wrap(getApplication<Application>())

    /** Reloads from storage, e.g. after a one-time alarm switched itself off while the app was closed. */
    fun refresh() {
        _settings.value = store.settings
        _appSettings.value = store.appSettings
        _groups.value = store.groups()
    }

    fun updateSettings(transform: (PrayerSettings) -> PrayerSettings) {
        store.settings = transform(_settings.value)
        _settings.value = store.settings
        // Prayer-based alarm groups move with the prayer times too.
        AlarmScheduler.rescheduleAll(context)
    }

    fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        val old = _appSettings.value
        store.appSettings = transform(old)
        _appSettings.value = store.appSettings
        // Recreates the screens in the new language.
        if (old.language != store.appSettings.language) LocaleHelper.apply(store.appSettings.language)
        // Work days and the reminder time change when groups ring.
        AlarmScheduler.rescheduleAll(context)
    }

    fun newGroupId() = store.nextId()

    /** Saves a group and returns its next ring, or null if it is off or can't ring (e.g. no location). */
    fun saveGroup(group: AlarmGroup): Occurrence? {
        store.upsert(group)
        _groups.value = store.groups()
        return AlarmScheduler.scheduleGroup(context, group, store = store)
    }

    fun setEnabled(group: AlarmGroup, enabled: Boolean): Occurrence? =
        saveGroup(group.copy(enabled = enabled, skipUntil = 0L))

    fun skipNext(group: AlarmGroup) {
        AlarmScheduler.skipNext(context, group)
        _groups.value = store.groups()
    }

    fun deleteGroup(group: AlarmGroup) {
        AlarmScheduler.cancelGroup(context, group.id)
        store.delete(group.id)
        _groups.value = store.groups()
    }
}
