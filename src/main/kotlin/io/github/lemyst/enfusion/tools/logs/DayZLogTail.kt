package io.github.lemyst.enfusion.tools.logs

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Follows the newest log of one [kind] in a profile folder: the game starts a new file each run,
 * named with the start time, so "the log" is whichever matching file is newest.
 *
 * [poll] returns what is new since the last call: either the text appended to the same file, or,
 * when a newer file appeared, that file from its start with [Chunk.restarted] set. Lines are
 * returned whole; a line still being written stays buffered until its end arrives.
 *
 * This class uses no IntelliJ API, so it is tested on temporary files.
 */
class DayZLogTail(private val folder: Path, private val kind: DayZLogLines.Kind) {

    class Chunk(val file: Path, val lines: List<String>, val restarted: Boolean)

    private var current: Path? = null
    private var offset = 0L
    private var pending = ""

    /** The file to follow instead of the newest one, such as an older run chosen by hand. */
    var pinned: Path? = null
        set(value) {
            field = value
            current = null
        }

    fun poll(): Chunk? {
        val file = pinned ?: newest() ?: return null
        var restarted = false
        if (file != current) {
            current = file
            offset = 0
            pending = ""
            restarted = true
        }
        val size = runCatching { Files.size(file) }.getOrNull() ?: return null
        if (size < offset) {
            // Truncated or replaced in place: read it again from the start.
            offset = 0
            pending = ""
            restarted = true
        }
        if (size == offset) return if (restarted) Chunk(file, emptyList(), true) else null
        // A log can reach hundreds of megabytes: a new one is shown from its last part only, cut
        // at a line start.
        var skipPartialLine = false
        if (restarted && size - offset > MAX_READ) {
            offset = size - MAX_READ
            skipPartialLine = true
        }
        val bytes = runCatching {
            RandomAccessFile(file.toFile(), "r").use { raf ->
                raf.seek(offset)
                val length = minOf(size - offset, MAX_READ).toInt()
                ByteArray(length).also { raf.readFully(it) }
            }
        }.getOrNull() ?: return null
        offset += bytes.size
        var text = pending + String(bytes, StandardCharsets.UTF_8)
        if (skipPartialLine) text = text.substringAfter('\n', "")
        val end = text.lastIndexOf('\n')
        val complete = if (end < 0) "" else text.substring(0, end)
        pending = if (end < 0) text else text.substring(end + 1)
        val lines = if (complete.isEmpty() && end < 0) emptyList() else complete.split('\n').map { it.trimEnd('\r') }
        return Chunk(file, lines, restarted)
    }

    /** The newest file of [kind] in [folder], by last modification. */
    fun newest(): Path? = runs().firstOrNull()

    /** Every file of [kind] in [folder], newest first. */
    fun runs(): List<Path> = runCatching {
        Files.list(folder).use { stream ->
            stream.filter { Files.isRegularFile(it) && kind.matches(it.fileName.toString()) }
                .toList()
                .sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_READ = 8L * 1024 * 1024
    }
}
