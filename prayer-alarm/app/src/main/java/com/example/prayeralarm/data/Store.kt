package com.example.prayeralarm.data

import android.content.Context
import com.example.prayeralarm.prayer.AsrMethod
import com.example.prayeralarm.prayer.CalculationMethod
import com.example.prayeralarm.prayer.Prayer
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

/** Saves settings and alarm groups in SharedPreferences. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("prayer_alarm", Context.MODE_PRIVATE)

    var settings: PrayerSettings
        get() {
            val defaults = PrayerSettings()
            return PrayerSettings(
                latitude = prefs.getString("lat", null)?.toDoubleOrNull(),
                longitude = prefs.getString("lng", null)?.toDoubleOrNull(),
                method = enumOr(prefs.getString("method", null), defaults.method),
                asrMethod = enumOr(prefs.getString("asr", null), defaults.asrMethod),
                prayerAlarms = Prayer.entries.associateWith { p ->
                    PrayerAlarm(
                        enabled = prefs.getBoolean("p_${p.name}_on", defaults.alarmFor(p).enabled),
                        minutesBefore = prefs.getInt("p_${p.name}_before", 0),
                    )
                },
            )
        }
        set(value) {
            prefs.edit().apply {
                putString("lat", value.latitude?.toString())
                putString("lng", value.longitude?.toString())
                putString("method", value.method.name)
                putString("asr", value.asrMethod.name)
                value.prayerAlarms.forEach { (p, a) ->
                    putBoolean("p_${p.name}_on", a.enabled)
                    putInt("p_${p.name}_before", a.minutesBefore)
                }
            }.apply()
        }

    var appSettings: AppSettings
        get() {
            val d = AppSettings()
            return AppSettings(
                alarmSoundUri = prefs.getString("s_alarm_uri", null),
                alarmSoundName = prefs.getString("s_alarm_name", null),
                prayerSoundUri = prefs.getString("s_prayer_uri", null),
                prayerSoundName = prefs.getString("s_prayer_name", null),
                vibrate = prefs.getBoolean("s_vibrate", d.vibrate),
                ascendingSeconds = prefs.getInt("s_ascending", d.ascendingSeconds),
                snoozeMinutes = prefs.getInt("s_snooze", d.snoozeMinutes),
                ringMinutes = prefs.getInt("s_ring", d.ringMinutes),
                reminderMinutes = prefs.getInt("s_reminder", d.reminderMinutes),
                wakeCheckMinutes = prefs.getInt("s_wake_check", d.wakeCheckMinutes),
                prayerCountdownMinutes = prefs.getInt("s_prayer_countdown", d.prayerCountdownMinutes),
                workDays = prefs.getString("s_work_days", null)?.let(::parseDays) ?: d.workDays,
                backgroundUri = prefs.getString("s_background", null),
                backgroundFit = enumOr(prefs.getString("s_background_fit", null), d.backgroundFit),
                theme = enumOr(prefs.getString("s_theme", null), d.theme),
                language = enumOr(prefs.getString("s_language", null), d.language),
            )
        }
        set(v) {
            prefs.edit()
                .putString("s_alarm_uri", v.alarmSoundUri)
                .putString("s_alarm_name", v.alarmSoundName)
                .putString("s_prayer_uri", v.prayerSoundUri)
                .putString("s_prayer_name", v.prayerSoundName)
                .putBoolean("s_vibrate", v.vibrate)
                .putInt("s_ascending", v.ascendingSeconds)
                .putInt("s_snooze", v.snoozeMinutes)
                .putInt("s_ring", v.ringMinutes)
                .putInt("s_reminder", v.reminderMinutes)
                .putInt("s_wake_check", v.wakeCheckMinutes)
                .putInt("s_prayer_countdown", v.prayerCountdownMinutes)
                .putString("s_work_days", v.workDays.joinToString(",") { it.value.toString() })
                .putString("s_background", v.backgroundUri)
                .putString("s_background_fit", v.backgroundFit.name)
                .putString("s_theme", v.theme.name)
                .putString("s_language", v.language.name)
                .apply()
        }

    fun scheduleContext() = ScheduleContext(appSettings.workDays, settings)

    fun groups(): List<AlarmGroup> {
        migrateOldAlarms()
        val json = JSONArray(prefs.getString("groups", "[]"))
        return (0 until json.length()).map { readGroup(json.getJSONObject(it)) }.sortedWith(
            compareBy<AlarmGroup> { it.anchor is Anchor.AfterPrayer }
                .thenBy { (it.anchor as? Anchor.Fixed)?.let { a -> a.hour * 60 + a.minute } }
                .thenBy { (it.anchor as? Anchor.AfterPrayer)?.let { a -> a.prayer.ordinal * 10_000 + a.offsetMinutes } }
        )
    }

    fun group(id: Int) = groups().find { it.id == id }

    fun upsert(group: AlarmGroup) = saveGroups(groups().filter { it.id != group.id } + group)

    fun delete(id: Int) = saveGroups(groups().filter { it.id != id })

    fun nextId(): Int = (groups().maxOfOrNull { it.id } ?: 0) + 1

    private fun saveGroups(list: List<AlarmGroup>) {
        val json = JSONArray()
        list.forEach { json.put(writeGroup(it)) }
        prefs.edit().putString("groups", json.toString()).apply()
    }

    private fun writeGroup(g: AlarmGroup): JSONObject = JSONObject()
        .put("id", g.id)
        .put("label", g.label)
        .apply {
            when (val a = g.anchor) {
                is Anchor.Fixed -> put("h", a.hour).put("m", a.minute)
                is Anchor.AfterPrayer -> put("prayer", a.prayer.name).put("offset", a.offsetMinutes)
            }
        }
        .put("count", g.count)
        .put("interval", g.intervalMinutes)
        .put("repeat", g.repeat.name)
        .put("days", g.customDays.joinToString(",") { it.value.toString() })
        .put("on", g.enabled)
        .put("sound", g.soundUri ?: JSONObject.NULL)
        .put("soundName", g.soundName ?: JSONObject.NULL)
        .put("skip", g.skipUntil)

    private fun readGroup(o: JSONObject): AlarmGroup {
        val prayer = o.optString("prayer").takeIf { it.isNotEmpty() }
        return AlarmGroup(
            id = o.getInt("id"),
            label = o.optString("label"),
            anchor = if (prayer != null) Anchor.AfterPrayer(enumOr(prayer, Prayer.FAJR), o.optInt("offset"))
            else Anchor.Fixed(o.optInt("h", 7), o.optInt("m")),
            count = o.optInt("count", 1).coerceAtLeast(1),
            intervalMinutes = o.optInt("interval", 5).coerceAtLeast(1),
            repeat = enumOr(o.optString("repeat"), Repeat.ONCE),
            customDays = parseDays(o.optString("days")),
            enabled = o.optBoolean("on", true),
            soundUri = o.optString("sound").takeIf { !o.isNull("sound") && it.isNotEmpty() },
            soundName = o.optString("soundName").takeIf { !o.isNull("soundName") && it.isNotEmpty() },
            skipUntil = o.optLong("skip"),
        )
    }

    /** Version 1 stored single alarms under "alarms"; turn them into one-alarm groups. */
    private fun migrateOldAlarms() {
        val old = prefs.getString("alarms", null) ?: return
        val json = JSONArray(old)
        val groups = (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            val daysJson = o.getJSONArray("days")
            val days = (0 until daysJson.length()).map { DayOfWeek.of(daysJson.getInt(it)) }.toSet()
            AlarmGroup(
                id = o.getInt("id"),
                label = o.optString("label"),
                anchor = Anchor.Fixed(o.getInt("h"), o.getInt("m")),
                repeat = when (days.size) {
                    0 -> Repeat.ONCE
                    7 -> Repeat.DAILY
                    else -> Repeat.CUSTOM
                },
                customDays = days,
                enabled = o.getBoolean("on"),
            )
        }
        val out = JSONArray()
        groups.forEach { out.put(writeGroup(it)) }
        prefs.edit().putString("groups", out.toString()).remove("alarms").apply()
    }

    private fun parseDays(s: String): Set<DayOfWeek> =
        s.split(",").mapNotNull { it.trim().toIntOrNull()?.takeIf { v -> v in 1..7 }?.let(DayOfWeek::of) }.toSet()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
