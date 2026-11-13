package com.foldermonitor.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings (SharedPreferences) and measurement history (a JSON file in app storage). */
object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("monitor", Context.MODE_PRIVATE)
    private val historyLock = Any()

    fun folders(ctx: Context): List<String> {
        val arr = JSONArray(prefs(ctx).getString("folders", "[]"))
        return List(arr.length()) { arr.getString(it) }
    }

    fun setFolders(ctx: Context, list: List<String>) {
        prefs(ctx).edit().putString("folders", JSONArray(list).toString()).apply()
    }

    /** Bump when the default rules change in a way existing installs should adopt. */
    private const val RULES_VERSION = 2

    fun rules(ctx: Context): Rules {
        val p = prefs(ctx)
        if (p.getInt("rulesVersion", 1) < RULES_VERSION) {
            // v2: default became a daily check at 12 AM with a 20% alert — apply it once.
            setRules(ctx, Rules())
        }
        val d = Rules()
        return Rules(
            daily = p.getBoolean("daily", d.daily),
            dailyHour = p.getInt("dailyHour", d.dailyHour),
            dailyMinute = p.getInt("dailyMinute", d.dailyMinute),
            intervalMin = p.getInt("interval", d.intervalMin),
            windowMin = p.getInt("window", d.windowMin),
            thresholdMb = p.getLong("mb", d.thresholdMb),
            thresholdPercent = p.getInt("pct", d.thresholdPercent),
            cooldownMin = p.getInt("cooldown", d.cooldownMin),
            phoneMb = p.getLong("phoneMb", d.phoneMb),
            phonePercent = p.getInt("phonePct", d.phonePercent),
        )
    }

    fun setRules(ctx: Context, r: Rules) {
        prefs(ctx).edit()
            .putInt("rulesVersion", RULES_VERSION)
            .putBoolean("daily", r.daily).putInt("dailyHour", r.dailyHour).putInt("dailyMinute", r.dailyMinute)
            .putInt("interval", r.intervalMin).putInt("window", r.windowMin)
            .putLong("mb", r.thresholdMb).putInt("pct", r.thresholdPercent)
            .putInt("cooldown", r.cooldownMin)
            .putLong("phoneMb", r.phoneMb).putInt("phonePct", r.phonePercent).commit()
    }

    fun nextRun(ctx: Context) = prefs(ctx).getLong("nextRun", 0L)
    fun setNextRun(ctx: Context, t: Long) = prefs(ctx).edit().putLong("nextRun", t).apply()

    /** Whole-phone check (on by default). */
    fun phoneEnabled(ctx: Context) = prefs(ctx).getBoolean("phone", true)
    fun setPhoneEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("phone", on).apply()

    /** Everything checked: the whole phone (if on) followed by the added folders. */
    fun targets(ctx: Context): List<String> =
        (if (phoneEnabled(ctx)) listOf(Alert.PHONE) else emptyList()) + folders(ctx)

    fun phoneMeasureVersion(ctx: Context) = prefs(ctx).getInt("phoneMeasure", 1)
    fun setPhoneMeasureVersion(ctx: Context, v: Int) = prefs(ctx).edit().putInt("phoneMeasure", v).apply()

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()

    fun lastRun(ctx: Context) = prefs(ctx).getLong("lastRun", 0L)
    fun setLastRun(ctx: Context, t: Long) = prefs(ctx).edit().putLong("lastRun", t).apply()

    // ---- per-folder size snapshots from the last check, to see which sub-folders grew

    private fun snapshotDir(ctx: Context) = File(ctx.filesDir, "snapshots").apply { mkdirs() }
    private fun snapshotName(key: String) = key.hashCode().toUInt().toString(16) + ".tsv"

    fun loadSnapshot(ctx: Context, key: String): Map<String, Long>? {
        val f = File(snapshotDir(ctx), snapshotName(key))
        if (!f.exists()) return null
        return try {
            f.readLines().mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) null else line.substring(tab + 1) to line.substring(0, tab).toLong()
            }.toMap()
        } catch (e: Exception) {
            null
        }
    }

    fun saveSnapshot(ctx: Context, key: String, dirs: Map<String, Long>) {
        val f = File(snapshotDir(ctx), snapshotName(key))
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { w -> for ((path, size) in dirs) { w.write("$size\t$path\n") } }
        tmp.renameTo(f)
    }

    /** Delete snapshots of folders that are no longer checked. */
    fun pruneSnapshots(ctx: Context, keep: Collection<String>) {
        val names = keep.map(::snapshotName).toSet()
        snapshotDir(ctx).listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    private fun historyFile(ctx: Context) = File(ctx.filesDir, "history.json")

    fun loadHistory(ctx: Context): MutableMap<String, FolderHistory> = synchronized(historyLock) {
        val out = LinkedHashMap<String, FolderHistory>()
        val f = historyFile(ctx)
        if (!f.exists()) return out
        try {
            val root = JSONObject(f.readText())
            for (path in root.keys()) {
                val o = root.getJSONObject(path)
                val h = FolderHistory(path)
                if (o.has("lastAlertAt")) h.lastAlertAt = o.getLong("lastAlertAt")
                h.lastAlertText = o.optString("lastAlertText")
                h.error = o.optString("error")
                val arr = o.getJSONArray("samples")
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val c = s.getJSONObject("c")
                    val children = HashMap<String, Long>()
                    for (k in c.keys()) children[k] = c.getLong(k)
                    h.samples.add(Sample(s.getLong("t"), s.getLong("s"), children))
                }
                out[path] = h
            }
        } catch (e: Exception) {
            // corrupt file: start fresh rather than crash
        }
        return out
    }

    fun saveHistory(ctx: Context, map: Map<String, FolderHistory>) = synchronized(historyLock) {
        val root = JSONObject()
        for ((path, h) in map) {
            val arr = JSONArray()
            for (s in h.samples) {
                arr.put(JSONObject().put("t", s.t).put("s", s.size).put("c", JSONObject(s.children as Map<*, *>)))
            }
            val o = JSONObject().put("samples", arr)
                .put("lastAlertText", h.lastAlertText).put("error", h.error)
            h.lastAlertAt?.let { o.put("lastAlertAt", it) }
            root.put(path, o)
        }
        val f = historyFile(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(f)
    }
}
