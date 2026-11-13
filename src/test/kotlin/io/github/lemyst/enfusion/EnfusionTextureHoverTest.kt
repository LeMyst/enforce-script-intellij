package io.github.lemyst.enfusion

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import io.github.lemyst.enfusion.texture.EnImageSetTextures
import io.github.lemyst.enfusion.texture.EnTextureDocs
import io.github.lemyst.enfusion.texture.EnTextureDocumentationTargetProvider
import java.awt.image.BufferedImage

/**
 * Hover documentation for textures: a texture path shows the whole texture, and an imageset image
 * shows only its own rectangle, cut out of the atlas.
 *
 * The atlas is 8×8 with four coloured quadrants, so a crop is checked by its colour alone.
 */
class EnfusionTextureHoverTest : BasePlatformTestCase() {

    private val topLeft = 0xFFFF0000.toInt()
    private val topRight = 0xFF00FF00.toInt()
    private val bottomLeft = 0xFF0000FF.toInt()
    private val bottomRight = 0xFFFFFF00.toInt()

    private fun addAtlas(path: String = "MyMod/GUI/imagesets/my_gui.edds"): VirtualFile {
        val bytes = TextureBytes.eddsBgra(8, 8, mips = 4) { x, y ->
            when {
                y < 4 && x < 4 -> topLeft
                y < 4 -> topRight
                x < 4 -> bottomLeft
                else -> bottomRight
            }
        }
        val file = myFixture.tempDirFixture.createFile(path)
        runWriteAction { file.setBinaryContent(bytes) }
        return file
    }

    /**
     * `Corner` is the top-right quadrant of the 8-pixel atlas, in that texture's pixels, which is
     * what `RefSize` repeats. A larger atlas passes its own size and a quadrant in its pixels.
     */
    private fun addImageSet(side: Int = 8) {
        val half = side / 2
        myFixture.addFileToProject(
            "MyMod/GUI/imagesets/my_gui.imageset",
            """
            ImageSetClass {
             Name "my_gui"
             RefSize $side $side
             Textures {
              ImageSetTextureClass {
               mpix 1
               path "{0123456789ABCDEF}MyMod/GUI/imagesets/my_gui.edds"
              }
             }
             Images {
              ImageSetDefClass Corner {
               Name "Corner"
               Pos $half 0
               Size $half $half
               Flags 0
              }
             }
            }
            """.trimIndent(),
        )
    }

    private fun resolveAtCaret(): PsiElement =
        (myFixture.getReferenceAtCaretPosition() as PsiPolyVariantReference)
            .multiResolve(false).firstNotNullOf { it.element }

    fun testProviderIsRegisteredAndClaimsTextures() {
        addAtlas()
        myFixture.configureByText("hud.es", "class Hud { string m_Tex = \"MyMod/GUI/imagesets/my_g<caret>ui.edds\"; }")
        val target = resolveAtCaret()
        // By name: the platform's own `EP_NAME` constant is internal API.
        val provider = ExtensionPointName.create<PsiDocumentationTargetProvider>(
            "com.intellij.platform.backend.documentation.psiTargetProvider",
        ).extensionList.single { it is EnTextureDocumentationTargetProvider }
        assertNotNull(provider.documentationTarget(target, null))
        // Anything else is left to the platform.
        assertNull(provider.documentationTarget(myFixture.file, null))
    }

