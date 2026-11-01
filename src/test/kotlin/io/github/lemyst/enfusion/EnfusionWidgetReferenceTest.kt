package io.github.lemyst.enfusion

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin

/**
 * Navigation from a widget name and from an imageset reference into the `.layout` / `.imageset`
 * that declares it.
 */
class EnfusionWidgetReferenceTest : BasePlatformTestCase() {

    private val partyHud = """
        FrameWidgetClass PartyRoot {
         position 0 0
         {
          PanelWidgetClass PartyRows {
           size 700 240
          }
         }
        }
    """.trimIndent()

    private val partyRow = """
        PanelWidgetClass RowRoot {
         {
          ImageWidgetClass RowHealthIcon {
           mode blend
          }
          TextWidgetClass RowName {
           text "#STR_PARTY_NAME"
          }
         }
        }
    """.trimIndent()

    private fun addLayouts() {
        myFixture.addFileToProject("Party/GUI/layouts/party_hud.layout", partyHud)
        myFixture.addFileToProject("Party/GUI/layouts/party_hud_row.layout", partyRow)
    }

    private fun resolveAtCaret(): EnObjDeclMixin? =
        myFixture.getReferenceAtCaretPosition()?.resolve() as? EnObjDeclMixin

    /**
     * The core case: a widget looked up from the class that created the layout holding it. The row
     * layout is loaded by the same class, so both files are in scope.
     */
    fun testWidgetResolvesThroughTheLayoutTheClassCreates() {
        addLayouts()
        myFixture.configureByText(
            "hud.es",
            """
            class PartyHud
            {
            	void Load()
            	{
            		m_Root = GetGame().GetWorkspace().CreateWidgets("Party/GUI/layouts/party_hud.layout");
            		Widget row = GetGame().GetWorkspace().CreateWidgets("Party/GUI/layouts/party_hud_row.layout", m_Rows);
            		ImageWidget icon = ImageWidget.Cast(row.FindAnyWidget("RowHeal<caret>thIcon"));
            	}
            }
            """.trimIndent(),
        )
        val target = resolveAtCaret()
        assertNotNull("the widget should resolve into the row layout", target)
        assertEquals("RowHealthIcon", target!!.name)
        assertEquals("ImageWidgetClass", target.getClassName())
    }

    /** Widgets nested inside the anonymous children block are reachable, not just top-level ones. */
    fun testNestedWidgetResolves() {
        addLayouts()
        myFixture.configureByText(
            "hud.es",
            """
            class PartyHud
            {
            	void Load()
            	{
            		m_Root = GetGame().GetWorkspace().CreateWidgets("Party/GUI/layouts/party_hud.layout");
            		m_Rows = m_Root.FindAnyWidget("Party<caret>Rows");
            	}
            }
            """.trimIndent(),
        )
        assertEquals("PartyRows", resolveAtCaret()?.name)
    }

    /**
     * The path is assembled from a constant, as `Party/` really writes it. Folding has to happen
     * before the layout can be located at all.
     */
    fun testWidgetResolvesThroughAConcatenatedLayoutPath() {
        addLayouts()
        myFixture.configureByText(
            "hud.es",
            """
            const string BR_PARTY_PREFIX = "BattleRoyale/Party/";

            class PartyHud
            {
            	void Load()
            	{
            		m_Root = GetGame().GetWorkspace().CreateWidgets(BR_PARTY_PREFIX + "GUI/layouts/party_hud.layout");
            		m_Rows = m_Root.FindAnyWidget("Party<caret>Rows");
            	}
            }
            """.trimIndent(),
        )
        assertEquals("PartyRows", resolveAtCaret()?.name)
    }

    /**
     * A class that creates no layout still resolves, through the global index. Helper classes whose
     * widgets were built by a parent are the reason the fallback exists.
     */
    fun testGlobalFallbackWhenTheClassLoadsNoLayout() {
        addLayouts()
        myFixture.configureByText(
            "hud.es",
            """
            class RowHelper
            {
            	void Bind(Widget row) { row.FindAnyWidget("RowNa<caret>me"); }
            }
            """.trimIndent(),
        )
        assertEquals("RowName", resolveAtCaret()?.name)
    }

