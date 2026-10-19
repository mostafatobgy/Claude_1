package com.example.prayeralarm.prayer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

enum class Prayer { FAJR, SUNRISE, DHUHR, ASR, MAGHRIB, ISHA }

/**
 * Twilight angles used by the major calculation authorities.
 * Isha is either a sun angle below the horizon or a fixed number of minutes after Maghrib.
 */
enum class CalculationMethod(val fajrAngle: Double, val ishaAngle: Double?, val ishaMinutes: Int?) {
    EGYPT(19.5, 17.5, null),
    MWL(18.0, 17.0, null),
    MAKKAH(18.5, null, 90),
    KARACHI(18.0, 18.0, null),
    ISNA(15.0, 15.0, null),
    GULF(19.5, null, 90),
}

/** Shadow length factor for Asr: Standard (Shafi'i, Maliki, Hanbali) = 1, Hanafi = 2. */
enum class AsrMethod(val factor: Double) { STANDARD(1.0), HANAFI(2.0) }

/**
 * Astronomical prayer time calculation, ported from the PrayTimes.org algorithm (v2.3).
 * Uses the "angle based" adjustment for high latitudes, where twilight may never end.
 */
object PrayerTimesCalculator {
    private const val RISE_SET_ANGLE = 0.833

    fun calculate(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
        method: CalculationMethod,
        asrMethod: AsrMethod,
    ): Map<Prayer, ZonedDateTime> {
        val timeZone = zone.rules.getOffset(date.atTime(12, 0)).totalSeconds / 3600.0
        val jDate = julian(date.year, date.monthValue, date.dayOfMonth) - longitude / (15.0 * 24.0)
        val sun = Sun(jDate, latitude)

        // Initial guesses in hours, converted to fractions of a day.
        var fajr = sun.sunAngleTime(method.fajrAngle, 5 / 24.0, ccw = true)
        val sunrise = sun.sunAngleTime(RISE_SET_ANGLE, 6 / 24.0, ccw = true)
        val dhuhr = sun.midDay(12 / 24.0)
        val asr = sun.asrTime(asrMethod.factor, 13 / 24.0)
        val sunset = sun.sunAngleTime(RISE_SET_ANGLE, 18 / 24.0)
        var isha = method.ishaAngle?.let { sun.sunAngleTime(it, 18 / 24.0) } ?: Double.NaN

        val shift = timeZone - longitude / 15.0
        val times = doubleArrayOf(fajr, sunrise, dhuhr, asr, sunset, isha).map { it + shift }
        fajr = times[0]
        val maghrib = times[4]
        isha = times[5]

        val night = timeDiff(times[4], times[1])
        fajr = adjustHighLatitude(fajr, times[1], method.fajrAngle, night, ccw = true)
        method.ishaAngle?.let { isha = adjustHighLatitude(isha, maghrib, it, night, ccw = false) }
        method.ishaMinutes?.let { isha = maghrib + it / 60.0 }

        val hours = mapOf(
            Prayer.FAJR to fajr,
            Prayer.SUNRISE to times[1],
            Prayer.DHUHR to times[2],
            Prayer.ASR to times[3],
            Prayer.MAGHRIB to maghrib,
            Prayer.ISHA to isha,
        )
        val midnightUtc = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return hours.filterValues { !it.isNaN() }.mapValues { (_, h) ->
            val minutes = ((h - timeZone) * 60).roundToLong()
            Instant.ofEpochSecond(midnightUtc + minutes * 60).atZone(zone)
        }
    }

    private class Sun(private val jDate: Double, private val lat: Double) {
        /** Returns declination and equation of time for a Julian date. */
        private fun position(jd: Double): Pair<Double, Double> {
            val d = jd - 2451545.0
            val g = fixAngle(357.529 + 0.98560028 * d)
            val q = fixAngle(280.459 + 0.98564736 * d)
            val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
            val e = 23.439 - 0.00000036 * d
            val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
            val eqt = q / 15.0 - fixHour(ra)
            val decl = darcsin(dsin(e) * dsin(l))
            return decl to eqt
        }

        fun midDay(time: Double): Double = fixHour(12 - position(jDate + time).second)

        fun sunAngleTime(angle: Double, time: Double, ccw: Boolean = false): Double {
            val decl = position(jDate + time).first
            val noon = midDay(time)
            val t = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15.0
            return noon + if (ccw) -t else t
        }

        fun asrTime(factor: Double, time: Double): Double {
            val decl = position(jDate + time).first
            val angle = -darccot(factor + dtan(abs(lat - decl)))
            return sunAngleTime(angle, time)
        }
    }

    private fun adjustHighLatitude(time: Double, base: Double, angle: Double, night: Double, ccw: Boolean): Double {
        val portion = angle / 60.0 * night
        val diff = if (ccw) timeDiff(time, base) else timeDiff(base, time)
        return if (time.isNaN() || diff > portion) base + if (ccw) -portion else portion else time
    }

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun timeDiff(a: Double, b: Double) = fixHour(b - a)
    private fun fix(a: Double, b: Double): Double {
        val r = a - b * floor(a / b)
        return if (r < 0) r + b else r
    }
    private fun fixAngle(a: Double) = fix(a, 360.0)
    private fun fixHour(a: Double) = fix(a, 24.0)
    private fun dtr(d: Double) = d * Math.PI / 180.0
    private fun rtd(r: Double) = r * 180.0 / Math.PI
    private fun dsin(d: Double) = sin(dtr(d))
    private fun dcos(d: Double) = cos(dtr(d))
    private fun dtan(d: Double) = tan(dtr(d))
    private fun darcsin(x: Double) = rtd(asin(x))
    private fun darccos(x: Double) = rtd(acos(x))
    private fun darctan2(y: Double, x: Double) = rtd(atan2(y, x))
    private fun darccot(x: Double) = rtd(atan(1 / x))
}
