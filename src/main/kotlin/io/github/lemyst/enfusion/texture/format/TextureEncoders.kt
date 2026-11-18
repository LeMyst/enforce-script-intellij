package io.github.lemyst.enfusion.texture.format

import java.io.ByteArrayOutputStream

/**
 * LZ4 as `.edds` stores a compressed mip, the reverse of [Lz4.decodeEddsMip]: the decompressed
 * size (`uint32`), then chunks, each a 4-byte header (the compressed size in 24 bits, `0x80` in the
 * top byte on the last chunk) and one LZ4 block.
 *
 * The chunk layout is vanilla's, measured over the 203 `.edds` under `P:\gui`: every chunk but the
 * last decompresses to exactly 64 KiB (9,056 of them), the last to the rest, and only the last
 * carries the flag. Each block here is compressed on its own, never reaching back into an earlier
 * chunk. Vanilla's may (the decoder keeps a 64 KiB window), but a block that does not is read the
 * same by any decoder.
 */
object Lz4Writer {

    const val CHUNK = 64 * 1024

    private const val MIN_MATCH = 4
    private const val HASH_BITS = 16
    private const val MAX_DISTANCE = 65535

    /** LZ4's end-of-block rules: the last 5 bytes are literals, and no match starts in the last 12. */
    private const val LAST_LITERALS = 5
    private const val MATCH_LIMIT = 12

    fun compressEddsMip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 2 + 16)
        writeInt32(out, raw.size)
        var start = 0
        do {
            val end = minOf(raw.size, start + CHUNK)
            val block = compressBlock(raw, start, end)
            val last = end == raw.size
            writeInt32(out, block.size or (if (last) 0x80 shl 24 else 0))
            out.write(block)
            start = end
        } while (start < raw.size)
        return out.toByteArray()
    }

    /** One LZ4 block of [src] from [start] to [end], greedy with a single hash table. */
    fun compressBlock(src: ByteArray, start: Int, end: Int): ByteArray {
        val out = ByteArrayOutputStream((end - start) / 2 + 16)
        val table = IntArray(1 shl HASH_BITS) { -1 }
        var anchor = start
        var ip = start
        val matchLimit = end - MATCH_LIMIT
        while (ip < matchLimit) {
            val hash = hash(src, ip)
            val candidate = table[hash]
            table[hash] = ip
            if (candidate >= start && ip - candidate <= MAX_DISTANCE && same4(src, candidate, ip)) {
                var length = MIN_MATCH
                val limit = end - LAST_LITERALS
                while (ip + length < limit && src[candidate + length] == src[ip + length]) length++
                sequence(out, src, anchor, ip - anchor, ip - candidate, length)
                ip += length
                anchor = ip
            } else {
                ip++
            }
        }
        // The rest as literals, with no match after them.
        val literals = end - anchor
        out.write(minOf(literals, 15) shl 4)
        if (literals >= 15) lengthBytes(out, literals - 15)
        out.write(src, anchor, literals)
        return out.toByteArray()
    }

    private fun sequence(out: ByteArrayOutputStream, src: ByteArray, from: Int, literals: Int, distance: Int, match: Int) {
        val extra = match - MIN_MATCH
        out.write((minOf(literals, 15) shl 4) or minOf(extra, 15))
        if (literals >= 15) lengthBytes(out, literals - 15)
        out.write(src, from, literals)
        out.write(distance and 0xFF)
        out.write(distance ushr 8 and 0xFF)
        if (extra >= 15) lengthBytes(out, extra - 15)
    }

    private fun lengthBytes(out: ByteArrayOutputStream, value: Int) {
        var rest = value
        while (rest >= 255) {
            out.write(255)
            rest -= 255
        }
        out.write(rest)
    }

    private fun hash(src: ByteArray, at: Int): Int = (read32(src, at) * -1640531535) ushr (32 - HASH_BITS)

    private fun same4(src: ByteArray, a: Int, b: Int): Boolean = read32(src, a) == read32(src, b)

    private fun read32(src: ByteArray, at: Int): Int =
        (src[at].toInt() and 0xFF) or ((src[at + 1].toInt() and 0xFF) shl 8) or
            ((src[at + 2].toInt() and 0xFF) shl 16) or ((src[at + 3].toInt() and 0xFF) shl 24)

    private fun writeInt32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write(value ushr 8 and 0xFF)
        out.write(value ushr 16 and 0xFF)
        out.write(value ushr 24 and 0xFF)
    }
}

