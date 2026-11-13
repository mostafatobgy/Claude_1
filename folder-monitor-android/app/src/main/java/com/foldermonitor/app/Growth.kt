package com.foldermonitor.app

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One size measurement of a folder. [children] = size of each top-level item inside it. */
data class Sample(val t: Long, val size: Long, val children: Map<String, Long>)

/** User settings that decide when to check and when to alert. */
data class Rules(
    val daily: Boolean = true,       // true: once a day at dailyHour:dailyMinute; false: every intervalMin
    val dailyHour: Int = 0,          // 0 = 12 AM (midnight)
    val dailyMinute: Int = 0,
    val intervalMin: Int = 60,       // interval mode: how often to measure
    val windowMin: Int = 60,         // interval mode: the "short period"
    val thresholdMb: Long = 0,       // alert if growth >= this many MB (0 = off) ...
    val thresholdPercent: Int = 20,  // ... or >= this percent of the earlier size (0 = off)
    val cooldownMin: Int = 30,       // interval mode: don't repeat an alert for the same folder sooner
) {
    // Daily mode compares with the previous day's check. 20 h (not 24) so a check that ran a little
    // late yesterday still counts as "yesterday" and older days drop out.
    val windowMs: Long get() = if (daily) 20 * HOUR else windowMin * 60_000L
    val cooldownMs: Long get() = if (daily) 12 * HOUR else cooldownMin * 60_000L

    /** Wall-clock time of the next scheduled check after [now]. */
    fun nextRun(now: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        if (!daily) return now + intervalMin * 60_000L
        val c = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, dailyHour)
            set(Calendar.MINUTE, dailyMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now + 30_000L) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    companion object {
        const val HOUR = 3_600_000L
    }
}

/** "12:00 ص" style time. */
fun formatClock(hour: Int, minute: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return String.format(Locale.US, "%d:%02d %s", h12, minute, if (hour < 12) "ص" else "م")
}

/** Recent measurements of one folder, trimmed to the alert window. */
class FolderHistory(val path: String) {
    val samples = mutableListOf<Sample>()
    var lastAlertAt: Long? = null
    var lastAlertText: String = ""
    var error: String = ""

    val current: Long get() = samples.lastOrNull()?.size ?: 0L
    val lastCheck: Long? get() = samples.lastOrNull()?.t

    fun add(sample: Sample, windowMs: Long) {
        samples.add(sample)
        // Keep one sample at/just before the window start so the whole window is covered.
        val cutoff = sample.t - windowMs
        while (samples.size > 2 && samples[1].t <= cutoff) samples.removeAt(0)
    }

    /** Growth from the smallest size seen in the window to now, and that base sample. */
    fun growth(): Pair<Long, Sample?> {
        if (samples.size < 2) return 0L to null
        val base = samples.dropLast(1).minBy { it.size }
        return (current - base.size) to base
    }

    fun isAlerting(now: Long, rules: Rules): Boolean =
        lastAlertAt?.let { now - it < rules.cooldownMs } ?: false
}

data class Alert(
    val path: String,
    val growth: Long,
    val percent: Double,
    val spanMs: Long,
    val size: Long,
    val topGrowers: List<Pair<String, Long>>,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    fun title() = "⚠ زيادة سريعة في: $name"

    fun text(): String {
        val pct = if (percent.isInfinite()) "" else " (${percent.toInt()}%)"
        var msg = "زاد ${formatSize(growth)}$pct خلال ${formatSpan(spanMs)} — الحجم الآن ${formatSize(size)}"
        if (topGrowers.isNotEmpty()) {
            msg += "\nأكثر ما زاد: " + topGrowers.joinToString("، ") { (n, g) -> "$n (+${formatSize(g)})" }
        }
        return msg
    }
}

object Growth {
    const val MB = 1024L * 1024L

    /** Returns an alert if [h] grew past the thresholds within the window (and not in cooldown). */
    fun check(h: FolderHistory, rules: Rules, now: Long): Alert? {
        val (growth, base) = h.growth()
        if (base == null || growth <= 0) return null
        val mbHit = rules.thresholdMb > 0 && growth >= rules.thresholdMb * MB
        // Percent only makes sense from a non-trivial starting size; otherwise any first file is "infinite %".
        val percent = if (base.size > 0) growth * 100.0 / base.size else Double.POSITIVE_INFINITY
        val pctHit = rules.thresholdPercent > 0 && base.size >= MB && percent >= rules.thresholdPercent
        if (!mbHit && !pctHit) return null
        if (h.isAlerting(now, rules)) return null
        h.lastAlertAt = now

        val latest = h.samples.last()
        val top = latest.children
            .map { (name, size) -> name to size - (base.children[name] ?: 0L) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
        return Alert(h.path, growth, percent, now - base.t, latest.size, top).also {
            h.lastAlertText = it.text()
        }
    }
}

fun formatSize(bytes: Long): String {
    val sign = if (bytes < 0) "-" else ""
    var n = kotlin.math.abs(bytes).toDouble()
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var i = 0
    while (n >= 1024 && i < units.lastIndex) { n /= 1024; i++ }
    return if (i == 0) "$sign${n.toLong()} B" else sign + String.format(Locale.US, "%.1f %s", n, units[i])
}

fun formatSpan(ms: Long): String {
    val sec = ms / 1000
    return when {
        sec < 60 -> "$sec ثانية"
        sec < 3600 -> "${sec / 60} دقيقة"
        sec in 20 * 3600..30 * 3600 -> "يوم"
        else -> String.format(Locale.US, "%.1f ساعة", sec / 3600.0)
    }
}
