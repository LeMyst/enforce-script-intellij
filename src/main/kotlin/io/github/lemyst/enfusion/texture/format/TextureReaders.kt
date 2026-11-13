package io.github.lemyst.enfusion.texture.format

/** Enforces the size limits every reader shares, so that one corrupt header cannot ask for gigabytes. */
private fun checkDimensions(width: Int, height: Int) {
    if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) {
        throw TextureFormatException("invalid texture size ${width}x$height")
    }
}

private const val MAX_SIDE = 16384

/**
 * `.edds`, the Enfusion texture: a standard DDS header followed by a mip table instead of raw
 * mip data.
 *
 * After the 128-byte header (and the 20-byte `DX10` extension when present) comes one 8-byte entry
 * per mip: a tag (`COPY` for raw, `LZ4 ` for compressed) and a byte count. The mip data follows in
 * the same order, which is **smallest mip first**, the reverse of plain DDS. Checked against every
 * `.edds` under `P:\gui` and several mods.
 */
internal object EddsReader {

    private const val HEADER_END = 128
    private const val DX10_SIZE = 20

    private const val DDPF_ALPHAPIXELS = 0x1
    private const val DDPF_FOURCC = 0x4
    private const val DDPF_RGB = 0x40
    private const val DDPF_LUMINANCE = 0x20000
    private const val DDSCAPS2_CUBEMAP = 0x200

