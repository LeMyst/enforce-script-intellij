package io.github.lemyst.enfusion

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.colors.EnColorMath
import io.github.lemyst.enfusion.colors.EnColorProvider
import java.awt.Color

/** Colour swatches: each notation reads the right colour, and a picked one is written back in it. */
class EnfusionColorTest : BasePlatformTestCase() {

    private val provider = EnColorProvider()

    /** The colours of every leaf of [file], in order. */
    private fun colors(file: PsiFile): List<Pair<PsiElement, Color>> =
        PsiTreeUtil.collectElements(file) { it.firstChild == null }
            .mapNotNull { leaf -> provider.getColorFrom(leaf)?.let { leaf to it } }

    private fun pick(file: PsiFile, color: Color): String {
        val (leaf, _) = colors(file).single()
        WriteCommandAction.runWriteCommandAction(project) { provider.setColorTo(leaf, color) }
        return file.text
    }

    fun testLinearChannelsShownInSrgb() {
        // Workbench shows `color 0.141 0.141 0.141 1` as #696969.
        assertEquals(Color(0x69, 0x69, 0x69, 255), EnColorMath.fromLinear(0.141, 0.141, 0.141, 1.0))
        assertEquals("0.141 0.141 0.141 1", EnColorMath.toLinearText(Color(0x69, 0x69, 0x69), true))
        assertEquals("1 0 0", EnColorMath.toLinearText(Color.RED, false))
        assertEquals("0.5", EnColorMath.decimal(0.5, 3))
        assertEquals("0", EnColorMath.decimal(0.0001, 3))
    }

    fun testLayoutProperties() {
        val file = myFixture.configureByText(
            "a.layout",
            "FrameWidgetClass Root {\n color 0.141 0.141 0.141 1\n \"text color\" 1 1 0 1\n size 1 1\n}\n",
        )
        assertEquals(listOf(Color(0x69, 0x69, 0x69), Color(255, 255, 0)), colors(file).map { it.second })
    }

    fun testLayoutWriteBackIsLinear() {
        val file = myFixture.configureByText("a.layout", "FrameWidgetClass Root {\n color 1 1 1 0.5\n}\n")
        assertEquals("FrameWidgetClass Root {\n color 0.141 0.141 0.141 0.502\n}\n", pick(file, Color(0x69, 0x69, 0x69, 128)))
    }

    fun testScriptArgbAndArgbf() {
        val file = myFixture.configureByText(
            "a.es",
            "class A\n{\n\tvoid F()\n\t{\n\t\tint a = ARGB(255, 242, 38, 19);\n\t\tint b = ARGBF(1, 0, 0.5, 1);\n\t\tint c = ARGB(255, x, 0, 0);\n\t}\n}",
        )
        assertEquals(listOf(Color(242, 38, 19, 255), Color(0, 128, 255, 255)), colors(file).map { it.second })
    }

    fun testScriptWriteBackKeepsTheNotation() {
        val file = myFixture.configureByText("a.es", "int a = ARGB(255, 242, 38, 19);")
        assertEquals("int a = ARGB(128, 0, 128, 255);", pick(file, Color(0, 128, 255, 128)))
        val floats = myFixture.configureByText("b.es", "int b = ARGBF(1, 1, 1, 1);")
        assertEquals("int b = ARGBF(1, 0, 0.5, 1);", pick(floats, Color(0, 128, 255, 255)))
    }

