package io.github.lemyst.enfusion

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.texture.EnImageSetTextures
import io.github.lemyst.enfusion.texture.EnTextureEditor
import io.github.lemyst.enfusion.texture.EnTexturePreviews
import java.util.concurrent.TimeUnit

/** The viewer outlines every imageset image placed on the texture it shows. */
class EnfusionImageSetOverlayTest : BasePlatformTestCase() {

    private lateinit var atlas: VirtualFile
    private lateinit var atlas2x: VirtualFile

    override fun setUp() {
        super.setUp()
        atlas = texture("MyMod/GUI/imagesets/my_gui.edds", 8)
        atlas2x = texture("MyMod/GUI/imagesets/my_gui@2x.edds", 16)
        myFixture.addFileToProject("MyMod/GUI/imagesets/my_gui.imageset", imageSet("""
              ImageSetDefClass Corner {
               Name "Corner"
               Pos 4 0
               Size 4 4
              }
              ImageSetDefClass Dot {
               Name "Dot"
               Pos 0 0
               Size 2 2
              }"""))
    }

    private fun texture(path: String, side: Int): VirtualFile {
        val file = myFixture.tempDirFixture.createFile(path)
        runWriteAction { file.setBinaryContent(TextureBytes.eddsBgra(side, side) { _, _ -> 0xFFFF0000.toInt() }) }
        return file
    }

    /** One set naming a 1x and a 2x texture; `Pos` and `Size` count in the 1x texture's pixels. */
    private fun imageSet(images: String) = """
        ImageSetClass {
         Name "my_gui"
         RefSize 8 8
         Textures {
          ImageSetTextureClass {
           mpix 0
           path "{0123456789ABCDEF}MyMod/GUI/imagesets/my_gui.edds"
          }
          ImageSetTextureClass {
           mpix 1
           path "MyMod/GUI/imagesets/my_gui@2x.edds"
          }
         }
         Images {
        $images
         }
        }
    """.trimIndent()

    fun testBothTexturesOfASetCarryItsImages() {
        for (texture in listOf(atlas, atlas2x)) {
            val names = EnImageSetTextures.entriesOn(project, texture).map { it.name }
            assertEquals(texture.name, listOf("Corner", "Dot"), names.sorted())
            assertEquals("my_gui", EnImageSetTextures.entriesOn(project, texture).first().setName)
        }
        assertEmpty(EnImageSetTextures.entriesOn(project, texture("MyMod/GUI/textures/other.edds", 8)))
    }

    /** `Pos 4 0 Size 4 4` on the 8-pixel base is the top-right quadrant, on either texture. */
    fun testRegionsLandInTheSamePlaceOnEitherTexture() {
        val corner = EnImageSetTextures.entriesOn(project, atlas2x).single { it.name == "Corner" }.region
        val expected = EnTexturePreviews.Fractions(0.5, 0.0, 0.5, 0.5)
        assertEquals(expected, corner.fractions(16, 16))
        assertEquals(expected, corner.fractions(8, 8))
    }

    /**
     * The base texture's own size wins over `RefSize`. Vanilla's `dayz_crosshairs` says `RefSize
     * 1024 1024` over a 512² texture, and its images sit in that texture's pixels.
     */
    fun testStaleRefSizeIsOverruledByTheBaseTexture() {
        val half = texture("MyMod/GUI/imagesets/crosshairs.edds", 8)
        myFixture.addFileToProject("MyMod/GUI/imagesets/crosshairs.imageset", """
            ImageSetClass {
             Name "crosshairs"
             RefSize 16 16
             Textures {
              ImageSetTextureClass {
               mpix 3
               path "MyMod/GUI/imagesets/crosshairs.edds"
              }
             }
             Images {
              ImageSetDefClass Cross {
               Pos 4 0
               Size 4 4
              }
             }
            }
        """.trimIndent())
        val cross = EnImageSetTextures.entriesOn(project, half).single().region
        assertEquals(EnTexturePreviews.Fractions(0.5, 0.0, 0.5, 0.5), cross.fractions(8, 8))
    }

    /** With the base texture missing, `RefSize` is the space: the 2x texture is not a base. */
    fun testMissingBaseTextureFallsBackToRefSize() {
        val only2x = texture("MyMod/GUI/imagesets/lonely@2x.edds", 16)
        myFixture.addFileToProject("MyMod/GUI/imagesets/lonely.imageset", """
            ImageSetClass {
             Name "lonely"
             RefSize 8 8
             Textures {
              ImageSetTextureClass {
               mpix 0
               path "MyMod/GUI/imagesets/lonely.edds"
              }
              ImageSetTextureClass {
               mpix 1
               path "MyMod/GUI/imagesets/lonely@2x.edds"
              }
             }
             Images {
              ImageSetDefClass Half {
               Pos 4 0
               Size 4 4
              }
             }
            }
        """.trimIndent())
        val region = EnImageSetTextures.entriesOn(project, only2x).single().region
        assertEquals(EnTexturePreviews.Fractions(0.5, 0.0, 0.5, 0.5), region.fractions(16, 16))
    }

    fun testWithoutRefSizeRegionsAreInTexturePixels() {
        val region = EnTexturePreviews.Region(2, 0, 2, 4, null, null)
        assertEquals(EnTexturePreviews.Fractions(0.25, 0.0, 0.25, 0.5), region.fractions(8, 8))
    }

    fun testRegionOffTheTextureIsDropped() {
        assertNull(EnTexturePreviews.Region(12, 0, 8, 8, 16, 16).fractions(8, 8))
        assertNull(EnTexturePreviews.Region(0, 0, 0, 4, 16, 16).fractions(8, 8))
    }

    /** Opening the texture finds its images; editing the imageset updates them. */
    fun testViewerFollowsTheImageSet() {
        val editor = EnTextureEditor(project, atlas)
        try {
            waitFor { editor.shownRegions().size == 2 }

            val imageset = psiManager.findFile(myFixture.findFileInTempDir("MyMod/GUI/imagesets/my_gui.imageset"))!!
            val document = PsiDocumentManager.getInstance(project).getDocument(imageset)!!
            val anchor = document.text.indexOf("ImageSetDefClass Dot")
            runWriteAction {
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                    document.insertString(anchor, "ImageSetDefClass Extra {\n   Name \"Extra\"\n   Pos 0 4\n   Size 4 4\n  }\n  ")
                }
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            waitFor { editor.shownRegions().map { it.name }.sorted() == listOf("Corner", "Dot", "Extra") }
        } finally {
            Disposer.dispose(editor)
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for the viewer")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
    }
}
