package com.foldermonitor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GrowthTest {
    private val mb = Growth.MB
    private val rules = Rules(intervalMin = 1, windowMin = 10, thresholdMb = 100, cooldownMin = 15)
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
}
