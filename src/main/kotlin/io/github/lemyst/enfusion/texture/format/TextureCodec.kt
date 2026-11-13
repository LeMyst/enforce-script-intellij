package io.github.lemyst.enfusion.texture.format

/**
 * How one mip's bytes are stored.
 *
 * Both decompressors are written here rather than taken from a library. LZ4 is small enough that
 * a dependency would cost more than it saves, and depending on the copy the IDE bundles would rely
 * on something that is not API. The Java LZO ports are GPL-2.0-only or unclearly licensed, and
 * this plugin must stay GPL-3.0-or-later.
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
    },

    /** `.paa` DXT mips. */
    LZO("LZO") {
        override fun decompress(src: ByteArray, offset: Int, size: Int, expected: Int): ByteArray =
            Lzo1x.decompress(src, offset, offset + size, expected)
    };

    abstract fun decompress(src: ByteArray, offset: Int, size: Int, expected: Int): ByteArray
}

/**
 * Neither LZ4 nor LZO can expand data by much more than 255 to 1, since each extra length byte
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

/**
 * LZO1X-1 decompression, as `.paa` uses for DXT mips: a port of the reference `lzo1x_d.ch` state
 * machine, with the bounds checks that the reference leaves to its `_safe` build added throughout.
 */
internal object Lzo1x {

    private const val LOOP = 0
    private const val FIRST_LITERAL_RUN = 1
    private const val MATCH = 2
    private const val MATCH_DONE = 3
    private const val MATCH_NEXT = 4

    fun decompress(src: ByteArray, start: Int, end: Int, expected: Int): ByteArray {
        checkRatio(end - start, expected, "LZO")
        val out = ByteArray(expected)
        var ip = start
        var op = 0

        fun next(): Int {
            if (ip >= end) corrupt("LZO")
            return src[ip++].toInt() and 0xFF
        }

        /** A run of zero bytes adds 255 each; the first non-zero byte ends it. */
        fun extended(): Int {
            var n = 0
            while (true) {
                if (ip >= end) corrupt("LZO")
                if (src[ip].toInt() != 0) break
                n += 255
                ip++
                if (n > expected) corrupt("LZO")
            }
            return n + next()
        }

        fun literals(count: Int) {
            if (count > end - ip || count > expected - op) corrupt("LZO")
            System.arraycopy(src, ip, out, op, count)
            ip += count
            op += count
        }

        fun match(from: Int, count: Int) {
            if (from < 0 || count > expected - op) corrupt("LZO")
            var m = from
            repeat(count) { out[op++] = out[m++] }
        }

        var t: Int
        var state: Int
        if (end <= start) corrupt("LZO")
        if ((src[ip].toInt() and 0xFF) > 17) {
            t = next() - 17
            if (t < 4) {
                state = MATCH_NEXT
            } else {
                literals(t)
                state = FIRST_LITERAL_RUN
            }
        } else {
            t = 0
            state = LOOP
        }

        while (true) {
            when (state) {
                LOOP -> {
                    t = next()
                    if (t >= 16) {
                        state = MATCH
                    } else {
                        if (t == 0) t = 15 + extended()
                        literals(t + 3)
                        state = FIRST_LITERAL_RUN
                    }
                }

                FIRST_LITERAL_RUN -> {
                    t = next()
                    if (t >= 16) {
                        state = MATCH
                    } else {
                        val from = op - (1 + 0x800) - (t shr 2) - (next() shl 2)
                        match(from, 3)
                        state = MATCH_DONE
                    }
                }

                MATCH -> {
                    val from: Int
                    val count: Int
                    when {
                        t >= 64 -> {
                            from = op - 1 - ((t shr 2) and 7) - (next() shl 3)
                            count = (t shr 5) + 1
                        }

                        t >= 32 -> {
                            t = t and 31
                            if (t == 0) t = 31 + extended()
                            val lo = next()
                            val hi = next()
                            from = op - 1 - ((lo shr 2) + (hi shl 6))
                            count = t + 2
                        }

                        t >= 16 -> {
                            var m = op - ((t and 8) shl 11)
                            t = t and 7
                            if (t == 0) t = 7 + extended()
                            val lo = next()
                            val hi = next()
                            m -= (lo shr 2) + (hi shl 6)
                            if (m == op) {
                                // The end-of-stream marker.
                                if (op != expected) {
                                    throw TextureFormatException("LZO mip decompressed to $op bytes, expected $expected")
                                }
                                return out
                            }
                            from = m - 0x4000
                            count = t + 2
                        }

                        else -> {
                            from = op - 1 - (t shr 2) - (next() shl 2)
                            count = 2
                        }
                    }
                    match(from, count)
                    state = MATCH_DONE
                }

                MATCH_DONE -> {
                    // The low two bits of the byte two back say how many literals follow the match.
                    t = src[ip - 2].toInt() and 3
                    state = if (t == 0) LOOP else MATCH_NEXT
                }

                MATCH_NEXT -> {
                    literals(t)
                    t = next()
                    state = MATCH
                }
            }
        }
    }
}

internal fun readInt32(src: ByteArray, at: Int): Int =
    (src[at].toInt() and 0xFF) or
        ((src[at + 1].toInt() and 0xFF) shl 8) or
        ((src[at + 2].toInt() and 0xFF) shl 16) or
        ((src[at + 3].toInt() and 0xFF) shl 24)

internal fun readUInt16(src: ByteArray, at: Int): Int =
    (src[at].toInt() and 0xFF) or ((src[at + 1].toInt() and 0xFF) shl 8)
