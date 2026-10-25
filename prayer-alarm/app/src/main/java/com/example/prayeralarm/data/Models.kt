package com.example.prayeralarm.data

import com.example.prayeralarm.prayer.AsrMethod
import com.example.prayeralarm.prayer.CalculationMethod
import com.example.prayeralarm.prayer.Prayer
import com.example.prayeralarm.prayer.PrayerTimesCalculator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** When an alarm group starts: a fixed clock time, or minutes before/after a prayer (moves with it every day). */
sealed interface Anchor {
    data class Fixed(val hour: Int, val minute: Int) : Anchor
    data class AfterPrayer(val prayer: Prayer, val offsetMinutes: Int) : Anchor
}

enum class Repeat { ONCE, DAILY, WORK_DAYS, OFF_DAYS, CUSTOM }

/**
 * One or more alarms that ring [intervalMinutes] apart, starting at [anchor].
 * A single alarm is just a group with [count] = 1.
 */
data class AlarmGroup(
    val id: Int,
    val label: String = "",
    val anchor: Anchor = Anchor.Fixed(7, 0),
    val count: Int = 1,
    val intervalMinutes: Int = 5,
    val repeat: Repeat = Repeat.ONCE,
    val customDays: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
    val soundUri: String? = null,
    val soundName: String? = null,
    /** Epoch millis; alarms at or before this are skipped ("skip next" or "I'm awake, stop the rest"). */
    val skipUntil: Long = 0L,
) {
    fun runsOn(date: LocalDate, workDays: Set<DayOfWeek>): Boolean = when (repeat) {
        Repeat.ONCE, Repeat.DAILY -> true
        Repeat.WORK_DAYS -> date.dayOfWeek in workDays
        Repeat.OFF_DAYS -> date.dayOfWeek !in workDays
        Repeat.CUSTOM -> date.dayOfWeek in customDays
    }

    /** All ring times of the group for the session belonging to [date] (empty if it doesn't run that day). */
    fun session(date: LocalDate, zone: ZoneId, ctx: ScheduleContext): List<ZonedDateTime> {
        if (!runsOn(date, ctx.workDays)) return emptyList()
        val start = when (anchor) {
            is Anchor.Fixed -> date.atTime(anchor.hour, anchor.minute).atZone(zone)
            is Anchor.AfterPrayer -> ctx.prayers.timesFor(date, zone)[anchor.prayer]
                ?.plusMinutes(anchor.offsetMinutes.toLong())
        } ?: return emptyList()
        return (0 until count).map { start.plusMinutes(it.toLong() * intervalMinutes) }
    }

    fun nextOccurrence(from: ZonedDateTime, ctx: ScheduleContext): Occurrence? {
        if (!enabled) return null
        // Start a day early: a session may cross midnight.
        for (day in -1L..8L) {
            val times = session(from.toLocalDate().plusDays(day), from.zone, ctx)
            times.forEachIndexed { index, time ->
                if (time.isAfter(from) && time.toInstant().toEpochMilli() > skipUntil) {
                    return Occurrence(time, index, times.last())
                }
            }
        }
        return null
    }
}

/** One ring of a group: its time, position in the session, and when the session's last alarm rings. */
data class Occurrence(val time: ZonedDateTime, val index: Int, val sessionEnd: ZonedDateTime)

data class ScheduleContext(val workDays: Set<DayOfWeek>, val prayers: PrayerSettings)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What the phone's side buttons (volume, power) do while an alarm rings. */
enum class SideButtonAction { NONE, SNOOZE, DISMISS }

/** App language, independent of the phone's language. */
enum class AppLanguage(val tag: String?) { SYSTEM(null), ARABIC("ar"), ENGLISH("en") }

/** How the alarm screen shows the background picture: fill the screen (cropping), or show all of it. */
enum class BackgroundFit { FILL, FIT }

data class AppSettings(
    val alarmSoundUri: String? = null,
    val alarmSoundName: String? = null,
    val prayerSoundUri: String? = null,
    val prayerSoundName: String? = null,
    val vibrate: Boolean = true,
    /** 0 = full volume immediately. */
    val ascendingSeconds: Int = 0,
    val snoozeMinutes: Int = 10,
    val ringMinutes: Int = 5,
    /** Notify this many minutes before a group's first alarm; 0 = off. */
    val reminderMinutes: Int = 5,
    /** If the phone is unlocked within this many minutes before an alarm, ask whether to turn it off; 0 = off. */
    val wakeCheckMinutes: Int = 60,
    /** Countdown notification this many minutes before each prayer, then time since the adhan for as long; 0 = off. */
    val prayerCountdownMinutes: Int = 30,
    // Egypt and most of the region: Sunday to Thursday.
    val workDays: Set<DayOfWeek> = setOf(
        DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    ),
    val backgroundUri: String? = null,
    val backgroundFit: BackgroundFit = BackgroundFit.FILL,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val sideButtons: SideButtonAction = SideButtonAction.SNOOZE,
)

data class PrayerAlarm(val enabled: Boolean, val minutesBefore: Int = 0)

data class PrayerSettings(
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** City name for the location, in Arabic (looked up from the coordinates); null if unknown. */
    val cityName: String? = null,
    val method: CalculationMethod = CalculationMethod.EGYPT,
    val asrMethod: AsrMethod = AsrMethod.STANDARD,
    val prayerAlarms: Map<Prayer, PrayerAlarm> = Prayer.entries.associateWith { PrayerAlarm(it != Prayer.SUNRISE) },
) {
    val hasLocation get() = latitude != null && longitude != null

    fun timesFor(date: LocalDate, zone: ZoneId): Map<Prayer, ZonedDateTime> =
        if (latitude == null || longitude == null) emptyMap()
        else PrayerTimesCalculator.calculate(date, latitude, longitude, zone, method, asrMethod)

    fun alarmFor(prayer: Prayer) = prayerAlarms[prayer] ?: PrayerAlarm(false)

    /** The first time [prayer] falls strictly after [from], or null without a location. */
    fun nextPrayerTime(prayer: Prayer, from: ZonedDateTime): ZonedDateTime? {
        if (!hasLocation) return null
        for (i in 0L..7L) {
            val time = timesFor(from.toLocalDate().plusDays(i), from.zone)[prayer] ?: continue
            if (time.isAfter(from)) return time
        }
        return null
    }

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