    fun isEdds(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'D'.code.toByte() && bytes[1] == 'D'.code.toByte() &&
            bytes[2] == 'S'.code.toByte() && bytes[3] == ' '.code.toByte()

    fun read(bytes: ByteArray): Texture {
        if (bytes.size < HEADER_END) throw TextureFormatException("truncated EDDS header")
        val height = readInt32(bytes, 12)
        val width = readInt32(bytes, 16)
        val mipCount = maxOf(1, readInt32(bytes, 28))
        val pfFlags = readInt32(bytes, 80)
        val fourCC = fourCC(bytes, 84)
        val bitCount = readInt32(bytes, 88)
        val masks = LongArray(4) { readInt32(bytes, 92 + 4 * it).toLong() and 0xFFFFFFFFL }
        val caps2 = readInt32(bytes, 112)
        checkDimensions(width, height)
        if (mipCount > 32) throw TextureFormatException("invalid mip count $mipCount")

        var tableStart = HEADER_END
        val formatName: String
        val layout: PixelLayout?
        when {
            pfFlags and DDPF_FOURCC != 0 && fourCC == "DX10" -> {
                if (bytes.size < HEADER_END + DX10_SIZE) throw TextureFormatException("truncated EDDS header")
                tableStart += DX10_SIZE
                val dxgi = readInt32(bytes, HEADER_END)
                val known = DXGI_FORMATS[dxgi]
                formatName = known?.first ?: "DXGI format $dxgi"
                layout = known?.second
            }

            pfFlags and DDPF_FOURCC != 0 -> {
                formatName = FOURCC_NAMES[fourCC] ?: fourCC.trim().ifEmpty { "unknown" }
                layout = when (fourCC) {
                    "DXT1" -> PixelLayout.Block(PixelLayout.Block.Kind.BC1)
                    "DXT2", "DXT3" -> PixelLayout.Block(PixelLayout.Block.Kind.BC2)
                    "DXT4", "DXT5" -> PixelLayout.Block(PixelLayout.Block.Kind.BC3)
                    else -> null
                }
            }

            pfFlags and (DDPF_RGB or DDPF_LUMINANCE) != 0 && bitCount in intArrayOf(8, 16, 24, 32) -> {
                val alpha = if (pfFlags and DDPF_ALPHAPIXELS != 0) masks[3] else 0L
                val luminance = pfFlags and DDPF_LUMINANCE != 0
                formatName = maskedName(bitCount, masks, alpha, luminance)
                layout = PixelLayout.Masked(bitCount / 8, masks[0], masks[1], masks[2], alpha, luminance)
            }

            else -> {
                formatName = "pixel format 0x${Integer.toHexString(pfFlags)}"
                layout = null
            }
        }
        if (caps2 and DDSCAPS2_CUBEMAP != 0) throw UnsupportedTextureException("Cube map")

        // The table runs smallest first; level 0 is the largest.
        val tableEnd = tableStart.toLong() + 8L * mipCount
        if (tableEnd > bytes.size) throw TextureFormatException("truncated EDDS mip table")
        var dataAt = tableEnd
        val stored = ArrayList<TextureMip>(mipCount)
        for (i in 0 until mipCount) {
            val entry = tableStart + 8 * i
            val codec = when (fourCC(bytes, entry)) {
                "COPY" -> TextureCodec.RAW
                "LZ4 " -> TextureCodec.LZ4
                else -> throw TextureFormatException("unknown EDDS mip block '${fourCC(bytes, entry).trim()}'")
            }
            val size = readInt32(bytes, entry + 4).toLong() and 0xFFFFFFFFL
            if (dataAt + size > bytes.size) throw TextureFormatException("truncated EDDS mip data")
            val level = mipCount - 1 - i
            stored += TextureMip(
                maxOf(1, width shr level),
                maxOf(1, height shr level),
                dataAt.toInt(),
                size.toInt(),
                codec,
            )
            dataAt += size
        }
        return Texture("EDDS", formatName, layout, stored.reversed(), bytes)
    }

    private fun fourCC(bytes: ByteArray, at: Int): String =
        String(CharArray(4) { (bytes[at + it].toInt() and 0xFF).toChar() })

    private fun maskedName(bitCount: Int, masks: LongArray, alpha: Long, luminance: Boolean): String = when {
        luminance -> if (alpha != 0L) "L${bitCount / 2}A${bitCount / 2}" else "L$bitCount"
        bitCount == 32 && masks[0] == 0xFF0000L -> if (alpha != 0L) "BGRA8" else "BGRX8"
        bitCount == 32 && masks[0] == 0xFFL -> if (alpha != 0L) "RGBA8" else "RGBX8"
        else -> "RGB $bitCount-bit"
    }

    private val FOURCC_NAMES = mapOf(
        "DXT1" to "DXT1", "DXT2" to "DXT2", "DXT3" to "DXT3", "DXT4" to "DXT4", "DXT5" to "DXT5",
        "ATI1" to "BC4", "BC4U" to "BC4", "ATI2" to "BC5", "BC5U" to "BC5",
    )

    /**
     * The DXGI formats worth naming. BC7 is the one the game data holds, in 17 font atlases under
     * `P:\gui\fonts`; it stays undecoded until a texture people actually look at needs it.
     */
    private val DXGI_FORMATS: Map<Int, Pair<String, PixelLayout?>> = mapOf(
        28 to ("RGBA8" to PixelLayout.Masked(4, 0xFFL, 0xFF00L, 0xFF0000L, 0xFF000000L, false)),
        29 to ("RGBA8 sRGB" to PixelLayout.Masked(4, 0xFFL, 0xFF00L, 0xFF0000L, 0xFF000000L, false)),
        71 to ("BC1" to PixelLayout.Block(PixelLayout.Block.Kind.BC1)),
        72 to ("BC1 sRGB" to PixelLayout.Block(PixelLayout.Block.Kind.BC1)),
        74 to ("BC2" to PixelLayout.Block(PixelLayout.Block.Kind.BC2)),
        75 to ("BC2 sRGB" to PixelLayout.Block(PixelLayout.Block.Kind.BC2)),
        77 to ("BC3" to PixelLayout.Block(PixelLayout.Block.Kind.BC3)),
        78 to ("BC3 sRGB" to PixelLayout.Block(PixelLayout.Block.Kind.BC3)),
        80 to ("BC4" to null),
        83 to ("BC5" to null),
        87 to ("BGRA8" to PixelLayout.Masked(4, 0xFF0000L, 0xFF00L, 0xFFL, 0xFF000000L, false)),
        88 to ("BGRX8" to PixelLayout.Masked(4, 0xFF0000L, 0xFF00L, 0xFFL, 0L, false)),
        91 to ("BGRA8 sRGB" to PixelLayout.Masked(4, 0xFF0000L, 0xFF00L, 0xFFL, 0xFF000000L, false)),
        95 to ("BC6H" to null),
        96 to ("BC6H" to null),
        98 to ("BC7" to null),
        99 to ("BC7 sRGB" to null),
    )
}

/**
 * `.paa`, the Real Virtuality texture that DayZ inherited from Arma.
 *
 * A 2-byte format code, a run of `GGAT` tags (average colour, maximum colour, flags, mip offsets,
 * swizzle), a palette count, then the mips largest first. Each mip is a `uint16` width and height
 * and a 24-bit size; the top bit of the width marks LZO-compressed data. A zero width and height
 * end the list.
 *
 * Only the DXT formats are decoded. Of the 40,055 `.paa` files under `P:\DZ`, 40,052 are DXT1 or
 * DXT5; the other three are an ARGB4444 detail map and an ARGB1555 noise map in `data/data`. Those
 * codes are still named, so that a file using one says what it is rather than "corrupt".
 */
internal object PaaReader {

