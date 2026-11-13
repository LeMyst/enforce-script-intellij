package io.github.lemyst.enfusion.pbo.format

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** A PBO that cannot be read: truncated, obfuscated, encrypted, or not a PBO at all. */
class PboFormatException(message: String) : IOException(message)

enum class PboPacking { STORED, COMPRESSED, ENCRYPTED, UNKNOWN }

/**
 * One header entry. [name] is the raw name with `\` turned into `/`, exactly as stored: it may be
 * unsafe to use as a path, which is what [PboPaths.safeRelativePath] decides.
 */
data class PboEntry(
    val name: String,
    val packing: PboPacking,
    /** Unpacked size. Stored entries often write 0 here, so read [size] instead. */
    val originalSize: Long,
    val timestamp: Long,
    /** Bytes the entry occupies in the file. */
    val dataSize: Long,
    /** Absolute position of the entry's data in the file. */
    val dataOffset: Long,
) {
    val size: Long get() = if (packing == PboPacking.COMPRESSED) originalSize else dataSize
}

/** Positional reads over the archive's bytes. A file in the plugin, a byte array in tests. */
interface PboSource : Closeable {
    val size: Long
    fun read(position: Long, length: Int): ByteArray
}

class ByteArrayPboSource(private val bytes: ByteArray) : PboSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || position + length > bytes.size) throw PboFormatException("read past the end of the PBO")
        return bytes.copyOfRange(position.toInt(), position.toInt() + length)
    }
    override fun close() {}
}

/**
 * `FileChannel` positional reads are safe from several threads at once, which the browser relies on:
 * it opens entries while the table is still on screen.
 */
class FilePboSource(path: Path) : PboSource {
    private val channel = FileChannel.open(path, StandardOpenOption.READ)
    override val size: Long = channel.size()
    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || position + length > size) throw PboFormatException("read past the end of the PBO")
        val buffer = ByteBuffer.allocate(length)
        var at = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, at)
            if (n < 0) throw PboFormatException("read past the end of the PBO")
            at += n
        }
        return buffer.array()
    }
    override fun close() = channel.close()
}

/**
 * A Bohemia PBO: a header table of entries, then each entry's bytes in header order.
 *
 * Only the header is read up front. That is the whole point of the design: a texture PBO can be
 * gigabytes, and the plugin needs a few hundred KB of scripts and configs out of a handful of them.
 * [read] then fetches one entry by seeking to it.
 *
 * Every size in the header is checked against the file length before it is trusted. PBOs arrive from
 * the Workshop, and obfuscators deliberately write headers with absurd sizes and junk names to break
 * unpackers.
 */