/**
 * BC1 (DXT1) and BC3 (DXT5) block encoders, the formats vanilla's block-compressed UI textures
 * use. Endpoints are the inset bounding box of the block's colours, as stb_dxt's fast mode chooses
 * them, and every texel takes its nearest palette entry. Good for flat UI art, which is what an
 * imageset holds; not a substitute for a high-quality encoder on photographs.
 *
 * Read back by the plugin's own decoders in the tests.
 */
object BlockEncoder {

    /** 8 bytes per 4 × 4 block. A block with a texel under half alpha takes BC1's transparent mode. */
    fun bc1(argb: IntArray, width: Int, height: Int): ByteArray = encode(argb, width, height, 8) { block, out, at ->
        colourBlock(block, out, at, allowTransparent = true)
    }

    /** 16 bytes per 4 × 4 block: an alpha block, then a four-colour BC1 block. */
    fun bc3(argb: IntArray, width: Int, height: Int): ByteArray = encode(argb, width, height, 16) { block, out, at ->
        alphaBlock(block, out, at)
        colourBlock(block, out, at + 8, allowTransparent = false)
    }

    /** Bytes of one mip of [width] × [height] in a format with [blockBytes] per 4 × 4 block. */
    fun size(width: Int, height: Int, blockBytes: Int): Int = maxOf(1, (width + 3) / 4) * maxOf(1, (height + 3) / 4) * blockBytes

