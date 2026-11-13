package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.pbo.format.Lzss
import java.io.ByteArrayOutputStream

/**
 * Builds synthetic PBOs for tests. Real ones cannot be committed: vanilla's are proprietary, and
 * mods' are their authors'.
 */
object PboBytes {

    class Entry(
        val name: String,
        val data: ByteArray,
        val compress: Boolean = false,
        /** Overrides the packing method, e.g. `"Encr"`. */
        val method: String? = null,
        /** Overrides the data size written in the header, to fake an obfuscated or truncated PBO. */
        val claimedSize: Long? = null,
    )

    fun pbo(entries: List<Entry>, properties: Map<String, String> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        if (properties.isNotEmpty()) {
            header(out, "", method("Vers"), 0, 0, 0)
            for ((k, v) in properties) {
                asciiz(out, k)
                asciiz(out, v)
            }
            asciiz(out, "")
        }
        val bodies = entries.map { if (it.compress) compress(it.data) else it.data }
        for ((entry, body) in entries.zip(bodies)) {
            val method = when {
                entry.method != null -> method(entry.method)
                entry.compress -> method("Cprs")
                else -> 0L
            }
            val original = if (entry.compress) entry.data.size.toLong() else 0L
            header(out, entry.name, method, original, 0x5F000000, entry.claimedSize ?: body.size.toLong())
        }
        header(out, "", 0, 0, 0, 0)
        bodies.forEach { out.write(it) }
        // The trailing SHA-1 block: a zero byte and 20 bytes the reader never checks.
        out.write(0)
        out.write(ByteArray(20))
        return out.toByteArray()
    }

    fun file(name: String, text: String, compress: Boolean = false) =
        Entry(name, text.toByteArray(Charsets.ISO_8859_1), compress)

    private fun method(tag: String): Long {
        // The tag is stored so that its bytes read back reversed: "Cprs" is the u32 0x43707273.
        var v = 0L
        for (c in tag) v = (v shl 8) or c.code.toLong()
        return v
    }

    private fun header(out: ByteArrayOutputStream, name: String, method: Long, original: Long, time: Long, size: Long) {
        asciiz(out, name)
        u32(out, method)
        u32(out, original)
        u32(out, 0)
        u32(out, time)
        u32(out, size)
    }

    private fun asciiz(out: ByteArrayOutputStream, s: String) {
        out.write(s.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
    }

    private fun u32(out: ByteArrayOutputStream, v: Long) {
        for (shift in 0 until 32 step 8) out.write(((v shr shift) and 0xFF).toInt())
    }

    /** A greedy LZSS encoder, enough to produce back-references the decoder must follow. */
    fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            var flags = 0
            val group = ByteArrayOutputStream()
            var bit = 0
            while (bit < 8 && i < data.size) {
                var bestLen = 0
                var bestDist = 0
                val start = maxOf(0, i - 4095)
                for (j in start until i) {
                    var len = 0
                    while (len < 18 && i + len < data.size && data[j + len] == data[i + len]) len++
                    if (len > bestLen) {
                        bestLen = len
                        bestDist = i - j
                    }
                }
                if (bestLen >= 3) {
                    group.write(bestDist and 0xFF)
                    group.write(((bestDist shr 4) and 0xF0) or (bestLen - 3))
                    i += bestLen
                } else {
                    flags = flags or (1 shl bit)
                    group.write(data[i].toInt())
                    i++
                }
                bit++
            }
            out.write(flags)
            out.write(group.toByteArray())
        }
        u32(out, Lzss.checksum(data).toLong() and 0xFFFFFFFFL)
        return out.toByteArray()
    }
}
