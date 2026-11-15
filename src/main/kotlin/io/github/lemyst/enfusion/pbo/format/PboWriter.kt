package io.github.lemyst.enfusion.pbo.format

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Writes a PBO the way Addon Builder's *pack only* does: every file stored as it is, nothing
 * binarised, a `prefix` property, and the SHA-1 trailer the game checks. The layout is the one
 * [PboArchive] reads: a `Vers` block of properties, one header entry per file, an empty terminator
 * entry, the files' bytes in header order, then a zero byte and the SHA-1 of everything before it.
 */
object PboWriter {

    private const val METHOD_VERSION = 0x56657273L // "Vers"

    /** A file to store: its bytes come from [source], its name in the PBO is [name] (`\`-separated). */
    data class File(val name: String, val source: Path)

    /**
     * Writes [files] under [prefix] to [target], through a temporary file next to it, so that a
     * game reading the folder never sees half a PBO.
     */
    fun write(target: Path, prefix: String, files: List<File>) {
        val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.newOutputStream(temp).use { raw ->
            val digest = MessageDigest.getInstance("SHA-1")
            val out = DigestOutputStream(BufferedOutputStream(raw, 1 shl 16), digest)
            writeEntryHeader(out, "", METHOD_VERSION, 0, 0)
            asciiz(out, "prefix")
            asciiz(out, prefix)
            out.write(0)
            for (file in files) {
                val time = Files.getLastModifiedTime(file.source).toMillis() / 1000
                writeEntryHeader(out, file.name, 0, time, Files.size(file.source))
            }
            writeEntryHeader(out, "", 0, 0, 0)
            for (file in files) Files.newInputStream(file.source).use { it.copyTo(out) }
            out.flush()
            val sha1 = digest.digest()
            raw.write(0)
            raw.write(sha1)
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeEntryHeader(out: OutputStream, name: String, method: Long, timestamp: Long, dataSize: Long) {
        asciiz(out, name)
        u32(out, method)
        u32(out, 0) // original size: 0 for a stored file
        u32(out, 0) // reserved
        u32(out, timestamp)
        u32(out, dataSize)
    }

    private fun asciiz(out: OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.write(0)
    }

    private fun u32(out: OutputStream, value: Long) {
        require(value in 0..0xFFFFFFFFL) { "$value does not fit a PBO header field" }
        for (shift in 0 until 32 step 8) out.write(((value shr shift) and 0xFF).toInt())
    }
}
