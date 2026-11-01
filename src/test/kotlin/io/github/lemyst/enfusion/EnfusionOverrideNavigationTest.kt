package io.github.lemyst.enfusion

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * A method's own name identifier is a declaration, not a reference -- `getReferenceAtCaretPosition`
 * returns null for it by construction, same as any declaration in any language. What is being tested
 * here is the extra path that stands in for one:
 * [io.github.lemyst.enfusion.nav.EnOverrideGotoDeclarationHandler] for Ctrl+Click, and
 * [io.github.lemyst.enfusion.nav.EnOverrideLineMarkerProvider] for the gutter icon.
 */
class EnfusionOverrideNavigationTest : BasePlatformTestCase() {

    private fun targetsAtCaret(): List<EnMethodDeclMixin> =
        GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)
            .filterIsInstance<EnMethodDeclMixin>()

    /** `modded class` layering: the two declarations share a name but neither extends the other. */
    fun testOverrideInAModdedLayerGoesToTheOtherLayer() {
        myFixture.addFileToProject(
            "vanilla/dayzgame.es",
            """
            class DayZGame
            {
            	void ConnectFromCLI() {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "mod.es",
            """
            modded class DayZGame
            {
            	override void ConnectFrom<caret>CLI() {}
            }
            """.trimIndent(),
        )
        val targets = targetsAtCaret()
        assertEquals(1, targets.size)
        assertEquals("ConnectFromCLI", targets[0].name)
        assertEquals("dayzgame.es", targets[0].containingFile.name)
    }

    /** Ordinary inheritance: `override` resolves up the `extends` chain, reusing `findMembers`. */
    fun testOverrideThroughExtendsGoesToTheSuperclass() {
        myFixture.configureByText(
            "state.es",
            """
            class BattleRoyaleState
            {
            	void OnPlayerCountChanged() {}
            }

            class BattleRoyaleDebug extends BattleRoyaleState
            {
            	override void OnPlayerCount<caret>Changed() {}
            }
            """.trimIndent(),
        )
        val targets = targetsAtCaret()
        assertEquals(1, targets.size)
        val owner = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            targets[0], io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin::class.java,
        )
        assertEquals("BattleRoyaleState", owner?.name)
    }

    /** A method with no override anywhere resolves to nothing -- no gutter, no extra Ctrl+Click target. */
    fun testMethodWithNoOverrideHasNoTargets() {
        myFixture.configureByText(
            "state.es",
            """
            class BattleRoyaleState
            {
            	void OnPlayer<caret>CountChanged() {}
            }
            """.trimIndent(),
        )
        assertTrue(targetsAtCaret().isEmpty())
    }

    /** The gutter icon itself, not just the resolution behind it. */
    fun testGutterIconAppearsOnAnOverridingMethod() {
        myFixture.addFileToProject(
            "vanilla/dayzgame.es",
            """
            class DayZGame
            {
            	void ConnectFromCLI() {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "mod.es",
            """
            modded class DayZGame
            {
            	override void ConnectFromCLI() {}
            }
            """.trimIndent(),
        )
        myFixture.doHighlighting()
        val gutters = myFixture.findAllGutters()
        assertTrue(
            "expected an overriding-method gutter icon, got: ${gutters.map { it.tooltipText }}",
            gutters.any { it.tooltipText == "Go to overridden method" },
        )
    }
}
