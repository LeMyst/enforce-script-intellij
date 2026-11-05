package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Completion inside a string literal.
 *
 * It is driven entirely by `getVariants()` on the references, which the platform's own
 * `LegacyCompletionContributor` collects for any language. This test exists to hold that
 * arrangement in place: the plugin's own completion contributors handle identifiers and
 * directives only, never a string, so if that platform behaviour ever stops applying the feature
 * would vanish with no other signal.
 */
class EnfusionStringCompletionTest : BasePlatformTestCase() {

    /** Widget names offered are those of the layouts the enclosing class loads — not all of them. */
    fun testWidgetNamesAreCompletedFromTheClassLayouts() {
        myFixture.addFileToProject(
            "Party/GUI/layouts/party_hud.layout",
            """
            FrameWidgetClass PartyRoot {
             {
              PanelWidgetClass PartyRows {
               size 1 1
              }
             }
            }
            """.trimIndent(),
        )
        // A widget in a layout this class never loads must not be offered.
        myFixture.addFileToProject(
            "Other/GUI/layouts/unrelated.layout",
            "PanelWidgetClass UnrelatedWidget {\n visible 1\n}",
        )
        myFixture.configureByText(
            "hud.es",
            """
            class PartyHud
            {
            	void Load()
            	{
            		m_Root = GetGame().GetWorkspace().CreateWidgets("Party/GUI/layouts/party_hud.layout");
            		m_Rows = m_Root.FindAnyWidget("<caret>");
            	}
            }
            """.trimIndent(),
        )
        val offered = myFixture.getCompletionVariants("hud.es").orEmpty()
        assertContainsElements(offered, "PartyRoot", "PartyRows")
        assertDoesntContain(offered, "UnrelatedWidget")
    }

    /** Dispatch names are offered from the inferred target's methods. */
    fun testMethodNamesAreCompletedFromTheDispatchTarget() {
        myFixture.configureByText(
            "rpc.es",
            """
            class BattleRoyaleRPC
            {
            	void BattleRoyaleRPC() { GetRPCManager().AddRPC(NS, "<caret>", this); }

            	void SetPlayerCount() {}
            	void SetCountdownSeconds() {}
            }
            """.trimIndent(),
        )
        val offered = myFixture.getCompletionVariants("rpc.es").orEmpty()
        assertContainsElements(offered, "SetPlayerCount", "SetCountdownSeconds")
    }

    /** The image half is scoped by whatever the set half resolved to. */
    fun testImageNamesAreCompletedWithinTheResolvedSet() {
        myFixture.addFileToProject(
            "GUI/imagesets/dayzbr_gui.imageset",
            """
            ImageSetClass {
             Name "battleroyale_gui"
             Images {
              ImageSetDefClass DayZBRLogo {
               Name "DayZBRLogo"
              }
              ImageSetDefClass DayZBRLogo_White {
               Name "DayZBRLogo_White"
              }
             }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "GUI/imagesets/other.imageset",
            """
            ImageSetClass {
             Name "other_gui"
             Images {
              ImageSetDefClass NotOurs {
               Name "NotOurs"
              }
             }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "hud.es",
            """
            class Hud { void Show() { icon.SetImage("set:battleroyale_gui image:<caret>"); } }
            """.trimIndent(),
        )
        val offered = myFixture.getCompletionVariants("hud.es").orEmpty()
        assertContainsElements(offered, "DayZBRLogo", "DayZBRLogo_White")
        assertDoesntContain(offered, "NotOurs")
    }
}
