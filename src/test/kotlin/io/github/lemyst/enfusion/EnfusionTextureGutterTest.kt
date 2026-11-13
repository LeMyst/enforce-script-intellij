package io.github.lemyst.enfusion

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import io.github.lemyst.enfusion.texture.EnImageSetTextures
import io.github.lemyst.enfusion.texture.EnTextureMarkers

/**
 * Texture thumbnails in the gutter, in each of the three languages and in the imageset itself.
 *
 * The atlas is 8×8 with four coloured quadrants, so a crop is checked by its colour alone.
 */
class EnfusionTextureGutterTest : BasePlatformTestCase() {

    private val topLeft = 0xFFFF0000.toInt()
    private val topRight = 0xFF00FF00.toInt()

    override fun setUp() {
        super.setUp()
        val atlas = myFixture.tempDirFixture.createFile("MyMod/GUI/imagesets/my_gui.edds")
        runWriteAction {
            atlas.setBinaryContent(TextureBytes.eddsBgra(8, 8, mips = 4) { x, y -> if (x < 4 && y < 4) topLeft else topRight })
        }
        myFixture.addFileToProject(
            "MyMod/GUI/imagesets/my_gui.imageset",
            """
            ImageSetClass {
             Name "my_gui"
             RefSize 8 8
             Textures {
              ImageSetTextureClass {
               mpix 1
               path "{0123456789ABCDEF}MyMod/GUI/imagesets/my_gui.edds"
              }
             }
             Images {
              ImageSetDefClass Corner {
               Name "Corner"
               Pos 4 0
               Size 4 4
              }
             }
            }
            """.trimIndent(),
        )
    }

    private fun gutterTooltips(): List<String> =
        myFixture.findAllGutters().filter { (it as? GutterIconRenderer)?.accessibleName == "Texture preview" }.mapNotNull { it.tooltipText }

    fun testIconIsTheCroppedImage() {
        val def = PsiTreeUtil.findChildrenOfType(
            psiManager.findFile(myFixture.findFileInTempDir("MyMod/GUI/imagesets/my_gui.imageset"))!!,
            EnObjDeclMixin::class.java,
        ).single { EnImageSetTextures.isImageDef(it) }
        val shown = EnTextureMarkers.preview(def, 32)!!
        assertEquals(topRight, shown.image.getRGB(0, 0))
        assertTrue(shown.tooltip, shown.tooltip.contains("Corner"))
    }

    fun testScript() {
        myFixture.configureByText(
            "hud.es",
            """
            class Hud
            {
            	string m_Icon = "set:my_gui image:Corner";
            	string m_Tex = "MyMod/GUI/imagesets/my_gui.edds";
            	string m_Name = "not a texture";
            }
            """.trimIndent(),
        )
        val tooltips = gutterTooltips()
        assertEquals(tooltips.toString(), 2, tooltips.size)
        assertTrue(tooltips.toString(), tooltips.any { it.contains("Corner") && it.contains("4 × 4 in my_gui.edds") })
        assertTrue(tooltips.toString(), tooltips.any { it.contains("my_gui.edds") && it.contains("BGRA8") })
    }

    fun testLayout() {
        myFixture.configureByText("hud.layout", "ImageWidgetClass Icon {\n image0 \"set:my_gui image:Corner\"\n}\n")
        assertTrue(gutterTooltips().toString(), gutterTooltips().single().contains("Corner"))
    }

    fun testImageSetItself() {
        myFixture.configureFromTempProjectFile("MyMod/GUI/imagesets/my_gui.imageset")
        val tooltips = gutterTooltips()
        // The image entry, and the texture path of the set: both lead to pixels.
        assertTrue(tooltips.toString(), tooltips.any { it.contains("Corner") })
        assertTrue(tooltips.toString(), tooltips.any { it.contains("my_gui.edds") && it.contains("BGRA8") })
    }

    /** Several textures on one line still make one icon: stacked icons would be noise. */
    fun testConfigOneIconPerLine() {
        myFixture.addFileToProject("MyMod/Data/a.paa", "").virtualFile.let { a ->
            runWriteAction { a.setBinaryContent(TextureBytes.paaDxt1(8, 8, TextureBytes.bc1Block(0xF800, 0x001F), lzo = true)) }
        }
        myFixture.configureByText(
            "config.cpp",
            """
            class CfgVehicles
            {
            	class MyItem
            	{
            		picture = "MyMod/Data/a.paa";
            		hiddenSelectionsTextures[] = {"MyMod/GUI/imagesets/my_gui.edds", "MyMod/Data/a.paa"};
            	};
            };
            """.trimIndent(),
        )
        val tooltips = gutterTooltips()
        assertEquals(tooltips.toString(), 2, tooltips.size)
    }

    /**
     * Clicking goes to the target. Tested on an imageset image: the test editor manager opens only
     * text editors, so it cannot follow a click to the `.edds` itself.
     */
    fun testClickGoesToTheImageSetEntry() {
        myFixture.configureByText("hud.es", "class Hud { string m_Icon = \"set:my_gui image:Corner\"; }")
        val gutter = myFixture.findAllGutters().single { (it as? GutterIconRenderer)?.accessibleName == "Texture preview" }
        @Suppress("UNCHECKED_CAST")
        val info = (gutter as LineMarkerInfo.LineMarkerGutterIconRenderer<*>).lineMarkerInfo as LineMarkerInfo<com.intellij.psi.PsiElement>
        info.navigationHandler!!.navigate(null, info.element!!)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("my_gui.imageset", editor.virtualFile.name)
        val line = editor.document.getLineNumber(editor.caretModel.offset)
        assertTrue(editor.document.text.lines()[line], editor.document.text.lines()[line].contains("Corner"))
    }

    fun testUnsupportedTextureHasNoIcon() {
        val font = myFixture.tempDirFixture.createFile("MyMod/GUI/fonts/font.edds")
        runWriteAction { font.setBinaryContent(TextureBytes.eddsBc7(8, 8)) }
        myFixture.configureByText("hud.es", "class Hud { string m_Font = \"MyMod/GUI/fonts/font.edds\"; }")
        assertTrue(gutterTooltips().isEmpty())
    }
}
