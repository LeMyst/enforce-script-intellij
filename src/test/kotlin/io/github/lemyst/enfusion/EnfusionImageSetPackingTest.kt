package io.github.lemyst.enfusion

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import io.github.lemyst.enfusion.texture.format.AtlasPacker
import io.github.lemyst.enfusion.texture.format.EddsWriter
import io.github.lemyst.enfusion.texture.format.ImageSetPacking
import io.github.lemyst.enfusion.texture.format.TextureReader
import io.github.lemyst.enfusion.texture.format.TgaReader

/**
 * Packing pictures into an imageset: the packer, the `.edds` writer read back by the plugin's own
 * reader, the TGA reader, and the `.imageset` read back by the plugin's own parser. Hand-made
 * pictures only.
 */
class EnfusionImageSetPackingTest : BasePlatformTestCase() {

    private fun sprites(vararg sizes: Pair<Int, Int>) = sizes.mapIndexed { i, (w, h) -> AtlasPacker.Sprite("s$i", w, h) }

    fun testPackerKeepsPaddingFreeAndStaysInside() {
        val sprites = sprites(100 to 40, 30 to 30, 64 to 64, 10 to 90, 17 to 5, 50 to 50, 1 to 1)
        val padding = 2
        val atlas = AtlasPacker.pack(sprites, padding, 1024)
        assertEquals(sprites.size, atlas.placements.size)
        assertEquals("a power of two", 0, atlas.width and (atlas.width - 1))
        assertEquals(0, atlas.height and (atlas.height - 1))
        val boxes = atlas.placements.map { p -> intArrayOf(p.x - padding, p.y - padding, p.sprite.width + 2 * padding, p.sprite.height + 2 * padding) }
        for (b in boxes) {
            assertTrue("inside the atlas", b[0] >= 0 && b[1] >= 0 && b[0] + b[2] <= atlas.width && b[1] + b[3] <= atlas.height)
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]
            val b = boxes[j]
            val overlap = a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3]
            assertFalse("${atlas.placements[i].sprite.name} and ${atlas.placements[j].sprite.name} overlap", overlap)
        }
    }

    fun testPackerIsDeterministicAndPicksTheSmallestAtlas() {
        val sprites = sprites(32 to 32, 32 to 32, 32 to 32, 32 to 32)
        val once = AtlasPacker.pack(sprites, 0, 1024)
        val again = AtlasPacker.pack(sprites.reversed(), 0, 1024)
        assertEquals(64 to 64, once.width to once.height)
        assertEquals(once.placements.map { Triple(it.sprite.name, it.x, it.y) }.sortedBy { it.first }, again.placements.map { Triple(it.sprite.name, it.x, it.y) }.sortedBy { it.first })
    }

    fun testPackerSaysWhatDoesNotFit() {
        try {
            AtlasPacker.pack(sprites(300 to 10), 2, 256)
            fail("a picture wider than the atlas was packed")
        } catch (e: AtlasPacker.DoesNotFit) {
            assertTrue(e.message!!, e.message!!.contains("s0"))
        }
        try {
            AtlasPacker.pack(sprites(*Array(5) { 128 to 128 }), 0, 256)
            fail("five 128s fitted in 256 × 256")
        } catch (e: AtlasPacker.DoesNotFit) {
            assertTrue(e.message!!, e.message!!.contains("256"))
        }
    }

    fun testEddsRoundTripsThroughTheReader() {
        val w = 5
        val h = 3
        val argb = IntArray(w * h) { i -> (0xFF shl 24) or (i * 17 shl 16) or (i * 3 shl 8) or (255 - i) }
        argb[4] = 0x00000000
        val bytes = EddsWriter.bgra8(argb, w, h, mips = true)
        val texture = TextureReader.read(bytes)
        assertEquals("BGRA8", texture.formatName)
        assertEquals("5×3, 2×1, 1×1", listOf(5 to 3, 2 to 1, 1 to 1), texture.mips.map { it.width to it.height })
        val decoded = texture.decode(0)
        assertEquals(argb.toList(), decoded.getRGB(0, 0, w, h, null, 0, w).toList())
        assertEquals("the same pixels give the same bytes", bytes.toList(), EddsWriter.bgra8(argb, w, h, mips = true).toList())
        assertEquals(1, TextureReader.read(EddsWriter.bgra8(argb, w, h, mips = false)).mips.size)
        assertEquals("the Enfusion mark every .edds carries", "ENF1", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals("COPY", String(bytes, 128, 4, Charsets.US_ASCII))
    }

    /** A transparent pixel's colour does not darken a smaller mip. */
    fun testMipsWeighColourByAlpha() {
        val (pixels, w, h) = EddsWriter.downsample(intArrayOf(0xFFFF0000.toInt(), 0x00000000, 0x00000000, 0x00000000), 2, 2)
        assertEquals(1 to 1, w to h)
        assertEquals(0x40FF0000, pixels[0])
    }

    fun testTgaUncompressedBottomUpAndRunLengthTopDown() {
        // 2 × 2, 24-bit, uncompressed, rows bottom first: bottom row red, green; top row blue, white.
        val plain = header(type = 2, bits = 24, descriptor = 0) + bytes(0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255)
        val a = TgaReader.read(plain)
        assertEquals(listOf(0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt()), a.argb.toList())
        // 3 × 1, 32-bit with 8 alpha bits, run-length, top first: a run of two, then one raw pixel.
        val rle = header(type = 10, bits = 32, descriptor = 0x28, width = 3, height = 1) +
            bytes(0x81, 1, 2, 3, 0x80, 0x00, 9, 8, 7, 0xFF)
        assertEquals(listOf(0x80030201.toInt(), 0x80030201.toInt(), 0xFF070809.toInt()), TgaReader.read(rle).argb.toList())
    }

    fun testImageNames() {
        assertEquals("arrow_left", ImageSetPacking.imageName("arrow_left.png"))
        assertEquals("my_icon_2", ImageSetPacking.imageName("my icon-2.tga"))
        assertEquals("_1st", ImageSetPacking.imageName("1st.png"))
    }

    fun testClashingNamesAreAProblem() {
        val pixel = intArrayOf(-1)
        try {
            ImageSetPacking.pack(listOf(ImageSetPacking.Source("Icon", null, 1, 1, pixel), ImageSetPacking.Source("icon", "Other", 1, 1, pixel)))
            fail("two pictures named icon were packed")
        } catch (e: ImageSetPacking.Problem) {
            assertTrue(e.message!!, e.message!!.contains("icon"))
        }
    }

    /** The descriptor parses, and names each picture where its pixels are. */
    fun testImageSetTextParsesAndPlacesEachPicture() {
        val red = ImageSetPacking.Source("red", null, 3, 2, IntArray(6) { 0xFFFF0000.toInt() })
        val blue = ImageSetPacking.Source("blue", "Icons", 2, 2, IntArray(4) { 0xFF0000FF.toInt() })
        val packed = ImageSetPacking.pack(listOf(red, blue), ImageSetPacking.Options(padding = 1))
        for (p in packed.placements) {
            val colour = p.source.argb[0]
            assertEquals(p.source.name, colour, packed.argb[p.y * packed.width + p.x])
            assertEquals("extruded into the padding", colour, packed.argb[(p.y - 1) * packed.width + p.x - 1])
        }
        val text = ImageSetPacking.imageSet("my_set", "MyMod/GUI/imagesets/my_set.edds", packed)
        assertEquals(text, ImageSetPacking.imageSet("my_set", "MyMod/GUI/imagesets/my_set.edds", ImageSetPacking.pack(listOf(blue, red), ImageSetPacking.Options(padding = 1))))
        val psi = myFixture.configureByText("my_set.imageset", text)
        assertNull(text, PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java))
        val defs = PsiTreeUtil.findChildrenOfType(psi, EnObjDeclMixin::class.java).filter { it.getClassName() == "ImageSetDefClass" }
        assertEquals(listOf("red", "blue").sorted(), defs.mapNotNull { it.declaredName() }.sorted())
        assertTrue(text, text.contains("ImageSetGroupClass Icons {\n   Name \"Icons\""))
        assertTrue(text, text.contains("RefSize ${packed.width} ${packed.height}"))
        assertTrue(text, text.contains("path \"MyMod/GUI/imagesets/my_set.edds\""))
        val redPlace = packed.placements.first { it.source.name == "red" }
        assertTrue(text, text.contains("ImageSetDefClass red {\n   Name \"red\"\n   Pos ${redPlace.x} ${redPlace.y}\n   Size 3 2\n   Flags 0\n  }"))
    }

    private fun header(type: Int, bits: Int, descriptor: Int, width: Int = 2, height: Int = 2): ByteArray {
        val h = ByteArray(18)
        h[2] = type.toByte()
        h[12] = width.toByte()
        h[14] = height.toByte()
        h[16] = bits.toByte()
        h[17] = descriptor.toByte()
        return h
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