    /**
     * The picker passes the swatch's original element on every change, though the first write may
     * have replaced it: the last colour picked is the one written, whatever the notation.
     */
    fun testEveryPickerChangeIsWritten() {
        val cases = listOf(
            Triple("a.styles", "<S><Style Color=\"4294967295\" /></S>", "<S><Style Color=\"4278255360\" /></S>"),
            Triple("b.es", "const int COLOR_RED = 0xFFF22613;", "const int COLOR_RED = 0xFF00FF00;"),
            Triple("c.es", "string t = \"#(argb,8,8,3)color(1,0,0,1,co)\";", "string t = \"#(argb,8,8,3)color(0,1,0,1,co)\";"),
            Triple("d.es", "int a = ARGB(255, 242, 38, 19);", "int a = ARGB(255, 0, 255, 0);"),
            Triple("e.layout", "PanelWidgetClass p {\n color 1 0 0 1\n}", "PanelWidgetClass p {\n color 0 1 0 1\n}"),
        )
        for ((name, text, expected) in cases) {
            val file = myFixture.configureByText(name, text)
            val (leaf, _) = colors(file).single()
            WriteCommandAction.runWriteCommandAction(project) {
                provider.setColorTo(leaf, Color(0, 0, 255, 255))
                provider.setColorTo(leaf, Color(0, 255, 0, 255))
            }
            assertEquals(name, expected, file.text)
        }
    }

    /** A fade's alpha is computed; the colour is still shown, opaque, and the alpha kept as written. */
    fun testComputedAlphaKeepsItsExpression() {
        val file = myFixture.configureByText("a.es", "int a = ARGB(m_Alpha * 255, 242, 199, 68);")
        assertEquals(listOf(Color(242, 199, 68, 255)), colors(file).map { it.second })
        assertEquals("int a = ARGB(m_Alpha * 255, 0, 128, 255);", pick(file, Color(0, 128, 255, 40)))
        val floats = myFixture.configureByText("b.es", "int b = ARGBF(a, 1, 1, 1);")
        assertEquals("int b = ARGBF(a, 0, 0.5, 1);", pick(floats, Color(0, 128, 255, 255)))
        val channel = myFixture.configureByText("c.es", "int c = ARGB(255, r, 199, 68);")
        assertEquals(emptyList<Color>(), colors(channel).map { it.second })
    }

    /** A colour constant gets a swatch; a hex flag or mask does not. */
    fun testHexColourConstants() {
        val file = myFixture.configureByText(
            "a.es",
            "const int COLOR_RED = 0xFFF22613;\nconst int MASK = 0xFFFFFFFF;\nconst int COLOR_SHORT = 0xFF;\nint colorVar = 0xFF00FF00;",
        )
        assertEquals(listOf(Color(0xF2, 0x26, 0x13, 0xFF)), colors(file).map { it.second })
        assertEquals("const int COLOR_RED = 0x8000FF00;\nconst int MASK = 0xFFFFFFFF;\nconst int COLOR_SHORT = 0xFF;\nint colorVar = 0xFF00FF00;",
            pick(file, Color(0, 255, 0, 128)))
    }

    fun testProceduralTextureInConfigAndScript() {
        val config = myFixture.configureByText(
            "config.cpp",
            "class CfgVehicles\n{\n\tclass A\n\t{\n\t\thiddenSelectionsTextures[]={\"#(argb,8,8,3)color(0.5,0.5,0.5,1,co)\"};\n\t};\n};",
        )
        assertEquals(listOf(Color(128, 128, 128, 255)), colors(config).map { it.second })
        assertTrue(pick(config, Color(255, 0, 0, 255)).contains("\"#(argb,8,8,3)color(1,0,0,1,co)\""))
        val script = myFixture.configureByText("a.es", "string t = \"#(argb,8,8,3)color(0,1,0,1.0,co)\";")
        assertEquals(listOf(Color(0, 255, 0, 255)), colors(script).map { it.second })
    }

    /** Opened by file name, as a user opens it: `*.styles` is registered as XML. */
    fun testStylesColour() {
        val file = myFixture.configureByText(
            "dayz.styles",
            "<WidgetStyles><Widget Name=\"TextWidget\"><Style Name=\"Normal\" Font=\"\" Color=\"4294967295\" /></Widget></WidgetStyles>",
        )
        assertEquals(XmlFileType.INSTANCE, file.fileType)
        assertEquals(listOf(Color(255, 255, 255, 255)), colors(file).map { it.second })
    }
}
