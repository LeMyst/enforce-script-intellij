package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.texture.format.Lz4
import io.github.lemyst.enfusion.texture.format.Lzo1x
import io.github.lemyst.enfusion.texture.format.TextureFormatException
import io.github.lemyst.enfusion.texture.format.TextureReader
import io.github.lemyst.enfusion.texture.format.UnsupportedTextureException
import junit.framework.TestCase
import kotlin.random.Random

/**
 * The texture decoders, `.edds` and `.paa`, on synthetic bytes. Real files are covered by [EnfusionTextureCorpusTest];
 * these pin down each codec and, above all, that a corrupt file is reported rather than crashing.
 */
class EnfusionTextureFormatTest : TestCase() {

    /**
     * Random BC7 blocks, one per mode (the mode is the lowest set bit of byte 0), with the 16 ARGB
     * texels Pillow's BC7 decoder gives for them. The decoder here was also checked against Pillow on
     * 65,536 random blocks, 8,192 per mode, with no difference.
     */
    private val BC7_REFERENCE = listOf(
        "53f22665a60c12d289185d950ee88136" to "ff52313eff41312aff74316aff41312aff6359bbff396b4aff7352e7ff7352e7ff6359bbff416760ff5b5da5ff7352e7ff565a93ff3886baff3886baff940042",
        "0a166f6b113d178d6c0fd3901ff239a1" to "ff5a4636ff8a4a48ffcbb5beff9a6464ffb2988cff6a160effab8185ffdbcfdbff705a4bff6a160effab8185ff7a302bff705a4bffcbb5beff9a6464ffbb9ba1",
        "a495f20f9395650cf9380b8edb224a6b" to "ff52804cff90494cff21ce73ff578d60ff525a39fff794b5ffce917afff794b5ff52804cffce917affa48f3bffa48f3bff52804cff90494cff21ce73ffc60839",
        "288a1e924e8fd0ae2e1a9492a3305f18" to "ff447a16ff25db29ff385518ff2ccb34ff25db29ff2b2e19ff2ccb34ff447a16ff1f091bff3bab4bff385518ff2ccb34ff25db29ff2b2e19ff2ccb34ff447a16",
        "90b610900f9e347fae886dc6507795ec" to "a48e18a463500963a4a11cb9637a138f24640e7824a11cb924640e78e38e18a424290039a43d054ea4500963a48e18a4e3a11cb9a4a11cb9e37a138f24290039",
        "605c4c3fcb2eb2c73e14934c867ee057" to "8cd9e3bf30d9b38b8cece3bfb9c4fbd95dc4cba55db1cba5b9b1fbd95dd9cba58cece3bf5deccba5b9c4fbd98cb1e3bf5db1cba58cd9e3bf5dd9cba5b9d9fbd9",
        "c072499bfa121e836b2ac15726ee7d6b" to "17a1958315978d750f7573451cb9a7a51ec3afb40c65672e148f876a17a1958315978d751cb9a7a509535914095359140a5d6123148f876a0d6d6d3915978d75",
        "80f6ab13c38e92cae0d15057b159987f" to "a67d865575b7485392aa462a3c9bcaa4728ca77c3c9bcaa492aa462a92aa462aae9e45043c9bcaa4728ca77c75b7485359c3497959c3497908aaebcb728ca77c",
    )

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val clear = 0x00000000

    fun testRawBgraEddsDecodes() {
        val bytes = TextureBytes.eddsBgra(4, 2, mips = 2) { x, _ -> if (x < 2) red else 0x8000FF00.toInt() }
        val texture = TextureReader.read(bytes)
        assertEquals("EDDS", texture.container)
        assertEquals("BGRA8", texture.formatName)
        assertEquals(listOf(4 to 2, 2 to 1), texture.mips.map { it.width to it.height })
        val image = texture.decode(0)
        assertEquals(red, image.getRGB(0, 0))
        assertEquals(0x8000FF00.toInt(), image.getRGB(3, 1))
        // The smaller mip is stored first in the file; reading it back proves the order was undone.
        assertEquals(red, texture.decode(1).getRGB(1, 0))
    }

    fun testLz4EddsDecodes() {
        val bytes = TextureBytes.eddsBgra(8, 8, mips = 1, lz4 = true) { x, y -> if ((x + y) % 2 == 0) red else green }
        val texture = TextureReader.read(bytes)
        assertEquals("LZ4", texture.compression)
        val image = texture.decode(0)
        assertEquals(red, image.getRGB(0, 0))
        assertEquals(green, image.getRGB(1, 0))
        assertEquals(red, image.getRGB(7, 7))
    }

