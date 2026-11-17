package io.github.lemyst.enfusion.objfmt.preview

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the layout preview needs from an Enfusion font file (`.fnt`): the size it was made at and,
 * when it says, the height of a line. No IntelliJ API, so it is tested on plain bytes.
 *
 * A `.fnt` is an IFF file: `FORM`, a big-endian length, `FNT3` or `FNT5`, then chunks, each a
 * four-letter name and a big-endian length. The `HEAD` chunk is little-endian inside: the name's
 * length and the name, its NUL included, then the size in pixels as a 32-bit integer. A bitmap
 * font (`FNT3`, `gui/fonts/metron14.fnt`) stops there. A distance-field font (`FNT5`,
 * `sdf_metronbook24.fnt`) goes on with four zero bytes, one byte, then the line height as a
 * 16-bit integer: 36 for the 24-pixel `sdf_MetronBook24`, the height Workbench gives one line of
 * `day_z_underground_trigger_editor_popup_dialog`'s message, which is sized to its text.
 */
object FontFiles {

    /** A font's [size] in pixels, and the [lineHeight] of a line at that size when the file gives one. */
    class Metrics(val size: Double, val lineHeight: Double?)

    /** The metrics of a `.fnt` file's [bytes], or null when they are not one this reads. */
    fun read(bytes: ByteArray): Metrics? {
        if (bytes.size < 12 || !bytes.startsWith("FORM", 0)) return null
        val version = String(bytes, 8, 4, Charsets.US_ASCII)
        if (version != "FNT3" && version != "FNT5") return null
        val big = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var at = 12
        while (at + 8 <= bytes.size) {
            val length = big.getInt(at + 4)
            if (length < 0 || at + 8 + length > bytes.size) return null
            if (bytes.startsWith("HEAD", at)) return head(ByteBuffer.wrap(bytes, at + 8, length).slice().order(ByteOrder.LITTLE_ENDIAN), version == "FNT5")
            at += 8 + length + (length and 1)
        }
        return null
    }

    private fun head(head: ByteBuffer, distanceField: Boolean): Metrics? {
        if (head.remaining() < 4) return null
        val nameLength = head.getInt(0)
        val sizeAt = 4 + nameLength
        if (nameLength < 0 || sizeAt + 4 > head.remaining()) return null
        val size = head.getInt(sizeAt)
        if (size <= 0) return null
        val lineAt = sizeAt + 9
        val line = if (distanceField && lineAt + 2 <= head.remaining()) head.getShort(lineAt).toInt() and 0xFFFF else 0
        return Metrics(size.toDouble(), line.takeIf { it > 0 }?.toDouble())
    }

    private fun ByteArray.startsWith(tag: String, at: Int): Boolean =
        at + tag.length <= size && tag.indices.all { this[at + it] == tag[it].code.toByte() }

    /**
     * The size a font's name gives when its file is not found (no work drive): vanilla names each
     * single-size font after its size, `sdf_MetronBook24`, `Metron16`. A family name without one,
     * `gui/fonts/Metron`, gives none.
     */
    fun sizeInName(font: String): Double? =
        Regex("""(\d+)$""").find(font.trim().substringAfterLast('/').substringAfterLast('\\'))?.value?.toDoubleOrNull()?.takeIf { it > 0 }
}