    private const val LZO_FLAG = 0x8000

    fun read(bytes: ByteArray): Texture {
        if (bytes.size < 2) throw TextureFormatException("not a texture")
        val code = readUInt16(bytes, 0)
        val (formatName, layout) = FORMATS[code] ?: throw TextureFormatException(
            "not an EDDS or PAA texture (starts with 0x${Integer.toHexString(code)})"
        )

        var at = 2
        while (at + 12 <= bytes.size && tag(bytes, at) == "GGAT") {
            val length = readInt32(bytes, at + 8).toLong() and 0xFFFFFFFFL
            if (at + 12L + length > bytes.size) throw TextureFormatException("truncated PAA tag")
            at += 12 + length.toInt()
        }
        if (at + 2 > bytes.size) throw TextureFormatException("truncated PAA header")
        val paletteSize = readUInt16(bytes, at)
        at += 2 + 3 * paletteSize

        val mips = ArrayList<TextureMip>()
        while (true) {
            if (at + 4 > bytes.size) throw TextureFormatException("truncated PAA mip list")
            val rawWidth = readUInt16(bytes, at)
            val height = readUInt16(bytes, at + 2)
            at += 4
            if (rawWidth == 0 && height == 0) break
            if (at + 3 > bytes.size) throw TextureFormatException("truncated PAA mip list")
            val size = (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16)
            at += 3
            if (at.toLong() + size > bytes.size) throw TextureFormatException("truncated PAA mip data")
            val compressed = rawWidth and LZO_FLAG != 0
            val width = rawWidth and LZO_FLAG.inv()
            checkDimensions(width, height)
            mips += TextureMip(width, height, at, size, if (compressed) TextureCodec.LZO else TextureCodec.RAW)
            at += size
            if (mips.size > 32) throw TextureFormatException("too many PAA mips")
        }
        if (mips.isEmpty()) throw TextureFormatException("PAA holds no mips")
        return Texture("PAA", formatName, layout, mips, bytes)
    }

    private fun tag(bytes: ByteArray, at: Int): String =
        String(CharArray(4) { (bytes[at + it].toInt() and 0xFF).toChar() })

    private val FORMATS: Map<Int, Pair<String, PixelLayout?>> = mapOf(
        0xFF01 to ("DXT1" to PixelLayout.Block(PixelLayout.Block.Kind.BC1)),
        0xFF02 to ("DXT2" to PixelLayout.Block(PixelLayout.Block.Kind.BC2)),
        0xFF03 to ("DXT3" to PixelLayout.Block(PixelLayout.Block.Kind.BC2)),
        0xFF04 to ("DXT4" to PixelLayout.Block(PixelLayout.Block.Kind.BC3)),
        0xFF05 to ("DXT5" to PixelLayout.Block(PixelLayout.Block.Kind.BC3)),
        0x4444 to ("ARGB4444" to null),
        0x1555 to ("ARGB1555" to null),
        0x8888 to ("ARGB8888" to null),
        0x8080 to ("AI88" to null),
    )
}
