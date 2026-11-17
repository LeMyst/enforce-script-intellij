package io.github.lemyst.enfusion.texture.format

/**
 * Reads a Targa (`.tga`) image as ARGB pixels: the format mods keep their imageset sources in
 * (the source of an atlas often sits beside it), and one the JDK's ImageIO has no reader for. No
 * IntelliJ API.
 *
 * True-colour (24 and 32 bits) and grey (8 bits) images, uncompressed or run-length encoded, from
 * either corner. Colour-mapped images are refused.
 */
object TgaReader {

    class Image(val width: Int, val height: Int, val argb: IntArray)

    fun read(bytes: ByteArray): Image {
        if (bytes.size < 18) throw TextureFormatException("truncated TGA header")
        val idLength = bytes[0].toInt() and 0xFF
        val colourMapType = bytes[1].toInt() and 0xFF
        val type = bytes[2].toInt() and 0xFF
        val mapLength = readUInt16(bytes, 5)
        val mapEntryBits = bytes[7].toInt() and 0xFF
        val width = readUInt16(bytes, 12)
        val height = readUInt16(bytes, 14)
        val bits = bytes[16].toInt() and 0xFF
        val descriptor = bytes[17].toInt() and 0xFF
        if (width == 0 || height == 0) throw TextureFormatException("invalid TGA size ${width}x$height")
        val rle = type >= 9
        val grey = when (type) {
            2, 10 -> false
            3, 11 -> true
            1, 9 -> throw UnsupportedTextureException("Colour-mapped TGA")
            else -> throw TextureFormatException("unsupported TGA image type $type")
        }
        val bytesPerPixel = when {
            grey && bits == 8 -> 1
            !grey && (bits == 24 || bits == 32) -> bits / 8
            else -> throw TextureFormatException("unsupported TGA pixel size $bits for ${if (grey) "grey" else "true colour"}")
        }
        var at = 18 + idLength + if (colourMapType == 1) mapLength * ((mapEntryBits + 7) / 8) else 0
        val count = width * height
        val pixels = IntArray(count)
        // A 32-bit image whose descriptor declares no alpha bits stores padding there, not alpha.
        val hasAlpha = bytesPerPixel == 4 && descriptor and 0x0F != 0
        fun pixel(at: Int): Int {
            if (at + bytesPerPixel > bytes.size) throw TextureFormatException("truncated TGA data")
            val b = bytes[at].toInt() and 0xFF
            val rgb = when (bytesPerPixel) {
                1 -> (b shl 16) or (b shl 8) or b
                else -> ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or b
            }
            val alpha = if (hasAlpha) bytes[at + 3].toInt() and 0xFF else 0xFF
            return (alpha shl 24) or rgb
        }
        var i = 0
        if (!rle) {
            while (i < count) {
                pixels[i++] = pixel(at)
                at += bytesPerPixel
            }
        } else {
            while (i < count) {
                if (at >= bytes.size) throw TextureFormatException("truncated TGA data")
                val header = bytes[at++].toInt() and 0xFF
                val run = (header and 0x7F) + 1
                if (i + run > count) throw TextureFormatException("TGA run past the end of the image")
                if (header and 0x80 != 0) {
                    val p = pixel(at)
                    at += bytesPerPixel
                    repeat(run) { pixels[i++] = p }
                } else {
                    repeat(run) {
                        pixels[i++] = pixel(at)
                        at += bytesPerPixel
                    }
                }
            }
        }
        // Rows are stored bottom first unless bit 5 says top first; bit 4 says right to left.
        val bottomUp = descriptor and 0x20 == 0
        val rightToLeft = descriptor and 0x10 != 0
        if (!bottomUp && !rightToLeft) return Image(width, height, pixels)
        val out = IntArray(count)
        for (y in 0 until height) {
            val from = if (bottomUp) height - 1 - y else y
            for (x in 0 until width) {
                out[y * width + x] = pixels[from * width + if (rightToLeft) width - 1 - x else x]
            }
        }
        return Image(width, height, out)
    }
}