    /** A match that overlaps its own output is how LZ4 writes a repeated run. */
    fun testLz4OverlappingMatch() {
        val block = byteArrayOf(0x22, 'a'.code.toByte(), 'b'.code.toByte(), 2, 0, 0x10, 'c'.code.toByte())
        val out = ByteArray(9)
        val end = Lz4.decodeBlock(block, 0, block.size, out, 0)
        assertEquals(9, end)
        assertEquals("ababababc", String(out, Charsets.US_ASCII))
    }

    fun testLz4MatchBeforeOutputStartIsCorrupt() {
        val block = byteArrayOf(0x10, 'a'.code.toByte(), 5, 0)
        assertThrows { Lz4.decodeBlock(block, 0, block.size, ByteArray(16), 0) }
    }

    /**
     * BC1 with c0 > c1 interpolates four colours; with c0 <= c1 the fourth texel is transparent,
     * which is how DXT1 encodes cut-out alpha.
     */
    fun testDxt1DecodesBothColourModes() {
        val fourColour = TextureReader.read(TextureBytes.eddsDxt1(4, 4, TextureBytes.bc1Block(0xF800, 0x001F)))
        assertEquals("DXT1", fourColour.formatName)
        val a = fourColour.decode(0)
        assertEquals(0xFFFF0000.toInt(), a.getRGB(0, 0))
        assertEquals(0xFF0000FF.toInt(), a.getRGB(1, 0))
        assertEquals(0xFFAA0055.toInt(), a.getRGB(2, 0))
        assertEquals(0xFF5500AA.toInt(), a.getRGB(3, 3))

        val threeColour = TextureReader.read(TextureBytes.eddsDxt1(4, 4, TextureBytes.bc1Block(0x001F, 0xF800)))
        assertEquals(clear, threeColour.decode(0).getRGB(3, 0))
    }

    /** A mip smaller than one block still occupies a whole block, and only its corner is kept. */
    fun testDxt1SmallerThanABlock() {
        val texture = TextureReader.read(TextureBytes.eddsDxt1(2, 1, TextureBytes.bc1Block(0xF800, 0x001F)))
        val image = texture.decode(0)
        assertEquals(2, image.width)
        assertEquals(0xFF0000FF.toInt(), image.getRGB(1, 0))
    }

    fun testDxt1PaaDecodes() {
        val texture = TextureReader.read(TextureBytes.paaDxt1(4, 4, TextureBytes.bc1Block(0xF800, 0x001F), lzo = false))
        assertEquals("PAA", texture.container)
        assertEquals("DXT1", texture.formatName)
        assertEquals("raw", texture.compression)
        val image = texture.decode(0)
        assertEquals(0xFFFF0000.toInt(), image.getRGB(0, 0))
        assertEquals(0xFF5500AA.toInt(), image.getRGB(3, 3))
    }

    /** The top bit of a `.paa` mip width marks LZO data; the width itself is the other 15 bits. */
    fun testLzoPaaDecodes() {
        val texture = TextureReader.read(TextureBytes.paaDxt1(8, 4, TextureBytes.bc1Block(0xF800, 0x001F), lzo = true))
        assertEquals("LZO", texture.compression)
        assertEquals(8, texture.width)
        assertEquals(0xFFFF0000.toInt(), texture.decode(0).getRGB(4, 2))
    }

    /**
     * A back-reference, which a literal-only stream never reaches: "abcd", then an M3 match of
     * length 6 at distance 3 (it overlaps its own output, giving "bcdbcd"), then the end marker.
     */
    fun testLzoMatch() {
        val stream = byteArrayOf(
            21, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(),
            (32 or 4).toByte(), (2 shl 2).toByte(), 0,
            0x11, 0, 0,
        )
        val out = Lzo1x.decompress(stream, 0, stream.size, 10)
        assertEquals("abcdbcdbcd", String(out, Charsets.US_ASCII))
    }

    fun testUncompressedPaaFormatsAreNamedNotDecoded() {
        val texture = TextureReader.read(TextureBytes.paaArgb4444(4, 4))
        assertEquals("ARGB4444", texture.formatName)
        assertFalse(texture.isSupported)
    }

