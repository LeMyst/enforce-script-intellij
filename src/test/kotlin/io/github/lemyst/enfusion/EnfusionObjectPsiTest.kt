package io.github.lemyst.enfusion

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjTree

/**
 * The shapes in the object format that the grammar could plausibly get wrong.
 *
 * The corpus test proves 254 real files parse; these pin down *what* they parsed into, which is
 * what the widget and imageset references depend on.
 */
class EnfusionObjectPsiTest : BasePlatformTestCase() {

    private fun parse(name: String, text: String): com.intellij.psi.PsiFile {
        val file = myFixture.configureByText(name, text)
        assertNull(
            "unexpected parse error",
            PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java),
        )
        return file
    }

    private fun declarations(text: String, name: String = "a.layout"): List<EnObjDeclMixin> =
        PsiTreeUtil.findChildrenOfType(parse(name, text), EnObjDeclMixin::class.java).toList()

    /**
     * The central ambiguity: `PanelWidgetClass RowRoot` and `halign center_ref` are the same two
     * identifiers, and only the brace that follows separates a declaration from a property.
     */
    fun testTwoIdentifiersAreADeclarationOnlyBeforeABrace() {
        val decls = declarations(
            """
            PanelWidgetClass RowRoot {
             halign center_ref
             valign center
             mode blend
            }
            """.trimIndent(),
        )
        assertEquals(1, decls.size)
        assertEquals("RowRoot", decls[0].name)
        assertEquals("PanelWidgetClass", decls[0].getClassName())
    }

    /** Container blocks in an imageset carry no instance name. */
    fun testDeclarationWithoutAnInstanceName() {
        val decls = declarations(
            """
            ImageSetClass {
             Name "battleroyale_gui"
             Textures {
              ImageSetTextureClass {
               mpix 1
              }
             }
            }
            """.trimIndent(),
            "a.imageset",
        )
        val set = decls.first { it.getClassName() == "ImageSetClass" }
        assertNull("an ImageSetClass has no instance name", set.name)
        // Addressed by its `Name` property — `"set:battleroyale_gui …"` never names the file.
        assertEquals("battleroyale_gui", set.declaredName())
    }

    /** Children hang off an anonymous `{ … }` that names nothing and must be seen through. */
    fun testChildrenAreReachedThroughTheAnonymousBlock() {
        val file = parse(
            "a.layout",
            """
            FrameWidgetClass Root {
             position 0 0
             {
              PanelWidgetClass Rows {
               size 700 240
              }
              TextWidgetClass Label {
               text "#STR_BR_WIN"
              }
             }
            }
            """.trimIndent(),
        )
        val root = EnObjTree.objectChildrenOf(file).single()
        assertEquals("Root", root.name)
        assertEquals(listOf("Rows", "Label"), EnObjTree.objectChildrenOf(root).map { it.name })
    }

    /** A quoted property key is real: `"Size To Content V" 1`, `"no focus" 1`. */
    fun testQuotedPropertyKey() {
        val decls = declarations(
            """
            ScrollWidgetClass Scroller {
             "Scrollbar V" 1
             "Size To Content V" 1
             vscroll 1
            }
            """.trimIndent(),
        )
        assertEquals("Scroller", decls.single().name)
        assertEquals("1", decls.single().propertyValue("Size To Content V"))
    }

    /** Negative and fractional numbers are one value each, not a sign plus a number. */
    fun testSignedNumbersAreSingleValues() {
        val decls = declarations(
            """
            ImageWidgetClass Icon {
             position -10 -0.5
             size 1 1
            }
            """.trimIndent(),
        )
        assertEquals("-10", decls.single().propertyValue("position"))
    }

    /**
     * Hand-written layouts carry `//` comments; vanilla's machine-written ones never do, so this is
     * the only place the comment rules are exercised outside the mod checkout.
     */
    fun testLineCommentsAreIgnored() {
        val decls = declarations(
            """
            // Row geometry is overwritten from script; these are placeholders.
            PanelWidgetClass Row {
             // the icons hang directly off Row
             size 100 20 // measured at runtime
            }
            """.trimIndent(),
        )
        assertEquals("Row", decls.single().name)
        assertEquals("100", decls.single().propertyValue("size"))
    }

    /**
     * `Name` is read from direct children only. An `ImageSetClass` and every `ImageSetDefClass`
     * under it all carry one, and reading through the subtree would give the set the first image's
     * name.
     */
    fun testNameIsReadFromDirectChildrenOnly() {
        val decls = declarations(
            """
            ImageSetClass {
             Name "battleroyale_gui"
             Images {
              ImageSetDefClass DayZBRLogo {
               Name "DayZBRLogo"
              }
             }
            }
            """.trimIndent(),
            "a.imageset",
        )
        assertEquals("battleroyale_gui", decls.first { it.getClassName() == "ImageSetClass" }.declaredName())
        assertEquals("DayZBRLogo", decls.first { it.getClassName() == "ImageSetDefClass" }.declaredName())
    }

    /**
     * The GUID form shipped in ten vanilla layouts (gui/layouts/debug/day_z_debug_monitor.layout):
     * the instance slot holds a quoted resource GUID, and the name script looks the widget up by is
     * the `Name` property. Mixed with the plain form in the same tree.
     */
    fun testGuidNamedWidgetIsNamedByItsNameProperty() {
        val decls = declarations(
            """
            FrameWidgetClass "{00000000EE70746B}" {
             Name "HudDebugFrameWidget"
             visible 1
             {
              TextWidgetClass "{00000000AB12CD34}" {
               Name "DebugText"
              }
              PanelWidgetClass Plain {
              }
             }
            }
            """.trimIndent(),
        )
        val root = decls.first { it.getClassName() == "FrameWidgetClass" }
        assertTrue(root.isGuidNamed())
        assertEquals("HudDebugFrameWidget", root.name)
        assertEquals(listOf("DebugText", "Plain"), EnObjTree.objectChildrenOf(root).map { it.name })
        assertFalse(decls.first { it.getClassName() == "PanelWidgetClass" }.isGuidNamed())
    }

    /** A file that is only comments and blank lines still parses to an empty tree. */
    fun testEmptyFile() {
        val decls = declarations("// nothing here yet\n\n")
        assertTrue(decls.isEmpty())
    }
}
