package io.github.lemyst.enfusion.texture.format

import java.awt.image.BufferedImage

/**
 * A texture read from an `.edds` file: its header and mip table, with every mip still encoded.
 *
 * This package uses no IntelliJ API on purpose. The format is documented only by community tools,
 * so the decoder is the part most likely to need fixing against a file nobody has seen yet. Keeping
 * it self-contained is what lets [TextureReader] be tested on plain bytes, and lets `.paa` (#14)
 * join it later without touching the IDE side.
 *
 * Nothing is decompressed until [decode] asks for one level. A 4096² texture has a 64 MB top mip,
 * and a 192-pixel hover preview has no use for it.
 */
class Texture internal constructor(
    /** `EDDS`; a field so that `.paa` can join without a model change. */
    val container: String,
    /** What users see: `DXT1`, `DXT5`, `BGRA8`, `BC7`, ... */
    val formatName: String,
    /** Null when the format is recognised but not decoded here, BC7 being the real case. */
    internal val layout: PixelLayout?,
    /** Largest first, whatever order the file stores them in. */
    val mips: List<TextureMip>,
    private val bytes: ByteArray,
) {
    val width: Int get() = mips[0].width
    val height: Int get() = mips[0].height

    val isSupported: Boolean get() = layout != null

    /** `LZ4`, `raw`, `raw + LZ4`, ... in the order the mips use them. */
    val compression: String
        get() = mips.map { it.codec.label }.distinct().joinToString(" + ")

    /**
     * The smallest mip that still covers [side] pixels in both directions, so a downscaled preview
     * keeps its detail. When even the top mip is smaller, that top mip.
     */
    fun levelCovering(side: Int): Int {
        for (level in mips.indices.reversed()) {
            val mip = mips[level]
            if (mip.width >= side && mip.height >= side) return level
        }
        return 0
    }

    /** The largest mip holding at most [maxPixels] pixels: what a viewer can afford to show. */
    fun levelWithin(maxPixels: Long): Int =
        mips.indices.firstOrNull { mips[it].width.toLong() * mips[it].height <= maxPixels } ?: mips.lastIndex

    /**
     * Decompresses and decodes one mip into a [BufferedImage.TYPE_INT_ARGB] image.
     *
     * @throws UnsupportedTextureException when the pixel format is not decoded here.
     * @throws TextureFormatException when the data contradicts the header.
     */
    fun decode(level: Int = 0): BufferedImage {
        val pixels = layout ?: throw UnsupportedTextureException(formatName)
        val mip = mips.getOrNull(level) ?: throw TextureFormatException("no mip level $level")
        if (mip.width.toLong() * mip.height > MAX_DECODED_PIXELS) {
            throw TextureFormatException("mip $level is ${mip.width}x${mip.height}, too large to decode")
        }
        val expected = pixels.byteSize(mip.width, mip.height)
        val data = mip.codec.decompress(bytes, mip.offset, mip.size, expected)
        val argb = pixels.toArgb(data, mip.width, mip.height)
        val image = BufferedImage(mip.width, mip.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, mip.width, mip.height, argb, 0, mip.width)
        return image
    }

    companion object {
        /** 8192², the largest texture the game itself uses with any regularity. */
        const val MAX_DECODED_PIXELS: Long = 8192L * 8192L
    }
}

/** One mip level: its size, and where its encoded bytes sit in the file. */
class TextureMip internal constructor(
    val width: Int,
    val height: Int,
    internal val offset: Int,
    internal val size: Int,
    internal val codec: TextureCodec,
)

/** The texture could not be read: truncated, corrupt, or not the format it claims to be. */
open class TextureFormatException(message: String) : Exception(message)

/** The texture is well-formed but uses a pixel format this package does not decode. */
class UnsupportedTextureException(val format: String) :
    TextureFormatException("$format textures cannot be previewed yet")

/** The entry point: checks the magic, then reads the header and mip table. */
object TextureReader {

    /** Larger than any texture in the vanilla game data, and well short of hurting the IDE's heap. */
    const val MAX_FILE_BYTES: Long = 128L * 1024 * 1024

    fun read(bytes: ByteArray): Texture {
        if (!EddsReader.isEdds(bytes)) throw TextureFormatException("not an EDDS texture")
        return EddsReader.read(bytes)
    }
}
