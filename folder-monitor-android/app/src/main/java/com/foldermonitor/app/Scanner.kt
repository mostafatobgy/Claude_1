package com.foldermonitor.app

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.PriorityQueue

/**
 * Measures a folder tree in one pass. Symlinks are not followed; unreadable items are skipped.
 *
 * Besides the total it records, for finding out *what* grew:
 *  - [Result.children]: size of each top-level item,
 *  - [Result.dirs]: total size of every sub-folder of at least [MIN_DIR] (relative path, "" = root),
 *  - [Result.recent]: the biggest files modified at or after `since`.
 */
object Scanner {
    data class Result(
        val total: Long,
        val children: Map<String, Long>,
        val dirs: Map<String, Long> = emptyMap(),
        val recent: List<FileInfo> = emptyList(),
        val error: String? = null,
    )

    /** Children kept per sample (largest first) so the saved history stays small. */
    private const val MAX_CHILDREN = 300
    /** Smaller folders are left out of the snapshot (their growth can't matter). */
    const val MIN_DIR = 1_000_000L
    private const val MAX_RECENT = 10

    fun scan(root: File, since: Long = Long.MAX_VALUE): Result {
        if (!root.isDirectory) return Result(0, emptyMap(), error = "الفولدر غير موجود")
        if (root.listFiles() == null) return Result(0, emptyMap(), error = "لا يوجد إذن لقراءة الفولدر")
        val rootPath = root.toPath()
        val children = HashMap<String, Long>()
        val dirs = HashMap<String, Long>()
        val recent = PriorityQueue<FileInfo>(compareBy { it.size }) // smallest on top
        val running = java.util.ArrayDeque<Long>() // size so far of each open folder
        var total = 0L

        try {
            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    running.push(0L)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE // symlinks, devices…
                    val size = attrs.size()
                    if (running.isNotEmpty()) running.push(running.pop() + size)
                    if (file.parent == rootPath) children[file.fileName.toString()] = size
                    if (attrs.lastModifiedTime().toMillis() >= since && size > 0) {
                        if (recent.size < MAX_RECENT) {
                            recent.add(FileInfo(rootPath.relativize(file).toString(), size))
                        } else if (size > recent.peek()!!.size) {
                            recent.poll()
                            recent.add(FileInfo(rootPath.relativize(file).toString(), size))
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException?) = FileVisitResult.CONTINUE

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    val size = running.pop()
                    if (running.isNotEmpty()) running.push(running.pop() + size) else total = size
                    val rel = rootPath.relativize(dir).toString()
                    if (size >= MIN_DIR || rel.isEmpty()) dirs[rel] = size
                    if (dir.parent == rootPath) children[dir.fileName.toString()] = size
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: Exception) {
            return Result(0, emptyMap(), error = "لا يوجد إذن لقراءة الفولدر")
        }

        val kept = if (children.size <= MAX_CHILDREN) children
        else children.entries.sortedByDescending { it.value }.take(MAX_CHILDREN).associate { it.key to it.value }
        return Result(total, kept, dirs, recent.sortedByDescending { it.size })
    }
}
