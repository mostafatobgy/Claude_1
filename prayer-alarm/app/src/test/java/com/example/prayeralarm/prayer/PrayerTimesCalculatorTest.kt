package com.example.prayeralarm.prayer

import com.example.prayeralarm.data.PrayerAlarm
import com.example.prayeralarm.data.PrayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

class PrayerTimesCalculatorTest {
    private fun assertNear(expected: String, actual: ZonedDateTime?, toleranceMinutes: Int = 2) {
        val e = LocalTime.parse(expected).toSecondOfDay() / 60
        val a = actual!!.toLocalTime().toSecondOfDay() / 60
        assertTrue("expected $expected but was ${actual.toLocalTime()}", abs(e - a) <= toleranceMinutes)
    }

    @Test
    fun cairo() {
        // Reference: Egyptian General Authority of Survey timetable for Cairo, 1 Jan 2025.
        val t = PrayerTimesCalculator.calculate(
            LocalDate.of(2025, 1, 1), 30.0444, 31.2357, ZoneId.of("Africa/Cairo"),
            CalculationMethod.EGYPT, AsrMethod.STANDARD,
        )
        assertNear("05:18", t[Prayer.FAJR])
        assertNear("06:49", t[Prayer.SUNRISE])
        assertNear("11:59", t[Prayer.DHUHR])
        assertNear("14:47", t[Prayer.ASR])
        // Astronomical sunset; printed Egyptian timetables add ~2-3 minutes of caution (17:09).
        assertNear("17:07", t[Prayer.MAGHRIB])
        assertNear("18:31", t[Prayer.ISHA])
    }

    @Test
    fun makkahIshaIsNinetyMinutesAfterMaghrib() {
        val t = PrayerTimesCalculator.calculate(
            LocalDate.of(2026, 10, 19), 21.4225, 39.8262, ZoneId.of("Asia/Riyadh"),
            CalculationMethod.MAKKAH, AsrMethod.STANDARD,
        )
        assertEquals(t[Prayer.MAGHRIB]!!.plusMinutes(90), t[Prayer.ISHA])
    }

    @Test
    fun highLatitudeStillProducesFajrAndIsha() {
        // Oslo in June: the sun never goes 18 degrees below the horizon.
        val t = PrayerTimesCalculator.calculate(
            LocalDate.of(2026, 6, 21), 59.91, 10.75, ZoneId.of("Europe/Oslo"),
            CalculationMethod.MWL, AsrMethod.STANDARD,
        )
        assertEquals(6, t.size)
        assertTrue(t[Prayer.FAJR]!!.isBefore(t[Prayer.SUNRISE]))
        assertTrue(t[Prayer.ISHA]!!.isAfter(t[Prayer.MAGHRIB]))
    }

    @Test
    fun nextPrayerTriggerRollsToTomorrowAndAppliesOffset() {
        val zone = ZoneId.of("Africa/Cairo")
        val settings = PrayerSettings(
            latitude = 30.0444, longitude = 31.2357,
            prayerAlarms = mapOf(Prayer.FAJR to PrayerAlarm(true, minutesBefore = 10)),
        )
        val from = ZonedDateTime.of(2025, 1, 1, 12, 0, 0, 0, zone)
        val next = settings.nextPrayerTrigger(Prayer.FAJR, from)!!
        assertEquals(LocalDate.of(2025, 1, 2), next.toLocalDate())
        val fajr = settings.timesFor(LocalDate.of(2025, 1, 2), zone)[Prayer.FAJR]!!
        assertEquals(fajr.minusMinutes(10), next)
        assertEquals(null, settings.nextPrayerTrigger(Prayer.DHUHR, from))
    }

    @Test
    fun nextPrayerTimeIgnoresAlarmSwitchAndRollsOver() {
        val zone = ZoneId.of("Africa/Cairo")
        // Alarms all off: the countdown notification still needs the prayer times.
        val settings = PrayerSettings(
            latitude = 30.0444, longitude = 31.2357,
            prayerAlarms = Prayer.entries.associateWith { PrayerAlarm(false) },
        )
        val today = LocalDate.of(2025, 1, 1)
        val asr = settings.timesFor(today, zone)[Prayer.ASR]!!
        assertEquals(asr, settings.nextPrayerTime(Prayer.ASR, asr.minusMinutes(30)))
        // Exactly at (or after) the prayer time, the next one is tomorrow's.
        assertEquals(today.plusDays(1), settings.nextPrayerTime(Prayer.ASR, asr)!!.toLocalDate())
        assertEquals(null, PrayerSettings().nextPrayerTime(Prayer.ASR, asr))
    }
}
