package io.github.lemyst.enfusion.texture.format

/**
 * How decoded mip bytes map to pixels.
 *
 * The decoders here cover what the game data actually uses. A survey of every `.edds` under
 * `P:\gui` and `P:\DZ` and several mods found uncompressed BGRA/BGRX, DXT1, DXT5, and BC7 in font
 * atlases only. Every `.paa` under `P:\DZ` but three is DXT1 or DXT5.
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
     * BC7: 16-byte blocks in one of eight modes, each splitting the block into up to three subsets
     * with their own endpoints. Follows the Direct3D 11 functional specification; the partition
     * and anchor tables below are the specification's.
     *
     * A block whose first byte is zero names no mode. The specification makes it transparent black,
     * which is what the GPU shows, so the viewer does too.
     */
    object Bc7 : PixelLayout() {

        override fun byteSize(width: Int, height: Int): Int =
            blocksAcross(width) * blocksAcross(height) * 16

        override fun toArgb(data: ByteArray, width: Int, height: Int): IntArray {
            val argb = IntArray(width * height)
            val texels = IntArray(16)
            val across = blocksAcross(width)
            val down = blocksAcross(height)
            var at = 0
            for (by in 0 until down) {
                for (bx in 0 until across) {
                    decodeBlock(data, at, texels)
                    for (py in 0 until 4) {
                        val y = by * 4 + py
                        if (y >= height) break
                        for (px in 0 until 4) {
                            val x = bx * 4 + px
                            if (x < width) argb[y * width + x] = texels[py * 4 + px]
                        }
                    }
                    at += 16
                }
            }
            return argb
        }

        private fun blocksAcross(pixels: Int): Int = maxOf(1, (pixels + 3) / 4)

        /** Decodes the block at [at] into 16 ARGB texels, row by row. */
        internal fun decodeBlock(data: ByteArray, at: Int, out: IntArray) {
            val bits = BlockBits(data, at)
            val first = data[at].toInt() and 0xFF
            if (first == 0) {
                out.fill(0)
                return
            }
            val mode = Integer.numberOfTrailingZeros(first)
            bits.skip(mode + 1)
            val info = MODES[mode]

            val partition = bits.read(info.partitionBits)
            val rotation = bits.read(info.rotationBits)
            val indexSelection = bits.read(info.indexSelectionBits)

            // Endpoints are stored channel by channel: every red, then every green, and so on.
            val endpoints = info.subsets * 2
            val channels = Array(4) { IntArray(endpoints) }
            for (c in 0 until 3) for (e in 0 until endpoints) channels[c][e] = bits.read(info.colorBits)
            for (e in 0 until endpoints) channels[3][e] = if (info.alphaBits > 0) bits.read(info.alphaBits) else 255

            var colorBits = info.colorBits
            var alphaBits = info.alphaBits
            if (info.endpointPBits || info.sharedPBits) {
                colorBits++
                if (alphaBits > 0) alphaBits++
                val pBits = if (info.endpointPBits) endpoints else info.subsets
                for (p in 0 until pBits) {
                    val bit = bits.read(1)
                    val covered = if (info.endpointPBits) p..p else (2 * p)..(2 * p + 1)
                    for (e in covered) {
                        for (c in 0 until 3) channels[c][e] = (channels[c][e] shl 1) or bit
                        if (info.alphaBits > 0) channels[3][e] = (channels[3][e] shl 1) or bit
                    }
                }
            }
            for (e in 0 until endpoints) {
                for (c in 0 until 3) channels[c][e] = expand(channels[c][e], colorBits)
                if (alphaBits > 0) channels[3][e] = expand(channels[3][e], alphaBits)
            }

            // Modes 4 and 5 carry a second index set; without one, alpha shares the colour index.
            val primaryAt = bits.position
            val secondaryAt = primaryAt + 16 * info.indexBits - info.subsets
            val primary = BlockBits(data, at, primaryAt)
            val secondary = BlockBits(data, at, secondaryAt)
            val primaryWeights = weights(info.indexBits)
            val secondaryWeights = weights(info.secondaryIndexBits)

            for (i in 0 until 16) {
                val subset = subsetOf(info.subsets, partition, i)
                val anchor = i == 0 || when (info.subsets) {
                    2 -> i == ANCHOR_2[partition]
                    3 -> i == ANCHOR_3A[partition] || i == ANCHOR_3B[partition]
                    else -> false
                }
                val index = primary.read(if (anchor) info.indexBits - 1 else info.indexBits)
                val colorWeight: Int
                val alphaWeight: Int
                if (info.secondaryIndexBits > 0) {
                    val second = secondary.read(if (i == 0) info.secondaryIndexBits - 1 else info.secondaryIndexBits)
                    if (indexSelection == 0) {
                        colorWeight = primaryWeights[index]
                        alphaWeight = secondaryWeights[second]
                    } else {
                        colorWeight = secondaryWeights[second]
                        alphaWeight = primaryWeights[index]
                    }
                } else {
                    colorWeight = primaryWeights[index]
                    alphaWeight = colorWeight
                }
                val e0 = subset * 2
                val e1 = e0 + 1
                var r = lerp(channels[0][e0], channels[0][e1], colorWeight)
                var g = lerp(channels[1][e0], channels[1][e1], colorWeight)
                var b = lerp(channels[2][e0], channels[2][e1], colorWeight)
                var a = lerp(channels[3][e0], channels[3][e1], alphaWeight)
                when (rotation) {
                    1 -> r = a.also { a = r }
                    2 -> g = a.also { a = g }
                    3 -> b = a.also { a = b }
                }
                out[i] = argb(a, r, g, b)
            }
        }

        private fun expand(value: Int, bits: Int): Int {
            val shifted = value shl (8 - bits)
            return (shifted or (shifted shr bits)) and 0xFF
        }

        private fun lerp(e0: Int, e1: Int, weight: Int): Int = ((64 - weight) * e0 + weight * e1 + 32) shr 6

        private fun weights(bits: Int): IntArray = when (bits) {
            2 -> WEIGHTS_2
            3 -> WEIGHTS_3
            else -> WEIGHTS_4
        }

        private fun subsetOf(subsets: Int, partition: Int, i: Int): Int = when (subsets) {
            2 -> (PARTITIONS_2[partition] shr i) and 1
            3 -> ((PARTITIONS_3[partition] ushr (2 * i)) and 3).toInt()
            else -> 0
        }

        /** A little-endian bit cursor over one 128-bit block. */
        private class BlockBits(private val data: ByteArray, private val at: Int, var position: Int = 0) {
            fun skip(count: Int) {
                position += count
            }

            fun read(count: Int): Int {
                var value = 0
                for (k in 0 until count) {
                    val bit = position + k
                    if (bit >= 128) break
                    value = value or (((data[at + (bit shr 3)].toInt() shr (bit and 7)) and 1) shl k)
                }
                position += count
                return value
            }
        }

        private class Mode(
            val subsets: Int,
            val partitionBits: Int,
            val rotationBits: Int,
            val indexSelectionBits: Int,
            val colorBits: Int,
            val alphaBits: Int,
            val endpointPBits: Boolean,
            val sharedPBits: Boolean,
            val indexBits: Int,
            val secondaryIndexBits: Int,
        )

        private val MODES = arrayOf(
            Mode(3, 4, 0, 0, 4, 0, true, false, 3, 0),
            Mode(2, 6, 0, 0, 6, 0, false, true, 3, 0),
            Mode(3, 6, 0, 0, 5, 0, false, false, 2, 0),
            Mode(2, 6, 0, 0, 7, 0, true, false, 2, 0),
            Mode(1, 0, 2, 1, 5, 6, false, false, 2, 3),
            Mode(1, 0, 2, 0, 7, 8, false, false, 2, 2),
            Mode(1, 0, 0, 0, 7, 7, true, false, 4, 0),
            Mode(2, 6, 0, 0, 5, 5, true, false, 2, 0),
        )

        private val WEIGHTS_2 = intArrayOf(0, 21, 43, 64)
        private val WEIGHTS_3 = intArrayOf(0, 9, 18, 27, 37, 46, 55, 64)
        private val WEIGHTS_4 = intArrayOf(0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64)

        /** Two-subset partitions, one bit per texel (bit `i` is texel `i`). */
        private val PARTITIONS_2 = intArrayOf(
            0xcccc, 0x8888, 0xeeee, 0xecc8, 0xc880, 0xfeec, 0xfec8, 0xec80, 0xc800, 0xffec,
            0xfe80, 0xe800, 0xffe8, 0xff00, 0xfff0, 0xf000, 0xf710, 0x008e, 0x7100, 0x08ce,
            0x008c, 0x7310, 0x3100, 0x8cce, 0x088c, 0x3110, 0x6666, 0x366c, 0x17e8, 0x0ff0,
            0x718e, 0x399c, 0xaaaa, 0xf0f0, 0x5a5a, 0x33cc, 0x3c3c, 0x55aa, 0x9696, 0xa55a,
            0x73ce, 0x13c8, 0x324c, 0x3bdc, 0x6996, 0xc33c, 0x9966, 0x0660, 0x0272, 0x04e4,
            0x4e40, 0x2720, 0xc936, 0x936c, 0x39c6, 0x639c, 0x9336, 0x9cc6, 0x817e, 0xe718,
            0xccf0, 0x0fcc, 0x7744, 0xee22,
        )

        /** Three-subset partitions, two bits per texel. */
        private val PARTITIONS_3 = longArrayOf(
            0xaa685050, 0x6a5a5040, 0x5a5a4200, 0x5450a0a8, 0xa5a50000, 0xa0a05050, 0x5555a0a0,
            0x5a5a5050, 0xaa550000, 0xaa555500, 0xaaaa5500, 0x90909090, 0x94949494, 0xa4a4a4a4,
            0xa9a59450, 0x2a0a4250, 0xa5945040, 0x0a425054, 0xa5a5a500, 0x55a0a0a0, 0xa8a85454,
            0x6a6a4040, 0xa4a45000, 0x1a1a0500, 0x0050a4a4, 0xaaa59090, 0x14696914, 0x69691400,
            0xa08585a0, 0xaa821414, 0x50a4a450, 0x6a5a0200, 0xa9a58000, 0x5090a0a8, 0xa8a09050,
            0x24242424, 0x00aa5500, 0x24924924, 0x24499224, 0x50a50a50, 0x500aa550, 0xaaaa4444,
            0x66660000, 0xa5a0a5a0, 0x50a050a0, 0x69286928, 0x44aaaa44, 0x66666600, 0xaa444444,
            0x54a854a8, 0x95809580, 0x96969600, 0xa85454a8, 0x80959580, 0xaa141414, 0x96960000,
            0xaaaa1414, 0xa05050a0, 0xa0a5a5a0, 0x96000000, 0x40804080, 0xa9a8a9a8, 0xaaaaaa44,
            0x2a4a5254,
        )

        /** The texel of subset 1 whose index drops its top bit, for each two-subset partition. */
        private val ANCHOR_2 = intArrayOf(
            15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15,
            15, 2, 8, 2, 2, 8, 8, 15, 2, 8, 2, 2, 8, 8, 2, 2,
            15, 15, 6, 8, 2, 8, 15, 15, 2, 8, 2, 2, 2, 15, 15, 6,
            6, 2, 6, 8, 15, 15, 2, 2, 15, 15, 15, 15, 15, 2, 2, 15,
        )

        /** The same for subset 1 of each three-subset partition... */
        private val ANCHOR_3A = intArrayOf(
            3, 3, 15, 15, 8, 3, 15, 15, 8, 8, 6, 6, 6, 5, 3, 3,
            3, 3, 8, 15, 3, 3, 6, 10, 5, 8, 8, 6, 8, 5, 15, 15,
            8, 15, 3, 5, 6, 10, 8, 15, 15, 3, 15, 5, 15, 15, 15, 15,
            3, 15, 5, 5, 5, 8, 5, 10, 5, 10, 8, 13, 15, 12, 3, 3,
        )

        /** ...and for its subset 2. */
        private val ANCHOR_3B = intArrayOf(
            15, 8, 8, 3, 15, 15, 3, 8, 15, 15, 15, 15, 15, 15, 15, 8,
            15, 8, 15, 3, 15, 8, 15, 8, 3, 15, 6, 10, 15, 15, 10, 8,
            15, 3, 15, 10, 10, 8, 9, 10, 6, 15, 8, 15, 3, 6, 6, 8,
            15, 3, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 3, 15, 15, 8,
        )
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
