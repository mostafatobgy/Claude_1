package com.callrecorder.app

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved recording on disk. */
data class Recording(val file: File) {
    val sizeBytes: Long get() = file.length()
    val modified: Long get() = file.lastModified()

    /** The display title: a phone number / label plus the date, parsed from the file name. */
    val title: String
        get() {
            // File name pattern: <label>__<yyyyMMdd-HHmmss>.m4a  (label may be "unknown")
            val base = file.nameWithoutExtension
            val parts = base.split("__")
            val label = parts.getOrNull(0)?.replace('_', ' ')?.trim().orEmpty()
            return if (label.isEmpty() || label == "unknown") "رقم غير معروف" else label
        }

    val whenText: String
        get() = DATE_FMT.format(Date(modified))

    companion object {
        private val DATE_FMT = SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale.getDefault())
    }
}

/** Central place that decides where recordings live and how they are named. */
object Storage {
    /** App-private external folder — no storage permission needed, removed when the app is uninstalled. */
    fun dir(context: Context): File {
        val d = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun newFile(context: Context, label: String?): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = (label ?: "unknown")
            .replace(Regex("[^A-Za-z0-9+]"), "_")
            .take(24)
            .ifEmpty { "unknown" }
        return File(dir(context), "${safe}__${stamp}.m4a")
    }

    fun list(context: Context): List<Recording> =
        dir(context).listFiles { f -> f.isFile && f.extension == "m4a" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { Recording(it) }
            ?: emptyList()
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
