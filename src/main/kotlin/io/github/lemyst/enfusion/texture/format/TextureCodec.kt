package io.github.lemyst.enfusion.texture.format

/**
 * How one mip's bytes are stored.
 *
 * LZ4 is written here rather than taken from a library: it is small enough that a dependency would
 * cost more than it saves, and depending on the copy the IDE bundles would rely on something that
 * is not API.
 *
 * Every decompressor writes into a buffer of exactly the size the header implies, and treats any
 * attempt to read or write past it as a corrupt file. A texture is untrusted input: it can come from
 * any mod a user unpacks.
 */
internal enum class TextureCodec(val label: String) {

    RAW("raw") {
        override fun decompress(src: ByteArray, offset: Int, size: Int, expected: Int): ByteArray {
            if (size != expected) throw TextureFormatException("mip holds $size bytes, expected $expected")
            return src.copyOfRange(offset, offset + size)
        }
    },

    /** `.edds`: a sequence of LZ4 blocks sharing one sliding window. */
    LZ4("LZ4") {
        override fun decompress(src: ByteArray, offset: Int, size: Int, expected: Int): ByteArray =
            Lz4.decodeEddsMip(src, offset, offset + size, expected)
    };

    abstract fun decompress(src: ByteArray, offset: Int, size: Int, expected: Int): ByteArray
}

/**
 * LZ4 cannot expand data by much more than 255 to 1, since each extra length byte
 * adds at most 255 bytes of output. A header claiming more is corrupt, and checking that before the
 * output buffer is allocated keeps a forged size from costing a large allocation.
 */
private fun checkRatio(size: Int, expected: Int, what: String) {
    if (expected.toLong() > size.toLong() * 300 + 64) corrupt(what)
}

private fun corrupt(what: String): Nothing = throw TextureFormatException("corrupt $what data")

/**
 * LZ4 as `.edds` stores it, checked against every mod `.edds` in the test corpus.
 *
 * A compressed mip starts with its decompressed size (`uint32`), then holds chunks. Each chunk has
 * a 4-byte header, a 24-bit compressed size with a flag byte above it (`0x80` on the last chunk),
 * followed by one LZ4 block. The blocks decompress back to back into one buffer, and a match may
 * reach into the output of earlier chunks: the encoder streams with a 64 KiB dictionary. Decoding
 * every chunk into the same contiguous buffer gives exactly that, with no dictionary to manage.
 */
internal object Lz4 {

    fun decodeEddsMip(src: ByteArray, start: Int, end: Int, expected: Int): ByteArray {
        if (end - start < 4) corrupt("LZ4")
        checkRatio(end - start, expected, "LZ4")
        val declared = readInt32(src, start)
        if (declared != expected) throw TextureFormatException("LZ4 mip declares $declared bytes, expected $expected")
        val out = ByteArray(expected)
        var ip = start + 4
        var op = 0
        while (ip < end) {
            if (end - ip < 4) corrupt("LZ4")
            val chunkSize = readInt32(src, ip) and 0xFFFFFF
            ip += 4
            if (chunkSize > end - ip) corrupt("LZ4")
            op = decodeBlock(src, ip, ip + chunkSize, out, op)
            ip += chunkSize
        }
        if (op != expected) throw TextureFormatException("LZ4 mip decompressed to $op bytes, expected $expected")
        return out
    }

    /** Decodes one block at [outStart], allowing matches back into anything already in [out]. */
    fun decodeBlock(src: ByteArray, start: Int, end: Int, out: ByteArray, outStart: Int): Int {
        var ip = start
        var op = outStart
        while (ip < end) {
            val token = src[ip++].toInt() and 0xFF

            var literals = token ushr 4
            if (literals == 15) {
                var b: Int
                do {
                    if (ip >= end) corrupt("LZ4")
                    b = src[ip++].toInt() and 0xFF
                    literals += b
                } while (b == 255 && literals <= out.size)
            }
            if (literals > end - ip || literals > out.size - op) corrupt("LZ4")
            System.arraycopy(src, ip, out, op, literals)
            ip += literals
            op += literals

            // The last sequence of a block is literals only.
            if (ip >= end) break

            if (end - ip < 2) corrupt("LZ4")
            val distance = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
            ip += 2
            var length = token and 15
            if (length == 15) {
                var b: Int
                do {
                    if (ip >= end) corrupt("LZ4")
                    b = src[ip++].toInt() and 0xFF
                    length += b
                } while (b == 255 && length <= out.size)
            }
            length += 4
            if (distance == 0 || distance > op || length > out.size - op) corrupt("LZ4")
            // Byte by byte: a match may overlap its own output, which is how LZ4 encodes runs.
            var from = op - distance
            repeat(length) { out[op++] = out[from++] }
        }
        return op
    }
}

internal fun readInt32(src: ByteArray, at: Int): Int =
    (src[at].toInt() and 0xFF) or
        ((src[at + 1].toInt() and 0xFF) shl 8) or
        ((src[at + 2].toInt() and 0xFF) shl 16) or
        ((src[at + 3].toInt() and 0xFF) shl 24)

internal fun readUInt16(src: ByteArray, at: Int): Int =
    (src[at].toInt() and 0xFF) or ((src[at + 1].toInt() and 0xFF) shl 8)
