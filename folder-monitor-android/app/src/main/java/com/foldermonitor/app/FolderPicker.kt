package com.foldermonitor.app

import android.app.Activity
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import java.io.File

/** A simple folder browser (the system picker can't select Download or the storage root). */
class FolderPicker(private val activity: Activity, private val onPicked: (File) -> Unit) {

    companion object {
        val internal: File get() = Environment.getExternalStorageDirectory()

        fun displayPath(path: String): String {
            val root = internal.absolutePath
            return when {
                path == root -> "الذاكرة الداخلية"
                path.startsWith("$root/") -> "الذاكرة الداخلية/" + path.removePrefix("$root/")
                else -> path
            }
        }
    }

    fun show() = showStart()

    /** Storage volumes + common shortcuts. */
    private fun showStart() {
        val entries = mutableListOf<Pair<String, File>>()
        entries += "📱 الذاكرة الداخلية (كل الهاتف)" to internal
        sdCards().forEach { entries += "💾 بطاقة الذاكرة ${it.name}" to it }
        listOf(
            "⬇ التنزيلات" to File(internal, Environment.DIRECTORY_DOWNLOADS),
            "📷 الكاميرا (DCIM)" to File(internal, Environment.DIRECTORY_DCIM),
            "🖼 الصور" to File(internal, Environment.DIRECTORY_PICTURES),
            "🎬 الفيديو" to File(internal, Environment.DIRECTORY_MOVIES),
            "📄 المستندات" to File(internal, Environment.DIRECTORY_DOCUMENTS),
            "💬 واتساب" to File(internal, "Android/media/com.whatsapp/WhatsApp/Media"),
            "💬 واتساب أعمال" to File(internal, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media"),
            "✈ تيليجرام" to File(internal, "Android/media/org.telegram.messenger/Telegram"),
            "✈ تيليجرام (قديم)" to File(internal, "Telegram"),
        ).filter { it.second.isDirectory }.forEach { entries += it }

        AlertDialog.Builder(activity)
            .setTitle("اختر فولدر")
            .setItems(entries.map { it.first }.toTypedArray()) { _, i -> showDir(entries[i].second) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showDir(dir: File) {
        val subs = (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
        val labels = subs.map { "📁 ${it.name}" }.ifEmpty { listOf("(لا توجد فولدرات داخلية)") }
        AlertDialog.Builder(activity)
            .setTitle(displayPath(dir.absolutePath))
            .setItems(labels.toTypedArray()) { _, i -> if (subs.isNotEmpty()) showDir(subs[i]) else showDir(dir) }
            .setPositiveButton("✓ راقب هذا الفولدر") { _, _ -> onPicked(dir) }
            .setNeutralButton("رجوع") { _, _ ->
                val parent = dir.parentFile
                if (dir == internal || sdCards().contains(dir) || parent == null || !parent.canRead()) showStart()
                else showDir(parent)
            }
            .show()
    }

    /** Roots of removable storage, derived from the app's per-volume folders. */
    private fun sdCards(): List<File> = activity.getExternalFilesDirs(null)
        .filterNotNull()
        .mapNotNull { f -> f.absolutePath.substringBefore("/Android/").takeIf { it != f.absolutePath } }
        .map(::File)
        .filter { it != internal && it.isDirectory }
}
