package com.example.prayeralarm.data

import com.example.prayeralarm.prayer.AsrMethod
import com.example.prayeralarm.prayer.CalculationMethod
import com.example.prayeralarm.prayer.Prayer
import com.example.prayeralarm.prayer.PrayerTimesCalculator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** A regular alarm. An empty [days] set means it rings once and then turns itself off. */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val days: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
) {
    fun nextTrigger(from: ZonedDateTime): ZonedDateTime {
        for (i in 0L..7L) {
            val date = from.toLocalDate().plusDays(i)
            if (days.isNotEmpty() && date.dayOfWeek !in days) continue
            val time = date.atTime(hour, minute).atZone(from.zone)
            if (time.isAfter(from)) return time
        }
        error("No trigger found for alarm $id")
    }
}

data class PrayerAlarm(val enabled: Boolean, val minutesBefore: Int = 0)

data class PrayerSettings(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val method: CalculationMethod = CalculationMethod.EGYPT,
    val asrMethod: AsrMethod = AsrMethod.STANDARD,
    val prayerAlarms: Map<Prayer, PrayerAlarm> = Prayer.entries.associateWith { PrayerAlarm(it != Prayer.SUNRISE) },
) {
    val hasLocation get() = latitude != null && longitude != null

    fun timesFor(date: LocalDate, zone: ZoneId): Map<Prayer, ZonedDateTime> =
        if (latitude == null || longitude == null) emptyMap()
        else PrayerTimesCalculator.calculate(date, latitude, longitude, zone, method, asrMethod)

    fun alarmFor(prayer: Prayer) = prayerAlarms[prayer] ?: PrayerAlarm(false)

    /** Next time the alarm for [prayer] should ring after [from], or null if it is off or there is no location. */
    fun nextPrayerTrigger(prayer: Prayer, from: ZonedDateTime): ZonedDateTime? {
        val alarm = alarmFor(prayer)
        if (!alarm.enabled || !hasLocation) return null
        for (i in 0L..7L) {
            val time = timesFor(from.toLocalDate().plusDays(i), from.zone)[prayer] ?: continue
            val trigger = time.minusMinutes(alarm.minutesBefore.toLong())
            if (trigger.isAfter(from)) return trigger
        }
        return null
    }
}
