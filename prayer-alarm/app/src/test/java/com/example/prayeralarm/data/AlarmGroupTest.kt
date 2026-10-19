package com.example.prayeralarm.data

import com.example.prayeralarm.prayer.Prayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmGroupTest {
    private val cairo = ZoneId.of("Africa/Cairo")
    private val ctx = ScheduleContext(
        workDays = AppSettings().workDays,
        prayers = PrayerSettings(latitude = 30.0444, longitude = 31.2357),
    )

    // 2026-10-19 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, cairo)

    @Test
    fun groupRingsEachAlarmInTurn() {
        val g = AlarmGroup(1, anchor = Anchor.Fixed(6, 0), count = 4, intervalMinutes = 5, repeat = Repeat.DAILY)
        val first = g.nextOccurrence(at(19, 5, 0), ctx)!!
        assertEquals(Occurrence(at(19, 6, 0), 0, at(19, 6, 15)), first)
        val third = g.nextOccurrence(at(19, 6, 5), ctx)!!
        assertEquals(at(19, 6, 10), third.time)
        assertEquals(2, third.index)
        // After the last one, the next is tomorrow's first.
        assertEquals(Occurrence(at(20, 6, 0), 0, at(20, 6, 15)), g.nextOccurrence(at(19, 6, 15), ctx))
    }

    @Test
    fun skipUntilSkipsTheRestOfTheSession() {
        val g = AlarmGroup(
            1, anchor = Anchor.Fixed(6, 0), count = 4, repeat = Repeat.DAILY,
            skipUntil = at(19, 6, 15).toInstant().toEpochMilli(),
        )
        assertEquals(at(20, 6, 0), g.nextOccurrence(at(19, 6, 0), ctx)!!.time)
    }

    @Test
    fun workDaysAndOffDays() {
        val work = AlarmGroup(1, anchor = Anchor.Fixed(7, 0), repeat = Repeat.WORK_DAYS)
        val off = work.copy(repeat = Repeat.OFF_DAYS)
        // Thursday 22nd after 7:00: next work day is Sunday 25th; next off day is Friday 23rd.
        assertEquals(at(25, 7, 0), work.nextOccurrence(at(22, 8, 0), ctx)!!.time)
        assertEquals(at(23, 7, 0), off.nextOccurrence(at(22, 8, 0), ctx)!!.time)
        val custom = work.copy(repeat = Repeat.CUSTOM, customDays = setOf(DayOfWeek.WEDNESDAY))
        assertEquals(at(21, 7, 0), custom.nextOccurrence(at(19, 8, 0), ctx)!!.time)
    }

    @Test
    fun followsFajrEveryDay() {
        val g = AlarmGroup(1, anchor = Anchor.AfterPrayer(Prayer.FAJR, 5), repeat = Repeat.DAILY)
        for (day in listOf(19, 26)) {
            val date = LocalDate.of(2026, 10, day)
            val fajr = ctx.prayers.timesFor(date, cairo)[Prayer.FAJR]!!
            assertEquals(fajr.plusMinutes(5), g.nextOccurrence(date.atStartOfDay(cairo), ctx)!!.time)
        }
        val before = g.copy(anchor = Anchor.AfterPrayer(Prayer.FAJR, -10))
        val fajr = ctx.prayers.timesFor(LocalDate.of(2026, 10, 19), cairo)[Prayer.FAJR]!!
        assertEquals(fajr.minusMinutes(10), before.nextOccurrence(at(19, 0, 0), ctx)!!.time)
    }

    @Test
    fun prayerAnchorNeedsLocation() {
        val g = AlarmGroup(1, anchor = Anchor.AfterPrayer(Prayer.FAJR, 5), repeat = Repeat.DAILY)
        assertNull(g.nextOccurrence(at(19, 0, 0), ctx.copy(prayers = PrayerSettings())))
    }

    @Test
    fun disabledGroupNeverRings() {
        assertNull(AlarmGroup(1, enabled = false).nextOccurrence(at(19, 0, 0), ctx))
    }
}
