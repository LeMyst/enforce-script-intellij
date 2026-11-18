package io.github.lemyst.enfusion.texture.format

import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Cutting images back out of an atlas, the reverse of [ImageSetPacking]. No IntelliJ API, so it is
 * tested on plain images.
 */
object ImageSetExtraction {

    /** A rectangle in pixels: left, top, width, height. */
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * A region given as fractions of the atlas, in the atlas's own pixels. Rounded to the nearest
     * pixel, since a 1x imageset's whole-pixel regions land on whole pixels of its 2x atlas too.
     * Null when nothing of it is inside the atlas.
     */
    fun pixelRect(x: Double, y: Double, w: Double, h: Double, atlasWidth: Int, atlasHeight: Int): Rect? {
        val left = (x * atlasWidth).roundToInt().coerceIn(0, atlasWidth)
        val top = (y * atlasHeight).roundToInt().coerceIn(0, atlasHeight)
        val right = ((x + w) * atlasWidth).roundToInt().coerceIn(0, atlasWidth)
        val bottom = ((y + h) * atlasHeight).roundToInt().coerceIn(0, atlasHeight)
        if (right <= left || bottom <= top) return null
        return Rect(left, top, right - left, bottom - top)
    }

    /** A copy of [rect] of [atlas], with its alpha. */
    fun crop(atlas: BufferedImage, rect: Rect): BufferedImage {
        val out = BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_ARGB)
        val row = IntArray(rect.width)
        for (y in 0 until rect.height) {
            atlas.getRGB(rect.x, rect.y + y, rect.width, 1, row, 0, rect.width)
            out.setRGB(0, y, rect.width, 1, row, 0, rect.width)
        }
        return out
    }

    /** An image or group name as a file name: what Windows refuses in one becomes `_`. */
    fun fileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "_").trim().trimEnd('.').ifEmpty { "image" }

    /**
     * [path], or `path_2.png` and so on when it was already used: two images may share a name in
     * different sets of one file, and Windows does not tell `Arrow` from `arrow`.
     */
    fun unique(path: String, used: MutableSet<String>): String {
        if (used.add(path.lowercase())) return path
        val stem = path.substringBeforeLast('.')
        val extension = path.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val candidate = if (extension.isEmpty()) "${stem}_$n" else "${stem}_$n.$extension"
            if (used.add(candidate.lowercase())) return candidate
            n++
        }
    }
}
