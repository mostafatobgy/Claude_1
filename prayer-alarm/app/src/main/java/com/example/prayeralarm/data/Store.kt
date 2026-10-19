package com.example.prayeralarm.data

import android.content.Context
import com.example.prayeralarm.prayer.AsrMethod
import com.example.prayeralarm.prayer.CalculationMethod
import com.example.prayeralarm.prayer.Prayer
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

/** Saves settings and alarms in SharedPreferences. */
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

    fun alarms(): List<Alarm> {
        val json = JSONArray(prefs.getString("alarms", "[]"))
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            val days = o.getJSONArray("days")
            Alarm(
                id = o.getInt("id"),
                hour = o.getInt("h"),
                minute = o.getInt("m"),
                label = o.optString("label"),
                days = (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(),
                enabled = o.getBoolean("on"),
            )
        }.sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    fun alarm(id: Int) = alarms().find { it.id == id }

    fun upsert(alarm: Alarm) = saveAlarms(alarms().filter { it.id != alarm.id } + alarm)

    fun delete(id: Int) = saveAlarms(alarms().filter { it.id != id })

    fun nextId(): Int = (alarms().maxOfOrNull { it.id } ?: 0) + 1

    private fun saveAlarms(list: List<Alarm>) {
        val json = JSONArray()
        list.forEach { a ->
            json.put(
                JSONObject()
                    .put("id", a.id)
                    .put("h", a.hour)
                    .put("m", a.minute)
                    .put("label", a.label)
                    .put("days", JSONArray(a.days.map { it.value }.sorted()))
                    .put("on", a.enabled)
            )
        }
        prefs.edit().putString("alarms", json.toString()).apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