    /**
     * When a reference resolves to more than one element, the platform hands the provider the
     * string literal instead. The provider then resolves the literal's own references.
     */
    fun testLiteralItselfLeadsToTheImage() {
        addAtlas()
        addImageSet()
        myFixture.configureByText(
            "hud.layout",
            "ImageWidgetClass Icon {\n image0 \"set:my_gui image:Cor<caret>ner\"\n}\n",
        )
        val literal = PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset),
            com.intellij.psi.ContributedReferenceHost::class.java,
        )!!
        val target = EnTextureDocs.targetInLiteral(literal)
        assertTrue(target is EnObjDeclMixin && EnImageSetTextures.isImageDef(target))
        assertNotNull(EnTextureDocumentationTargetProvider().documentationTarget(literal, literal))
    }

    fun testHoverOnTexturePathShowsTheImage() {
        addAtlas()
        myFixture.configureByText("hud.es", "class Hud { string m_Tex = \"MyMod/GUI/imagesets/my_g<caret>ui.edds\"; }")
        val rendered = EnTextureDocs.render(resolveAtCaret())!!
        assertTrue(rendered.html, rendered.html.contains("8 × 8 · BGRA8 · 4 mips · EDDS"))
        // The popup finds the image by parsing `src` as a URL and looking up its external form: a
        // key that is not an absolute URL, or does not survive that round trip, is a broken image.
        val src = Regex("""<img src="([^"]+)"""").find(rendered.html)!!.groupValues[1]
        assertEquals(rendered.images.keys.single(), java.net.URI(src).toURL().toExternalForm())
        val image = rendered.images.values.single() as BufferedImage
        // A tiny texture is enlarged by a whole factor, at most 8, so its pixels stay sharp instead of
        // being blown up to the full 192-pixel box as a blur.
        assertEquals(64, image.width)
        assertEquals(topLeft, image.getRGB(0, 0))
        assertEquals(topLeft, image.getRGB(31, 31))
        assertEquals(bottomRight, image.getRGB(63, 63))
    }

    fun testHoverOnImageSetImageShowsOnlyThatImage() {
        addAtlas()
        addImageSet()
        myFixture.configureByText("hud.es", "class Hud { string m_Icon = \"set:my_gui image:Cor<caret>ner\"; }")
        val def = resolveAtCaret() as EnObjDeclMixin

        val entry = EnImageSetTextures.entryFor(def)!!
        assertEquals("my_gui.edds", entry.texture.name)

        val rendered = EnTextureDocs.render(def)!!
        assertTrue(rendered.html, rendered.html.contains("4 × 4 at 4, 0"))
        val image = rendered.images.values.single() as BufferedImage
        // Pos 4 0 / Size 4 4 is the top-right quadrant of the 8-pixel texture.
        assertEquals(topRight, image.getRGB(0, 0))
        assertEquals(topRight, image.getRGB(image.width - 1, image.height - 1))
    }

    /** The highest `mpix` that resolves wins: it has the most detail. */
    fun testHighestResolutionTextureIsPreferred() {
        addAtlas()
        val big = myFixture.tempDirFixture.createFile("MyMod/GUI/imagesets/my_gui@2x.edds")
        runWriteAction { big.setBinaryContent(TextureBytes.eddsBgra(16, 16) { _, _ -> bottomLeft }) }
        myFixture.addFileToProject(
            "MyMod/GUI/imagesets/two.imageset",
            """
            ImageSetClass {
             Name "two"
             RefSize 16 16
             Textures {
              ImageSetTextureClass {
               mpix 0
               path "MyMod/GUI/imagesets/my_gui.edds"
              }
              ImageSetTextureClass {
               mpix 1
               path "MyMod/GUI/imagesets/my_gui@2x.edds"
              }
             }
             Images {
              ImageSetDefClass Dot {
               Pos 0 0
               Size 4 4
              }
             }
            }
            """.trimIndent(),
        )
        val def = PsiTreeUtil.findChildrenOfType(
            psiManager.findFile(myFixture.findFileInTempDir("MyMod/GUI/imagesets/two.imageset"))!!,
            EnObjDeclMixin::class.java,
        ).single { EnImageSetTextures.isImageDef(it) }
        assertEquals("my_gui@2x.edds", EnImageSetTextures.entryFor(def)!!.texture.name)
    }

    /**
     * The platform keeps files over 2.5 MB out of the file name index, and a 1024² BGRA atlas is
     * 4 MB. Both the path and the imageset entry must still find it.
     */
    fun testTextureTooLargeForTheNameIndexStillResolves() {
        val file = myFixture.tempDirFixture.createFile("MyMod/GUI/imagesets/my_gui.edds")
        runWriteAction {
            file.setBinaryContent(TextureBytes.eddsBgra(1024, 1024) { x, _ -> if (x < 512) topLeft else topRight })
        }
        assertTrue(file.length > 2_500_000)
        addImageSet(1024)

        myFixture.configureByText("hud.es", "class Hud { string m_Tex = \"{0123456789ABCDEF}MyMod/GUI/imagesets/my_g<caret>ui.edds\"; }")
        assertEquals(file, (resolveAtCaret() as com.intellij.psi.PsiFile).virtualFile)

        myFixture.configureByText("hud.es", "class Hud { string m_Icon = \"set:my_gui image:Cor<caret>ner\"; }")
        val rendered = EnTextureDocs.render(resolveAtCaret())!!
        assertEquals(topRight, (rendered.images.values.single() as BufferedImage).getRGB(0, 0))
    }

    fun testUnsupportedTextureSaysSoInsteadOfAnImage() {
        val file = myFixture.tempDirFixture.createFile("MyMod/GUI/fonts/font.edds")
        runWriteAction { file.setBinaryContent(TextureBytes.eddsBc7(8, 8)) }
        val rendered = EnTextureDocs.render(psiManager.findFile(file)!!)!!
        assertTrue(rendered.html, rendered.html.contains("BC7 textures cannot be previewed yet"))
        assertTrue(rendered.images.isEmpty())
    }

    fun testPaaPreviewToo() {
        val file = myFixture.tempDirFixture.createFile("MyMod/Data/logo.paa")
        runWriteAction {
            file.setBinaryContent(TextureBytes.paaDxt1(8, 8, TextureBytes.bc1Block(0xF800, 0x001F), lzo = true))
        }
        val rendered = EnTextureDocs.render(psiManager.findFile(file)!!)!!
        assertTrue(rendered.html, rendered.html.contains("DXT1"))
        assertEquals(1, rendered.images.size)
    }
}
