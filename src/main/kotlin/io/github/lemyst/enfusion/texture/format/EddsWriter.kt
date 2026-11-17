package io.github.lemyst.enfusion.texture.format

import java.io.ByteArrayOutputStream

/**
 * Writes an uncompressed BGRA8 `.edds`, the format of most vanilla UI atlases: 13 of the 23
 * `.edds` under `P:\gui\imagesets` are 32-bit BGRA, 12 of them with a full mip chain. Most store
 * their larger mips LZ4-compressed; `dayz_gui.edds`, the main UI atlas, stores every mip raw
 * (`COPY`), as written here, and its header is the one copied field for field: a legacy DDS
 * pixel format with masks rather than a `DX10` extension.
 *
 * The output depends only on the pixels: no time stamp, no tool name, so packing the same sources
 * again gives the same bytes. Read back by [EddsReader], which the tests do.
 */
object EddsWriter {

    private const val DDSD_FLAGS = 0x1 or 0x2 or 0x4 or 0x8 or 0x1000 or 0x20000
    private const val DDPF_ALPHAPIXELS_RGB = 0x41
    private const val DDSCAPS_TEXTURE = 0x1000
    private const val DDSCAPS_COMPLEX_MIPMAP = 0x8 or 0x400000
    private const val ENF1 = 0x31464E45

    /**
     * [argb] ([width] × [height], row by row) as an `.edds`, with every mip down to 1 × 1 when
     * [mips], else only the full size.
     */
    fun bgra8(argb: IntArray, width: Int, height: Int, mips: Boolean): ByteArray {
        require(width > 0 && height > 0 && argb.size == width * height) { "pixels do not match ${width}x$height" }
        val levels = ArrayList<Triple<IntArray, Int, Int>>()
        levels += Triple(argb, width, height)
        if (mips) {
            while (levels.last().second > 1 || levels.last().third > 1) {
                val (pixels, w, h) = levels.last()
                levels += downsample(pixels, w, h)
            }
        }
        val out = ByteArrayOutputStream()
        out.write("DDS ".toByteArray(Charsets.US_ASCII))
        val header = IntArray(31)
        header[0] = 124
        header[1] = DDSD_FLAGS
        header[2] = height
        header[3] = width
        header[4] = width * 4
        header[6] = levels.size
        // `ENF1` in the reserved words, at offset 36: the 203 `.edds` under `P:\gui` and the 14 of a
        // mod all carry it. The engine may read it as the mark of this mip-table layout.
        header[8] = ENF1
        // Pixel format, at offset 76: size, flags, fourCC, bit count, then the R, G, B, A masks.
        header[18] = 32
        header[19] = DDPF_ALPHAPIXELS_RGB
        header[21] = 32
        header[22] = 0x00FF0000
        header[23] = 0x0000FF00
        header[24] = 0x000000FF
        header[25] = 0xFF000000.toInt()
        header[26] = if (levels.size > 1) DDSCAPS_TEXTURE or DDSCAPS_COMPLEX_MIPMAP else DDSCAPS_TEXTURE
        header.forEach { out.writeInt32(it) }
        // The mip table and the mips themselves run smallest first.
        val smallestFirst = levels.reversed()
        for ((_, w, h) in smallestFirst) {
            out.write("COPY".toByteArray(Charsets.US_ASCII))
            out.writeInt32(w * h * 4)
        }
        for ((pixels, _, _) in smallestFirst) {
            val bytes = ByteArray(pixels.size * 4)
            for (i in pixels.indices) {
                val p = pixels[i]
                bytes[4 * i] = (p and 0xFF).toByte()
                bytes[4 * i + 1] = (p ushr 8 and 0xFF).toByte()
                bytes[4 * i + 2] = (p ushr 16 and 0xFF).toByte()
                bytes[4 * i + 3] = (p ushr 24 and 0xFF).toByte()
            }
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /**
     * The next mip: each pixel the average of the 2 × 2 above it (a 1-pixel side stays 1). Colour is
     * averaged weighted by alpha, so that a transparent pixel's colour, usually black, does not
     * darken the edge of an icon in the smaller mips.
     */
    internal fun downsample(pixels: IntArray, width: Int, height: Int): Triple<IntArray, Int, Int> {
        val w = maxOf(1, width / 2)
        val h = maxOf(1, height / 2)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0
                for (dy in 0..1) {
                    val sy = minOf(height - 1, 2 * y + dy)
                    for (dx in 0..1) {
                        val sx = minOf(width - 1, 2 * x + dx)
                        val p = pixels[sy * width + sx]
                        val pa = (p ushr 24 and 0xFF).toLong()
                        a += pa
                        r += pa * (p ushr 16 and 0xFF)
                        g += pa * (p ushr 8 and 0xFF)
                        b += pa * (p and 0xFF)
                        n++
                    }
                }
                out[y * w + x] = if (a == 0L) {
                    0
                } else {
                    val alpha = ((a + n / 2) / n).toInt()
                    (alpha shl 24) or (((r + a / 2) / a).toInt() shl 16) or (((g + a / 2) / a).toInt() shl 8) or ((b + a / 2) / a).toInt()
                }
            }
        }
        return Triple(out, w, h)
    }

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write(value and 0xFF)
        write(value ushr 8 and 0xFF)
        write(value ushr 16 and 0xFF)
        write(value ushr 24 and 0xFF)
    }
}
