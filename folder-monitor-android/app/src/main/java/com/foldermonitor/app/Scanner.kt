package com.foldermonitor.app

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Measures folder sizes. Symlinks are not followed; unreadable items are skipped. */
object Scanner {
    data class Result(val total: Long, val children: Map<String, Long>, val error: String? = null)

    /** Children kept per sample (largest first) so the saved history stays small. */
    private const val MAX_CHILDREN = 300

    fun scan(root: File): Result {
        if (!root.isDirectory) return Result(0, emptyMap(), "الفولدر غير موجود")
        val children = HashMap<String, Long>()
        try {
            Files.newDirectoryStream(root.toPath()).use { stream ->
                for (child in stream) children[child.fileName.toString()] = itemSize(child)
            }
        } catch (e: Exception) {
            return Result(0, emptyMap(), "لا يوجد إذن لقراءة الفولدر")
        }
        val total = children.values.sum()
        val kept = if (children.size <= MAX_CHILDREN) children
        else children.entries.sortedByDescending { it.value }.take(MAX_CHILDREN).associate { it.key to it.value }
        return Result(total, kept)
    }

    private fun itemSize(p: Path): Long = try {
        val attrs = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        when {
            attrs.isSymbolicLink -> 0L
            attrs.isDirectory -> treeSize(p)
            else -> attrs.size()
        }
    } catch (e: Exception) {
        0L
    }

    private fun treeSize(dir: Path): Long {
        var total = 0L
        try {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException?) = FileVisitResult.CONTINUE
                override fun postVisitDirectory(d: Path, exc: IOException?) = FileVisitResult.CONTINUE
            })
        } catch (e: Exception) {
            // keep whatever was counted
        }
        return total
    }
}