class PboArchive private constructor(
    private val source: PboSource,
    /** The product extension block: `prefix`, `product`, `version`, and whatever else a packer wrote. */
    val properties: Map<String, String>,
    val entries: List<PboEntry>,
) : Closeable {

    /** The virtual path the engine mounts the PBO's contents under, `/`-separated, without slashes at the ends. */
    val prefix: String? get() = properties["prefix"]?.replace('\\', '/')?.trim('/')?.takeIf { it.isNotEmpty() }

    val isEncrypted: Boolean get() = entries.any { it.packing == PboPacking.ENCRYPTED }

    fun find(name: String): PboEntry? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The entry's unpacked bytes. Refuses entries over [maxSize] rather than allocating them. */
    fun read(entry: PboEntry, maxSize: Long = Int.MAX_VALUE.toLong() - 16): ByteArray {
        if (entry.size > maxSize) throw PboFormatException("${entry.name} is ${entry.size} bytes, over the $maxSize limit")
        if (entry.dataSize > Int.MAX_VALUE - 16) throw PboFormatException("${entry.name} is too large to read")
        return when (entry.packing) {
            PboPacking.STORED -> source.read(entry.dataOffset, entry.dataSize.toInt())
            PboPacking.COMPRESSED -> Lzss.decompress(source.read(entry.dataOffset, entry.dataSize.toInt()), entry.originalSize.toInt())
            PboPacking.ENCRYPTED -> throw PboFormatException("${entry.name} is encrypted")
            PboPacking.UNKNOWN -> throw PboFormatException("${entry.name} uses an unknown packing method")
        }
    }

    override fun close() = source.close()

    companion object {
        // Packing methods are four ASCII bytes read as a little-endian u32.
        private const val METHOD_STORED = 0L
        private const val METHOD_COMPRESSED = 0x43707273L // "Cprs"
        private const val METHOD_VERSION = 0x56657273L    // "Vers", the product extension block
        private const val METHOD_ENCRYPTED = 0x456e6372L  // "Encr"

        // Far beyond any real PBO (vanilla's largest holds a few tens of thousands of files), small
        // enough that a hostile header cannot make the reader build a list until memory runs out.
        private const val MAX_ENTRIES = 500_000
        private const val MAX_NAME = 1024
        private const val MAX_PROPERTIES = 64

        fun open(path: Path): PboArchive {
            val source = FilePboSource(path)
            try {
                return open(source)
            } catch (e: Throwable) {
                source.close()
                throw e
            }
        }

        fun open(source: PboSource): PboArchive {
            val cursor = HeaderCursor(source)
            val properties = LinkedHashMap<String, String>()
            val raw = ArrayList<RawEntry>()
            var first = true
            while (true) {
                val name = cursor.asciiz(MAX_NAME)
                val method = cursor.u32()
                val originalSize = cursor.u32()
                cursor.u32() // reserved
                val timestamp = cursor.u32()
                val dataSize = cursor.u32()
                if (name.isEmpty()) {
                    if (method == METHOD_VERSION && first) {
                        readProperties(cursor, properties)
                        first = false
                        continue
                    }
                    // The terminator: an empty name. Its fields should all be zero, but packers disagree.
                    break
                }
                first = false
                if (raw.size >= MAX_ENTRIES) throw PboFormatException("more than $MAX_ENTRIES entries")
                raw += RawEntry(name.replace('\\', '/'), method, originalSize, timestamp, dataSize)
            }

            var offset = cursor.position
            val entries = ArrayList<PboEntry>(raw.size)
            for (r in raw) {
                if (offset + r.dataSize > source.size) {
                    throw PboFormatException("${r.name} claims ${r.dataSize} bytes past the end of the file (obfuscated or truncated PBO)")
                }
                val packing = when {
                    r.method == METHOD_STORED -> PboPacking.STORED
                    // A "compressed" entry whose sizes agree was stored after all: packers set the
                    // method and then skip compression when it would not shrink the file.
                    r.method == METHOD_COMPRESSED && (r.originalSize == 0L || r.originalSize == r.dataSize) -> PboPacking.STORED
                    r.method == METHOD_COMPRESSED -> PboPacking.COMPRESSED
                    r.method == METHOD_ENCRYPTED -> PboPacking.ENCRYPTED
                    else -> PboPacking.UNKNOWN
                }
                entries += PboEntry(r.name, packing, r.originalSize, r.timestamp, r.dataSize, offset)
                offset += r.dataSize
            }
            return PboArchive(source, properties, entries)
        }

        private fun readProperties(cursor: HeaderCursor, into: MutableMap<String, String>) {
            repeat(MAX_PROPERTIES) {
                val key = cursor.asciiz(MAX_NAME)
                if (key.isEmpty()) return
                val value = cursor.asciiz(MAX_NAME)
                into.putIfAbsent(key.lowercase(), value)
            }
            throw PboFormatException("more than $MAX_PROPERTIES header properties")
        }
    }

    private class RawEntry(val name: String, val method: Long, val originalSize: Long, val timestamp: Long, val dataSize: Long)

    /** Sequential reads over the header, in chunks, so no header byte costs a positional read of its own. */
    private class HeaderCursor(private val source: PboSource) {
        private var chunk = ByteArray(0)
        private var chunkStart = 0L
        var position = 0L
            private set

        fun byte(): Int {
            val index = position - chunkStart
            if (index < 0 || index >= chunk.size) refill()
            val b = chunk[(position - chunkStart).toInt()].toInt() and 0xFF
            position++
            return b
        }

        fun u32(): Long {
            var v = 0L
            for (shift in 0 until 32 step 8) v = v or (byte().toLong() shl shift)
            return v
        }

        fun asciiz(max: Int): String {
            val sb = StringBuilder()
            while (true) {
                val b = byte()
                if (b == 0) return sb.toString()
                if (sb.length >= max) throw PboFormatException("header string longer than $max bytes (obfuscated or not a PBO)")
                // Names are 8-bit ANSI in practice; Latin-1 keeps every byte without guessing a code page.
                sb.append(b.toChar())
            }
        }

        private fun refill() {
            if (position >= source.size) throw PboFormatException("header runs past the end of the file (truncated or not a PBO)")
            val length = minOf(CHUNK.toLong(), source.size - position).toInt()
            chunk = source.read(position, length)
            chunkStart = position
        }

        companion object {
            private const val CHUNK = 64 * 1024
        }
    }
}

object PboPaths {

    /**
     * [name] as a relative path safe to write under a directory, or null.
     *
     * The cache writes entries to disk by name, so a name that climbs out (`..\..\x`), is absolute,
     * names a drive, or carries characters Windows rejects would be a write outside the cache, the
     * zip-slip shape. Obfuscated PBOs put exactly such junk in their headers. Those entries are
     * skipped, not repaired.
     */
    fun safeRelativePath(name: String): String? {
        val path = name.replace('\\', '/')
        if (path.isEmpty() || path.startsWith('/')) return null
        val segments = path.split('/')
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == "..") return null
            if (segment.any { it < ' ' || it in "<>:\"|?*" }) return null
            // Windows silently strips trailing dots and spaces, so `a.` and `a` would collide.
            if (segment.endsWith('.') || segment.endsWith(' ')) return null
        }
        return segments.joinToString("/")
    }
}
