package com.foldermonitor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class GrowthTest {
    private val mb = Growth.MB
    private val rules = Rules(daily = false, intervalMin = 1, windowMin = 10, thresholdMb = 100, thresholdPercent = 0, cooldownMin = 15)
    private val min = 60_000L

    @get:Rule val tmp = TemporaryFolder()

    @Test fun alertsOnFastGrowthAndNamesTopGrower() {
        val h = FolderHistory("/sdcard/Download")
        h.add(Sample(0, 1000 * mb, mapOf("a.zip" to 10 * mb, "old" to 990 * mb)), rules.windowMs)
        h.add(Sample(min, 1200 * mb, mapOf("a.zip" to 210 * mb, "old" to 990 * mb)), rules.windowMs)
        val a = Growth.check(h, rules, min)
        assertNotNull(a)
        assertEquals(200 * mb, a!!.growth)
        assertEquals(listOf("a.zip" to 200 * mb), a.topGrowers)
        assertEquals("Download", a.name)
        assertTrue(a.text().contains("a.zip"))
        assertTrue(h.isAlerting(min, rules))
    }

    @Test fun noAlertBelowThreshold() {
        val h = FolderHistory("/x")
        h.add(Sample(0, 1000 * mb, emptyMap()), rules.windowMs)
        h.add(Sample(min, 1050 * mb, emptyMap()), rules.windowMs)
        assertNull(Growth.check(h, rules, min))
    }

    @Test fun slowGrowthNeverAlertsAndHistoryIsTrimmed() {
        val h = FolderHistory("/x")
        for (i in 0 until 50) { // +30 MB every 5 minutes: 60 MB per 10-minute window
            val t = i * 5 * min
            h.add(Sample(t, (1000 + 30L * i) * mb, emptyMap()), rules.windowMs)
            assertNull(Growth.check(h, rules, t))
        }
        assertTrue(h.samples.size <= 4)
    }

    @Test fun measuresFromSmallestSizeInWindow() {
        val h = FolderHistory("/x")
        h.add(Sample(0, 500 * mb, emptyMap()), rules.windowMs)
        h.add(Sample(min, 100 * mb, emptyMap()), rules.windowMs) // files deleted
        h.add(Sample(2 * min, 450 * mb, emptyMap()), rules.windowMs) // then refilled fast
        assertEquals(350 * mb, Growth.check(h, rules, 2 * min)!!.growth)
    }

    @Test fun cooldownPreventsRepeats() {
        val h = FolderHistory("/x")
        h.add(Sample(0, 0, emptyMap()), rules.windowMs)
        h.add(Sample(min, 200 * mb, emptyMap()), rules.windowMs)
        assertNotNull(Growth.check(h, rules, min))
        h.add(Sample(2 * min, 400 * mb, emptyMap()), rules.windowMs)
        assertNull(Growth.check(h, rules, 2 * min))
        h.add(Sample(17 * min, 900 * mb, emptyMap()), rules.windowMs)
        assertNotNull(Growth.check(h, rules, 17 * min))
    }

    @Test fun percentThreshold() {
        val r = rules.copy(thresholdMb = 0, thresholdPercent = 50)
        val h = FolderHistory("/x")
        h.add(Sample(0, 10 * mb, emptyMap()), r.windowMs)
        h.add(Sample(min, 16 * mb, emptyMap()), r.windowMs)
        assertEquals(60, Growth.check(h, r, min)!!.percent.toInt())
        // empty folder + small file isn't an "infinite %" alert
        val e = FolderHistory("/e")
        e.add(Sample(0, 0, emptyMap()), r.windowMs)
        e.add(Sample(min, 100_000, emptyMap()), r.windowMs)
        assertNull(Growth.check(e, r, min))
    }

    @Test fun scannerCountsNestedFiles() {
        val root = tmp.newFolder("w")
        File(root, "a/b").mkdirs()
        File(root, "a/b/f.bin").writeBytes(ByteArray(300))
        File(root, "top.txt").writeBytes(ByteArray(50))
        val r = Scanner.scan(root)
        assertNull(r.error)
        assertEquals(350L, r.total)
        assertEquals(mapOf("a" to 300L, "top.txt" to 50L), r.children)
        assertNotNull(Scanner.scan(File(root, "missing")).error)
    }

    @Test fun formatting() {
        assertEquals("512 B", formatSize(512))
        assertEquals("1.5 KB", formatSize(1536))
        assertEquals("-3.0 MB", formatSize(-3 * mb))
        assertEquals("45 ثانية", formatSpan(45_000))
        assertEquals("12 دقيقة", formatSpan(12 * min))
    }

    // ---------------------------------------------------------------- daily mode

    private val hour = Rules.HOUR
    private val day = 24 * hour

    @Test fun defaultIsDailyAtMidnightWith20Percent() {
        val d = Rules()
        assertTrue(d.daily)
        assertEquals(0, d.dailyHour)
        assertEquals(20, d.thresholdPercent)
        assertEquals(0L, d.thresholdMb)
        assertEquals("12:00 ص", formatClock(0, 0))
        assertEquals("1:30 م", formatClock(13, 30))
    }

    @Test fun dailyAlertsOn20PercentOverYesterday() {
        val r = Rules()
        val h = FolderHistory("/WhatsApp")
        h.add(Sample(0, 1000 * mb, mapOf("Video" to 500 * mb)), r.windowMs)
        h.add(Sample(day + 2 * min, 1150 * mb, mapOf("Video" to 650 * mb)), r.windowMs) // +15%: quiet
        assertNull(Growth.check(h, r, day + 2 * min))
        h.add(Sample(2 * day, 1400 * mb, mapOf("Video" to 900 * mb)), r.windowMs) // +21.7% over yesterday
        val a = Growth.check(h, r, 2 * day)
        assertNotNull(a)
        assertEquals(250 * mb, a!!.growth)
        assertEquals(21, a.percent.toInt())
        assertEquals(listOf("Video" to 250 * mb), a.topGrowers)
        assertTrue(a.text().contains("خلال يوم"))
        // the day before yesterday is no longer part of the comparison
        assertEquals(2, h.samples.size)
    }

    @Test fun dailyAlertsAgainNextDay() {
        val r = Rules()
        val h = FolderHistory("/x")
        h.add(Sample(0, 100 * mb, emptyMap()), r.windowMs)
        h.add(Sample(day, 200 * mb, emptyMap()), r.windowMs)
        assertNotNull(Growth.check(h, r, day))
        h.add(Sample(2 * day, 300 * mb, emptyMap()), r.windowMs)
        assertNotNull(Growth.check(h, r, 2 * day))
    }

    @Test fun nextRunIsNextMidnight() {
        val tz = TimeZone.getTimeZone("Africa/Cairo")
        fun at(y: Int, m: Int, d: Int, hh: Int, mm: Int) = Calendar.getInstance(tz).apply {
            clear(); set(y, m - 1, d, hh, mm, 0)
        }.timeInMillis
        val r = Rules()
        assertEquals(at(2026, 11, 14, 0, 0), r.nextRun(at(2026, 11, 13, 19, 10), tz))
        assertEquals(at(2026, 11, 15, 0, 0), r.nextRun(at(2026, 11, 14, 0, 0), tz)) // just ran
        val r9 = r.copy(dailyHour = 21, dailyMinute = 30)
        assertEquals(at(2026, 11, 13, 21, 30), r9.nextRun(at(2026, 11, 13, 19, 10), tz))
        val every = Rules(daily = false, intervalMin = 15)
        assertEquals(1000L + 15 * min, every.nextRun(1000L, tz))
    }

    // ------------------------------------------------- whole phone + finding the cause

    @Test fun phoneUsesItsOwnMbThreshold() {
        val r = Rules() // phone: 1024 MB, folders: 20%
        val h = FolderHistory(Alert.PHONE)
        h.add(Sample(0, 100_000 * mb, emptyMap()), r.windowMs)
        h.add(Sample(day, 100_900 * mb, emptyMap()), r.windowMs) // +900 MB: quiet
        assertNull(Growth.check(h, r, day))
        h.add(Sample(2 * day, 102_000 * mb, emptyMap()), r.windowMs) // +1.1 GB
        val a = Growth.check(h, r, 2 * day)!!
        assertEquals(1100 * mb, a.growth)
        assertTrue(a.title().contains("الهاتف"))
    }

    @Test fun culpritsAreTheDeepestFoldersHoldingTheGrowth() {
        val prev = mapOf(
            "" to 10_000 * mb,
            "Android" to 3000 * mb, "Android/media" to 3000 * mb,
            "Android/media/com.whatsapp" to 2000 * mb,
            "Android/media/com.whatsapp/WhatsApp" to 2000 * mb,
            "Android/media/com.whatsapp/WhatsApp/Media" to 2000 * mb,
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video" to 1500 * mb,
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images" to 500 * mb,
            "Download" to 1000 * mb,
            "DCIM" to 4000 * mb,
        )
        val cur = prev + mapOf(
            "" to 11_700 * mb,
            "Android" to 4200 * mb, "Android/media" to 4200 * mb,
            "Android/media/com.whatsapp" to 3200 * mb,
            "Android/media/com.whatsapp/WhatsApp" to 3200 * mb,
            "Android/media/com.whatsapp/WhatsApp/Media" to 3200 * mb,
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video" to 2700 * mb, // +1.2 GB
            "Download" to 1500 * mb,                                                    // +500 MB
            "DCIM" to 4005 * mb,                                                        // +5 MB: noise
        )
        val got = Culprits.folders(prev, cur, 1700 * mb)
        assertEquals(
            listOf(
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video" to 1200 * mb,
                "Download" to 500 * mb,
            ),
            got,
        )
        assertEquals("…/Media/WhatsApp Video", Alert.shortFolder(got[0].first))
    }

    @Test fun growthSpreadAcrossSubfoldersListsEachOne() {
        val prev = mapOf("" to 0L, "Movies" to 0L)
        val cur = mapOf("" to 1000 * mb, "Movies" to 1000 * mb, "Movies/a" to 300 * mb, "Movies/b" to 300 * mb, "Movies/c" to 400 * mb)
        assertEquals(listOf("Movies/c" to 400 * mb, "Movies/a" to 300 * mb, "Movies/b" to 300 * mb),
            Culprits.folders(prev, cur, 1000 * mb))
    }

    @Test fun scannerRecordsFolderSizesAndRecentFiles() {
        val root = tmp.newFolder("phone")
        File(root, "Old").mkdirs()
        val old = File(root, "Old/old.bin").apply { writeBytes(ByteArray(2 * mb.toInt())) }
        old.setLastModified(1_000L)
        File(root, "Download/x").mkdirs()
        File(root, "Download/x/new.mp4").writeBytes(ByteArray(3 * mb.toInt()))
        File(root, "Download/small.txt").writeBytes(ByteArray(10))
        val r = Scanner.scan(root, since = 1_000_000L)
        assertEquals(5 * mb + 10, r.total)
        assertEquals(5 * mb + 10, r.dirs[""])
        assertEquals(3 * mb + 10, r.dirs["Download"])
        assertEquals(3 * mb, r.dirs["Download/x"])
        assertEquals(2 * mb, r.dirs["Old"])
        assertEquals(mapOf("Old" to 2 * mb, "Download" to 3 * mb + 10), r.children)
        assertEquals("Download/x/new.mp4", r.recent.first().path) // old.bin is not "recent"
        assertTrue(r.recent.none { it.path.contains("old.bin") })
    }

    @Test fun alertTextNamesFoldersFilesAndAppData() {
        val a = Alert(
            Alert.PHONE, 2048 * mb, 2.0, 24 * hour, 102_000 * mb, emptyList(),
            folders = listOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video" to 1200 * mb),
            files = listOf(FileInfo("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/VID-1.mp4", 900 * mb)),
            unexplained = 700 * mb,
        )
        val t = a.text()
        assertTrue(t, t.contains("📁 السبب: …/Media/WhatsApp Video (+1.2 GB)"))
        assertTrue(t, t.contains("VID-1.mp4 (900.0 MB) في …/Media/WhatsApp Video"))
        assertTrue(t, t.contains("700.0 MB في بيانات التطبيقات"))
    }
}
