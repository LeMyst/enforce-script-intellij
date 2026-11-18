package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.texture.format.BlockEncoder
import io.github.lemyst.enfusion.texture.format.EddsWriter
import io.github.lemyst.enfusion.texture.format.Lz4Writer
import io.github.lemyst.enfusion.texture.format.TextureReader
import junit.framework.TestCase
import kotlin.math.abs
import kotlin.random.Random

/**
 * The `.edds` encoders, each read back by the plugin's own decoders: LZ4 mips in vanilla's chunk
 * layout, and BC1 / BC3 blocks within a tolerance. Hand-made pixels only.
 */
class EnfusionTextureEncodersTest : TestCase() {

    /** A UI-like picture: flat areas, a gradient, a soft-edged disc with partial alpha. */
    private fun picture(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val dx = x - w / 2.0
        val dy = y - h / 2.0
        val d = kotlin.math.sqrt(dx * dx + dy * dy) / (w / 2.0)
        val alpha = ((1.0 - d).coerceIn(0.0, 1.0) * 255).toInt()
        val r = 255 * x / maxOf(1, w - 1)
        val g = if (y < h / 2) 200 else 40
        val b = 255 * y / maxOf(1, h - 1)
        (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun pixels(bytes: ByteArray, level: Int = 0): IntArray {
        val image = TextureReader.read(bytes).decode(level)
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    fun testLz4MipsReadBackExactly() {
        val (w, h) = 256 to 256
        val argb = picture(w, h)
        val plain = EddsWriter.write(argb, w, h, mips = true, format = EddsWriter.Format.BGRA8, lz4 = false)
        val packed = EddsWriter.write(argb, w, h, mips = true, format = EddsWriter.Format.BGRA8, lz4 = true)
        assertTrue("LZ4 makes it smaller: ${packed.size} vs ${plain.size}", packed.size < plain.size)
        assertEquals(argb.toList(), pixels(packed).toList())
        val texture = TextureReader.read(packed)
        for (level in texture.mips.indices) assertEquals(pixels(plain, level).toList(), pixels(packed, level).toList())
    }

    /** Vanilla's chunks: 64 KiB of output each, the last with the `0x80` flag. */
    fun testLz4ChunkLayout() {
        val raw = ByteArray(150_000) { (it % 251).toByte() }
        val mip = Lz4Writer.compressEddsMip(raw)
        assertEquals(raw.size, readInt(mip, 0))
        var at = 4
        val flags = ArrayList<Int>()
        while (at < mip.size) {
            val head = readInt(mip, at)
            flags += head ushr 24
            at += 4 + (head and 0xFFFFFF)
        }
        assertEquals(listOf(0, 0, 0x80), flags)
    }

    /** Data that does not compress is stored raw, and so is a mip under 64 bytes. */
    fun testIncompressibleMipsStayRaw() {
        val random = Random(42)
        val (w, h) = 64 to 64
        val noise = IntArray(w * h) { random.nextInt() }
        val bytes = EddsWriter.write(noise, w, h, mips = true, format = EddsWriter.Format.BGRA8, lz4 = true)
        val tags = mipTags(bytes, TextureReader.read(bytes).mips.size)
        assertEquals("the largest mip of noise", "COPY", tags.last())
        assertEquals("a 1 x 1 mip", "COPY", tags.first())
        assertEquals(noise.toList(), pixels(bytes).toList())
    }

    fun testBc3IsCloseAndKeepsSmoothAlpha() {
        val (w, h) = 64 to 64
        val argb = picture(w, h)
        val bytes = EddsWriter.write(argb, w, h, mips = true, format = EddsWriter.Format.DXT5, lz4 = false)
        val texture = TextureReader.read(bytes)
        assertTrue(texture.formatName, texture.formatName.contains("DXT5") || texture.formatName.contains("BC3"))
        assertEquals(w * h, BlockEncoder.size(w, h, 16))
        assertClose(argb, pixels(bytes), colourTolerance = 24, alphaTolerance = 6)
    }

    fun testBc1IsCloseAndCutsAlpha() {
        val (w, h) = 64 to 64
        val opaque = picture(w, h).map { it or (0xFF shl 24) }.toIntArray()
        val bytes = EddsWriter.write(opaque, w, h, mips = false, format = EddsWriter.Format.DXT1, lz4 = false)
        assertClose(opaque, pixels(bytes), colourTolerance = 24, alphaTolerance = 0)
        // A cut-out: transparent texels decode transparent, opaque ones opaque.
        val cut = IntArray(16 * 16) { if (it % 16 < 8) 0x00000000 else 0xFF336699.toInt() }
        val decoded = pixels(EddsWriter.write(cut, 16, 16, mips = false, format = EddsWriter.Format.DXT1, lz4 = false))
        for (i in cut.indices) assertEquals("texel $i", cut[i] ushr 24 == 0xFF, decoded[i] ushr 24 == 0xFF)
        assertTrue(EddsWriter.hasPartialAlpha(picture(8, 8)))
        assertFalse(EddsWriter.hasPartialAlpha(cut))
    }

    /** Sides that are not a multiple of 4, as the small mips are. */
    fun testOddSizes() {
        for ((w, h) in listOf(1 to 1, 2 to 3, 5 to 7)) {
            val argb = picture(w, h)
            val bytes = EddsWriter.write(argb, w, h, mips = false, format = EddsWriter.Format.DXT5, lz4 = true)
            assertEquals(w * h, pixels(bytes).size)
        }
    }

    fun testSameInputSameBytes() {
        val argb = picture(128, 64)
        for (format in EddsWriter.Format.entries) {
            val a = EddsWriter.write(argb, 128, 64, mips = true, format = format, lz4 = true)
            val b = EddsWriter.write(argb, 128, 64, mips = true, format = format, lz4 = true)
            assertEquals(format.name, a.toList(), b.toList())
        }
    }

    private fun assertClose(expected: IntArray, actual: IntArray, colourTolerance: Int, alphaTolerance: Int) {
        assertEquals(expected.size, actual.size)
        var worstColour = 0
        var worstAlpha = 0
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            worstAlpha = maxOf(worstAlpha, abs((e ushr 24) - (a ushr 24)))
            // Colour only matters where the texel is visible.
            if (e ushr 24 < 16) continue
            for (shift in intArrayOf(16, 8, 0)) worstColour = maxOf(worstColour, abs((e ushr shift and 0xFF) - (a ushr shift and 0xFF)))
        }
        assertTrue("colour off by $worstColour", worstColour <= colourTolerance)
        assertTrue("alpha off by $worstAlpha", worstAlpha <= alphaTolerance)
    }

    private fun mipTags(bytes: ByteArray, mips: Int): List<String> =
        (0 until mips).map { String(bytes, 128 + 8 * it, 4, Charsets.US_ASCII) }

    private fun readInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