    /** A layout name mentioned in a message must not widen the scope of the search. */
    fun testWidgetOfAnUnknownNameResolvesToNothing() {
        addLayouts()
        myFixture.configureByText(
            "hud.es",
            """
            class PartyHud
            {
            	void Load()
            	{
            		m_Root = GetGame().GetWorkspace().CreateWidgets("Party/GUI/layouts/party_hud.layout");
            		m_Root.FindAnyWidget("NoSuch<caret>Widget");
            	}
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition()
        assertNotNull("the literal is still recognised as a widget name", reference)
        assertNull(reference!!.resolve())
    }

    // ---- imagesets ------------------------------------------------------------------------------

    private val imageSet = """
        ImageSetClass {
         Name "battleroyale_gui"
         Images {
          ImageSetDefClass DayZBRLogo_White {
           Name "DayZBRLogo_White"
           Pos 512 0
          }
         }
        }
    """.trimIndent()

    private fun addImageSet() {
        // Note the file name and the declared Name deliberately differ, as they do in the real mod.
        myFixture.addFileToProject("GUI/imagesets/dayzbr_gui.imageset", imageSet)
    }

    /** The set half resolves by the declared `Name`, never by the file name. */
    fun testImageSetHalfResolvesByDeclaredName() {
        addImageSet()
        myFixture.configureByText(
            "hud.es",
            """
            class Hud { void Show() { icon.LoadImageFile(0, "set:battler<caret>oyale_gui image:DayZBRLogo_White"); } }
            """.trimIndent(),
        )
        val target = resolveAtCaret()
        assertNotNull("the set should resolve", target)
        assertEquals("ImageSetClass", target!!.getClassName())
        assertEquals("battleroyale_gui", target.declaredName())
    }

    /** The image half is a second, independent reference over its own range. */
    fun testImageHalfResolvesSeparately() {
        addImageSet()
        myFixture.configureByText(
            "hud.es",
            """
            class Hud { void Show() { icon.LoadImageFile(0, "set:battleroyale_gui image:DayZBR<caret>Logo_White"); } }
            """.trimIndent(),
        )
        val target = resolveAtCaret()
        assertNotNull("the image should resolve", target)
        assertEquals("ImageSetDefClass", target!!.getClassName())
        assertEquals("DayZBRLogo_White", target.declaredName())
    }

    /** One literal, two references — the reason the literal is a ContributedReferenceHost. */
    fun testOneLiteralCarriesTwoReferences() {
        addImageSet()
        val file = myFixture.configureByText(
            "hud.es",
            """
            class Hud { void Show() { icon.LoadImageFile(0, "set:battleroyale_gui image:DayZBRLogo_White"); } }
            """.trimIndent(),
        )
        val literal = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(file, io.github.lemyst.enfusion.psi.impl.EnLiteralExprMixin::class.java)
            .first { it.text.contains("set:") }
        assertEquals(2, literal.references.size)
    }

    /** `"set:dayz_gui image:"` occurs in real code, with the image filled in from script. */
    fun testEmptyImageHalfYieldsOnlyTheSetReference() {
        addImageSet()
        val file = myFixture.configureByText(
            "hud.es",
            """
            class Hud { void Show() { icon.SetImage("set:battleroyale_gui image:"); } }
            """.trimIndent(),
        )
        val literal = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(file, io.github.lemyst.enfusion.psi.impl.EnLiteralExprMixin::class.java)
            .first { it.text.contains("set:") }
        assertEquals(1, literal.references.size)
    }

    /** Layout values are reference hosts too: `text "#STR_…"` and `image0 "set:… image:…"`. */
    fun testImageSetReferenceInsideALayout() {
        addImageSet()
        myFixture.configureByText(
            "row.layout",
            """
            ImageWidgetClass Icon {
             mode blend
             image0 "set:battleroyale_gui image:DayZBR<caret>Logo_White"
            }
            """.trimIndent(),
        )
        assertEquals("DayZBRLogo_White", resolveAtCaret()?.declaredName())
    }

    /** An unknown set resolves to nothing rather than to some other addon's imageset. */
    fun testUnknownImageSetResolvesToNothing() {
        addImageSet()
        myFixture.configureByText(
            "hud.es",
            """
            class Hud { void Show() { icon.SetImage("set:no_su<caret>ch_set image:Whatever"); } }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as? PsiPolyVariantReference
        assertNotNull(reference)
        assertEquals(0, reference!!.multiResolve(false).size)
    }
}