    private inline fun encode(argb: IntArray, width: Int, height: Int, blockBytes: Int, body: (IntArray, ByteArray, Int) -> Unit): ByteArray {
        val bw = maxOf(1, (width + 3) / 4)
        val bh = maxOf(1, (height + 3) / 4)
        val out = ByteArray(bw * bh * blockBytes)
        val block = IntArray(16)
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                // Texels past the edge repeat the last row or column, which keeps them out of the way.
                for (y in 0..3) for (x in 0..3) {
                    val sx = minOf(width - 1, bx * 4 + x)
                    val sy = minOf(height - 1, by * 4 + y)
                    block[y * 4 + x] = argb[sy * width + sx]
                }
                body(block, out, (by * bw + bx) * blockBytes)
            }
        }
        return out
    }

    private fun colourBlock(block: IntArray, out: ByteArray, at: Int, allowTransparent: Boolean) {
        val transparent = allowTransparent && block.any { (it ushr 24) < 128 }
        val opaque = if (transparent) block.filter { (it ushr 24) >= 128 } else block.toList()
        if (opaque.isEmpty()) {
            // Fully transparent: three-colour mode with every texel on index 3.
            putShort(out, at, 0)
            putShort(out, at + 2, 0xFFFF)
            for (i in 4..7) out[at + i] = 0xFF.toByte()
            return
        }
        var minR = 255; var minG = 255; var minB = 255
        var maxR = 0; var maxG = 0; var maxB = 0
        for (p in opaque) {
            val r = p ushr 16 and 0xFF; val g = p ushr 8 and 0xFF; val b = p and 0xFF
            if (r < minR) minR = r; if (g < minG) minG = g; if (b < minB) minB = b
            if (r > maxR) maxR = r; if (g > maxG) maxG = g; if (b > maxB) maxB = b
        }
        // Inset the box by 1/16 of its size, so the endpoints are not wasted on outliers.
        val insetR = (maxR - minR) shr 4; val insetG = (maxG - minG) shr 4; val insetB = (maxB - minB) shr 4
        var c0 = rgb565(maxR - insetR, maxG - insetG, maxB - insetB)
        var c1 = rgb565(minR + insetR, minG + insetG, minB + insetB)

        // Four-colour mode needs c0 > c1, three-colour mode c0 <= c1.
        if (transparent) {
            if (c0 > c1) c0 = c1.also { c1 = c0 }
        } else {
            if (c0 < c1) c0 = c1.also { c1 = c0 }
            if (c0 == c1) {
                putShort(out, at, c0)
                putShort(out, at + 2, c1)
                for (i in 4..7) out[at + i] = 0
                return
            }
        }
        val palette = palette(c0, c1, fourColours = !transparent)
        var bits = 0L
        for (i in 0 until 16) {
            val p = block[i]
            val index = if (transparent && (p ushr 24) < 128) 3 else nearest(p, palette, if (transparent) 3 else 4)
            bits = bits or (index.toLong() shl (2 * i))
        }
        putShort(out, at, c0)
        putShort(out, at + 2, c1)
        for (i in 0..3) out[at + 4 + i] = (bits ushr (8 * i) and 0xFF).toByte()
    }

    private fun alphaBlock(block: IntArray, out: ByteArray, at: Int) {
        var min = 255
        var max = 0
        for (p in block) {
            val a = p ushr 24
            if (a < min) min = a
            if (a > max) max = a
        }
        out[at] = max.toByte()
        out[at + 1] = min.toByte()
        if (max == min) {
            for (i in 2..7) out[at + i] = 0
            return
        }
        // a0 > a1: eight values, a0, a1 and six between them.
        val values = IntArray(8)
        values[0] = max
        values[1] = min
        for (i in 1..6) values[i + 1] = ((7 - i) * max + i * min + 3) / 7
        var bits = 0L
        for (i in 0 until 16) {
            val a = block[i] ushr 24
            var best = 0
            var bestError = Int.MAX_VALUE
            for (j in 0..7) {
                val error = kotlin.math.abs(values[j] - a)
                if (error < bestError) {
                    bestError = error
                    best = j
                }
            }
            bits = bits or (best.toLong() shl (3 * i))
        }
        for (i in 0..5) out[at + 2 + i] = (bits ushr (8 * i) and 0xFF).toByte()
    }

    private fun palette(c0: Int, c1: Int, fourColours: Boolean): Array<IntArray> {
        val a = expand(c0)
        val b = expand(c1)
        return if (fourColours) {
            arrayOf(a, b, mix(a, b, 2, 1, 3), mix(a, b, 1, 2, 3))
        } else {
            arrayOf(a, b, mix(a, b, 1, 1, 2))
        }
    }

    private fun nearest(p: Int, palette: Array<IntArray>, count: Int): Int {
        val r = p ushr 16 and 0xFF; val g = p ushr 8 and 0xFF; val b = p and 0xFF
        var best = 0
        var bestError = Int.MAX_VALUE
        for (i in 0 until count) {
            val c = palette[i]
            val dr = c[0] - r; val dg = c[1] - g; val db = c[2] - b
            val error = dr * dr + dg * dg + db * db
            if (error < bestError) {
                bestError = error
                best = i
            }
        }
        return best
    }

    private fun mix(a: IntArray, b: IntArray, wa: Int, wb: Int, total: Int): IntArray =
        IntArray(3) { (a[it] * wa + b[it] * wb) / total }

    private fun rgb565(r: Int, g: Int, b: Int): Int =
        ((r.coerceIn(0, 255) * 31 + 127) / 255 shl 11) or ((g.coerceIn(0, 255) * 63 + 127) / 255 shl 5) or ((b.coerceIn(0, 255) * 31 + 127) / 255)

    private fun expand(c: Int): IntArray {
        val r = c ushr 11 and 31; val g = c ushr 5 and 63; val b = c and 31
        return intArrayOf((r shl 3) or (r shr 2), (g shl 2) or (g shr 4), (b shl 3) or (b shr 2))
    }

    private fun putShort(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = (value ushr 8 and 0xFF).toByte()
    }
}
