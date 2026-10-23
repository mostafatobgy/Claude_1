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

    /** The phone number stored in the file name, or null when the call had no known number. */
    val number: String?
        get() {
            // File name pattern: <number>__<yyyyMMdd-HHmmss>.m4a  (number may be "unknown")
            val label = file.nameWithoutExtension.substringBefore("__").trim()
            return if (label.isEmpty() || label == "unknown") null else label
        }

    val whenText: String get() = DATE_FMT.format(Date(modified))

    companion object {
        private val DATE_FMT = SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale.getDefault())
    }
}

/** A recording plus the already-resolved title/subtitle shown in the list. */
data class RecordingItem(val recording: Recording, val title: String, val subtitle: String) {
    val file: File get() = recording.file
}

/** Central place that decides where recordings live and how they are named. */
object Storage {
    /** App-private external folder — no storage permission needed, removed when the app is uninstalled. */
    fun dir(context: Context): File {
        val d = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun newFile(context: Context, number: String?): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // Keep only dialable characters so the number survives as an ASCII file name.
        val safe = (number ?: "unknown")
            .replace(Regex("[^0-9+*#]"), "")
            .take(20)
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
