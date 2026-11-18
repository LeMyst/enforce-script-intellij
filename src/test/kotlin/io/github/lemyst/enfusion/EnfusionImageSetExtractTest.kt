package io.github.lemyst.enfusion

import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.texture.ExtractImageSet
import io.github.lemyst.enfusion.texture.format.ImageSetExtraction
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/** *Extract Images…*: each image cut out of its atlas at the atlas's resolution, grouped by folder. */
class EnfusionImageSetExtractTest : BasePlatformTestCase() {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0x800000FF.toInt()

    fun testPixelRects() {
        assertEquals(ImageSetExtraction.Rect(4, 0, 4, 4), ImageSetExtraction.pixelRect(0.5, 0.0, 0.5, 0.5, 8, 8))
        // A 1x region on a 2x atlas lands on whole pixels of it.
        assertEquals(ImageSetExtraction.Rect(8, 0, 8, 8), ImageSetExtraction.pixelRect(0.5, 0.0, 0.5, 0.5, 16, 16))
        assertNull(ImageSetExtraction.pixelRect(1.0, 1.0, 0.0, 0.1, 8, 8))
    }

    fun testCropKeepsAlpha() {
        val atlas = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        atlas.setRGB(2, 1, blue)
        val cut = ImageSetExtraction.crop(atlas, ImageSetExtraction.Rect(2, 1, 2, 2))
        assertEquals(2, cut.width)
        assertEquals(blue, cut.getRGB(0, 0))
    }

    fun testNames() {
        assertEquals("a_b_c", ImageSetExtraction.fileName("a/b:c"))
        val used = HashSet<String>()
        assertEquals("icons/Arrow.png", ImageSetExtraction.unique("icons/Arrow.png", used))
        assertEquals("icons/arrow_2.png", ImageSetExtraction.unique("icons/arrow.png", used))
    }

    fun testExtractsEachImageIntoItsGroup() {
        val bytes = TextureBytes.eddsBgra(8, 8, mips = 1) { x, y -> if (x >= 4 && y < 4) green else red }
        val atlas = myFixture.tempDirFixture.createFile("MyMod/GUI/imagesets/my_gui.edds")
        runWriteAction { atlas.setBinaryContent(bytes) }
        val imageset = myFixture.addFileToProject(
            "MyMod/GUI/imagesets/my_gui.imageset",
            """
            ImageSetClass {
             Name "my_gui"
             RefSize 8 8
             Textures {
              ImageSetTextureClass {
               mpix 1
               path "MyMod/GUI/imagesets/my_gui.edds"
              }
             }
             Images {
              ImageSetDefClass Corner {
               Name "Corner"
               Pos 4 0
               Size 4 4
               Flags 0
              }
             }
             Groups {
              ImageSetGroupClass Arrows {
               Name "Arrows"
               Images {
                ImageSetDefClass Left {
                 Name "Left"
                 Pos 0 4
                 Size 2 2
                 Flags 0
                }
               }
              }
             }
            }
            """.trimIndent(),
        ).virtualFile

        val images = ExtractImageSet.imagesOf(project, imageset)
        assertEquals(listOf("Corner.png", "Arrows/Left.png"), images.map { it.relativePath })

        val target = Files.createTempDirectory("extract")
        ExtractImageSet.run(project, images, target)
        val corner = ImageIO.read(target.resolve("Corner.png").toFile())
        assertEquals(4, corner.width)
        assertEquals(green, corner.getRGB(0, 0))
        val left = ImageIO.read(target.resolve("Arrows").resolve("Left.png").toFile())
        assertEquals(2, left.width)
        assertEquals(red, left.getRGB(1, 1))
    }
}
