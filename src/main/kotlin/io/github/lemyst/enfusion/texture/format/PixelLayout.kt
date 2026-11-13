package io.github.lemyst.enfusion.texture.format

/**
 * How decoded mip bytes map to pixels.
 *
 * The decoders here cover what the game data actually uses. A survey of every `.edds` under
 * `P:\gui` and `P:\DZ` and several mods found uncompressed BGRA/BGRX, DXT1, DXT5, and BC7 in font
 * atlases only.
 */
internal sealed class PixelLayout {

    abstract fun byteSize(width: Int, height: Int): Int

    abstract fun toArgb(data: ByteArray, width: Int, height: Int): IntArray

    /** BC1 to BC3, the 4x4-texel block formats DirectX calls DXT1, DXT3 and DXT5. */
    class Block(private val kind: Kind) : PixelLayout() {

        enum class Kind(val blockBytes: Int) { BC1(8), BC2(16), BC3(16) }

        override fun byteSize(width: Int, height: Int): Int =
            blocksAcross(width) * blocksAcross(height) * kind.blockBytes

        override fun toArgb(data: ByteArray, width: Int, height: Int): IntArray {
            val argb = IntArray(width * height)
            val across = blocksAcross(width)
            val down = blocksAcross(height)
            val colors = IntArray(4)
            val alphas = IntArray(16)
            var at = 0
            for (by in 0 until down) {
                for (bx in 0 until across) {
                    when (kind) {
                        Kind.BC1 -> {
                            colorBlock(data, at, colors, allowTransparent = true)
                            writeColors(argb, width, height, bx, by, data, at, colors, null)
                        }

                        Kind.BC2 -> {
                            explicitAlpha(data, at, alphas)
                            colorBlock(data, at + 8, colors, allowTransparent = false)
                            writeColors(argb, width, height, bx, by, data, at + 8, colors, alphas)
                        }

                        Kind.BC3 -> {
                            interpolatedAlpha(data, at, alphas)
                            colorBlock(data, at + 8, colors, allowTransparent = false)
                            writeColors(argb, width, height, bx, by, data, at + 8, colors, alphas)
                        }
                    }
                    at += kind.blockBytes
                }
            }
            return argb
        }

        private fun blocksAcross(pixels: Int): Int = maxOf(1, (pixels + 3) / 4)

        /**
         * The four colours of a block. BC1 alone switches to three colours plus transparent black
         * when the endpoints are in ascending order; BC2 and BC3 always interpolate four.
         */
        private fun colorBlock(data: ByteArray, at: Int, colors: IntArray, allowTransparent: Boolean) {
            val c0 = readUInt16(data, at)
            val c1 = readUInt16(data, at + 2)
            val r0 = expand5(c0 shr 11)
            val g0 = expand6(c0 shr 5)
            val b0 = expand5(c0)
            val r1 = expand5(c1 shr 11)
            val g1 = expand6(c1 shr 5)
            val b1 = expand5(c1)
            colors[0] = argb(255, r0, g0, b0)
            colors[1] = argb(255, r1, g1, b1)
            if (c0 > c1 || !allowTransparent) {
                colors[2] = argb(255, (2 * r0 + r1) / 3, (2 * g0 + g1) / 3, (2 * b0 + b1) / 3)
                colors[3] = argb(255, (r0 + 2 * r1) / 3, (g0 + 2 * g1) / 3, (b0 + 2 * b1) / 3)
            } else {
                colors[2] = argb(255, (r0 + r1) / 2, (g0 + g1) / 2, (b0 + b1) / 2)
                colors[3] = 0
            }
        }

        private fun explicitAlpha(data: ByteArray, at: Int, alphas: IntArray) {
            for (i in 0 until 16) {
                val nibble = (data[at + i / 2].toInt() shr ((i and 1) * 4)) and 0xF
                alphas[i] = nibble * 17
            }
        }

        private fun interpolatedAlpha(data: ByteArray, at: Int, alphas: IntArray) {
            val a0 = data[at].toInt() and 0xFF
            val a1 = data[at + 1].toInt() and 0xFF
            val palette = IntArray(8)
            palette[0] = a0
            palette[1] = a1
            if (a0 > a1) {
                for (i in 1..6) palette[i + 1] = ((7 - i) * a0 + i * a1) / 7
            } else {
                for (i in 1..4) palette[i + 1] = ((5 - i) * a0 + i * a1) / 5
                palette[6] = 0
                palette[7] = 255
            }
            // 48 bits of 3-bit indices, little-endian.
            var bits = 0L
            for (i in 0 until 6) bits = bits or ((data[at + 2 + i].toLong() and 0xFF) shl (8 * i))
            for (i in 0 until 16) alphas[i] = palette[((bits shr (3 * i)) and 7).toInt()]
        }

        private fun writeColors(
            argb: IntArray, width: Int, height: Int, bx: Int, by: Int,
            data: ByteArray, at: Int, colors: IntArray, alphas: IntArray?,
        ) {
            val indices = readInt32(data, at + 4)
            for (py in 0 until 4) {
                val y = by * 4 + py
                if (y >= height) break
                for (px in 0 until 4) {
                    val x = bx * 4 + px
                    if (x >= width) continue
                    val i = py * 4 + px
                    var color = colors[(indices ushr (2 * i)) and 3]
                    if (alphas != null) color = (color and 0xFFFFFF) or (alphas[i] shl 24)
                    argb[y * width + x] = color
                }
            }
        }

        private fun expand5(v: Int): Int = (v and 31).let { (it shl 3) or (it shr 2) }
        private fun expand6(v: Int): Int = (v and 63).let { (it shl 2) or (it shr 4) }
    }

    /**
     * An uncompressed format described by DDS channel masks: covers BGRA8, BGRX8, RGBA8 and the
     * 16-bit and luminance variants with one rule rather than one case per format.
     */
    class Masked(
        private val bytesPerPixel: Int,
        private val red: Long,
        private val green: Long,
        private val blue: Long,
        private val alpha: Long,
        private val luminance: Boolean,
    ) : PixelLayout() {

        override fun byteSize(width: Int, height: Int): Int = width * height * bytesPerPixel

        override fun toArgb(data: ByteArray, width: Int, height: Int): IntArray {
            val argb = IntArray(width * height)
            var at = 0
            for (i in argb.indices) {
                var value = 0L
                for (b in 0 until bytesPerPixel) value = value or ((data[at + b].toLong() and 0xFF) shl (8 * b))
                at += bytesPerPixel
                val a = if (alpha != 0L) channel(value, alpha) else 255
                argb[i] = if (luminance) {
                    val l = channel(value, red)
                    argb(a, l, l, l)
                } else {
                    argb(a, channel(value, red), channel(value, green), channel(value, blue))
                }
            }
            return argb
        }

        /** The masked bits, scaled to 0..255 whatever the mask's width. */
        private fun channel(value: Long, mask: Long): Int {
            if (mask == 0L) return 0
            val shift = java.lang.Long.numberOfTrailingZeros(mask)
            val max = mask ushr shift
            val raw = (value and mask) ushr shift
            return if (max == 255L) raw.toInt() else ((raw * 255 + max / 2) / max).toInt()
        }
    }
}

private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