    fun testBc5IsReadButNotDecoded() {
        val texture = TextureReader.read(TextureBytes.eddsBc5(16, 16))
        assertEquals("BC5", texture.formatName)
        assertFalse(texture.isSupported)
        try {
            texture.decode(0)
            fail("BC5 decoded")
        } catch (e: UnsupportedTextureException) {
            assertEquals("BC5", e.format)
        }
    }

    /** One block per mode, each checked texel by texel against an independent decoder. */
    fun testBc7DecodesEveryMode() {
        for ((mode, sample) in BC7_REFERENCE.withIndex()) {
            val (block, texels) = sample
            val image = TextureReader.read(TextureBytes.eddsBc7(4, 4, hex(block))).decode(0)
            val expected = hex(texels)
            for (i in 0 until 16) {
                val want = ((expected[4 * i].toInt() and 0xFF) shl 24) or ((expected[4 * i + 1].toInt() and 0xFF) shl 16) or
                    ((expected[4 * i + 2].toInt() and 0xFF) shl 8) or (expected[4 * i + 3].toInt() and 0xFF)
                assertEquals("mode $mode, texel $i", Integer.toHexString(want), Integer.toHexString(image.getRGB(i % 4, i / 4)))
            }
        }
    }

    fun testBc7ReservedModeIsTransparentBlack() {
        val image = TextureReader.read(TextureBytes.eddsBc7(4, 4, ByteArray(16) { if (it == 0) 0 else 0x5A })).decode(0)
        assertEquals(0, image.getRGB(0, 0))
        assertEquals(0, image.getRGB(3, 3))
    }

    fun testBc7CropsPartialBlocks() {
        val texture = TextureReader.read(TextureBytes.eddsBc7(6, 3, hex(BC7_REFERENCE[6].first)))
        assertEquals("BC7", texture.formatName)
        val image = texture.decode(0)
        assertEquals(6 to 3, image.width to image.height)
        // The second block repeats the first, so column 4 shows the block's column 0.
        assertEquals(image.getRGB(0, 2), image.getRGB(4, 2))
    }

    fun testLevelSelection() {
        val texture = TextureReader.read(TextureBytes.eddsBgra(64, 32, mips = 7) { _, _ -> red })
        // 64x32, 32x16, 16x8, 8x4, ...: the smallest covering 10 pixels both ways is 32x16.
        assertEquals(1, texture.levelCovering(10))
        assertEquals(0, texture.levelCovering(1000))
        assertEquals(2, texture.levelWithin(16L * 8))
    }

    fun testUnknownFileIsReportedNotThrown() {
        assertThrows { TextureReader.read("not a texture at all".toByteArray()) }
        assertThrows { TextureReader.read(ByteArray(0)) }
    }

    /**
     * A texture is untrusted input. Truncating or flipping bytes in valid files must only ever end
     * in a [TextureFormatException] or a decoded image, never in an index or size error.
     */
    fun testCorruptFilesFailCleanly() {
        val samples = listOf(
            TextureBytes.eddsBgra(16, 16, mips = 5) { x, y -> (x * 16 + y) or 0xFF000000.toInt() },
            TextureBytes.eddsBgra(16, 16, mips = 5, lz4 = true) { x, y -> (x xor y) or 0xFF000000.toInt() },
            TextureBytes.eddsDxt1(8, 8, TextureBytes.bc1Block(0xF800, 0x07E0)),
            TextureBytes.paaDxt1(8, 8, TextureBytes.bc1Block(0xF800, 0x07E0), lzo = true),
            TextureBytes.paaDxt1(8, 8, TextureBytes.bc1Block(0x07E0, 0xF800), lzo = false),
            TextureBytes.paaArgb4444(4, 4),
            TextureBytes.eddsBc5(8, 8),
            TextureBytes.eddsBc7(8, 8, hex(BC7_REFERENCE[6].first)),
        )
        val random = Random(42)
        for (sample in samples) {
            for (cut in 0 until sample.size step maxOf(1, sample.size / 64)) {
                decodeAll(sample.copyOf(cut))
            }
            repeat(400) {
                val mutated = sample.copyOf()
                repeat(1 + random.nextInt(4)) {
                    mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte()
                }
                decodeAll(mutated)
            }
        }
    }

    private fun decodeAll(bytes: ByteArray) {
        try {
            val texture = TextureReader.read(bytes)
            for (level in texture.mips.indices) {
                try {
                    texture.decode(level)
                } catch (_: TextureFormatException) {
                }
            }
        } catch (_: TextureFormatException) {
        }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected a TextureFormatException")
        } catch (_: TextureFormatException) {
        }
    }
}
