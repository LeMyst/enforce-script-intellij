package io.github.lemyst.enfusion

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.refs.StringTableEntry

/**
 * Navigation out of string literals.
 *
 * These tests are worth more than their size suggests: they are the only automated check that the
 * `ContributedReferenceHost` wiring in `EnLiteralExprMixin` actually reaches the provider registry.
 * Grammar-Kit PSI is not consulted through that registry by default, so a silent regression there
 * would leave every literal inert while every other test stayed green.
 */
class EnfusionStringReferenceTest : BasePlatformTestCase() {

    private val stringTable = """
        "Language","original","english"
        "STR_BR_COT_NAME","BATTLEROYALE MANAGER","BATTLEROYALE MANAGER"
        "STR_BR_WIN","WINNER","WINNER"
        "STR_BR_LOSE","ELIMINATED","ELIMINATED"
    """.trimIndent()

    private fun addStringTable(path: String = "LanguageCore/stringtable.csv") {
        myFixture.addFileToProject(path, stringTable)
    }

    private fun resolveAtCaret(): StringTableEntry? {
        val reference = myFixture.getReferenceAtCaretPosition() ?: return null
        return reference.resolve() as? StringTableEntry
    }

    /** The client form: a `#`-prefixed key handed to a widget. */
    fun testHashPrefixedKeyResolves() {
        addStringTable()
        myFixture.configureByText(
            "br.es",
            """
            class Hud
            {
            	void Show() { SetText("#STR_BR<caret>_WIN"); }
            }
            """.trimIndent(),
        )
        val entry = resolveAtCaret()
        assertNotNull("#STR_BR_WIN should resolve to a stringtable row", entry)
        assertEquals("STR_BR_WIN", entry!!.name)
    }

    /**
     * The server form: the bare key with no `#`, shipped over an RPC for the client to localize.
     * It has no sigil and no consistent enclosing call, which is why matching is by content.
     */
    fun testBareKeyResolves() {
        addStringTable()
        myFixture.configureByText(
            "br.es",
            """
            class State
            {
            	void Notify() { MessagePlayerUntranslated(player, "STR_BR<caret>_LOSE"); }
            }
            """.trimIndent(),
        )
        val entry = resolveAtCaret()
        assertNotNull("A bare STR_ key should resolve to a stringtable row", entry)
        assertEquals("STR_BR_LOSE", entry!!.name)
    }

    /** The reference range excludes the `#`, so the resolved key matches the CSV column exactly. */
    fun testHashIsNotPartOfTheKey() {
        addStringTable()
        myFixture.configureByText(
            "br.es",
            """
            class Hud { void Show() { SetText("#STR_BR<caret>_WIN"); } }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition()
        assertNotNull(reference)
        assertEquals("STR_BR_WIN", reference!!.canonicalText)
    }

    /** Navigation lands on the declaring row, not on the top of the file. */
    fun testResolvesToTheDeclaringRow() {
        addStringTable()
        myFixture.configureByText(
            "br.es",
            """
            class Hud { void Show() { SetText("#STR_BR<caret>_LOSE"); } }
            """.trimIndent(),
        )
        val entry = resolveAtCaret()
        assertNotNull(entry)
        // Header on line 0, COT_NAME on 1, WIN on 2, LOSE on 3.
        assertEquals(3, lineOf(entry!!))
    }

    /**
     * A key declared in two addons offers both. Every addon ships its own `stringtable.csv` and the
     * engine loads all of them, so picking one arbitrarily would hide a real duplicate.
     */
    fun testDuplicateKeyOffersEveryStringTable() {
        addStringTable()
        addStringTable("Party/stringtable.csv")
        myFixture.configureByText(
            "br.es",
            """
            class Hud { void Show() { SetText("#STR_BR<caret>_WIN"); } }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as? PsiPolyVariantReference
        assertNotNull(reference)
        assertEquals(2, reference!!.multiResolve(false).size)
    }

    /** A literal that is not a key gets no reference at all — no noise, no false Ctrl+Click. */
    fun testUnrelatedLiteralHasNoReference() {
        addStringTable()
        myFixture.configureByText(
            "br.es",
            """
            class Hud { void Show() { Print("just a m<caret>essage"); } }
            """.trimIndent(),
        )
        assertNull(myFixture.getReferenceAtCaretPosition())
    }

    /** An unknown key still produces a reference, but resolves to nothing rather than guessing. */
    fun testUnknownKeyResolvesToNothing() {
        addStringTable()
        myFixture.configureByText(
            "br.es",
            """
            class Hud { void Show() { SetText("#STR_BR<caret>_MISSING"); } }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition()
        assertNotNull("The literal should still be recognised as a key", reference)
        assertNull(reference!!.resolve())
    }

    // ---- asset paths -------------------------------------------------------------------------

    /** The PBO-prefixed form, written whole. */
    fun testPboPrefixedLayoutPathResolves() {
        myFixture.addFileToProject("GUI/layouts/hud/br_hud.layout", "PanelWidgetClass Root {}")
        myFixture.configureByText(
            "br.es",
            """
            class Hud
            {
            	void Load() { CreateWidgets("BattleRoyale/GUI/layouts/hud/br<caret>_hud.layout"); }
            }
            """.trimIndent(),
        )
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertNotNull("A prefixed layout path should resolve", target)
        assertEquals("br_hud.layout", (target as com.intellij.psi.PsiFile).name)
    }

    /**
     * The concatenated form, which is how `Party/` and `Extra/KillFeed/` write every path. Folding
     * the constant is what supplies the leading segments that disambiguate the match.
     */
    fun testConcatenatedLayoutPathResolves() {
        myFixture.addFileToProject("Party/GUI/layouts/party_hud.layout", "PanelWidgetClass Root {}")
        myFixture.configureByText(
            "br.es",
            """
            const string BR_PARTY_PREFIX = "BattleRoyale/Party/";

            class PartyHud
            {
            	void Load() { CreateWidgets(BR_PARTY_PREFIX + "GUI/layouts/party<caret>_hud.layout"); }
            }
            """.trimIndent(),
        )
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertNotNull("A concatenated layout path should resolve", target)
        assertEquals("party_hud.layout", (target as com.intellij.psi.PsiFile).name)
    }

    /**
     * Two addons ship a row layout of the same name — the common case in this codebase. The path
     * segments in front of the file name are what separate them, so the longest suffix wins rather
     * than the first hit.
     */
    fun testLongestSuffixWinsBetweenSameNamedLayouts() {
        myFixture.addFileToProject("Party/GUI/layouts/row.layout", "PanelWidgetClass A {}")
        myFixture.addFileToProject("Extra/KillFeed/GUI/layouts/row.layout", "PanelWidgetClass B {}")
        myFixture.configureByText(
            "br.es",
            """
            class Feed
            {
            	void Load() { CreateWidgets("BattleRoyale/Extra/KillFeed/GUI/layouts/r<caret>ow.layout"); }
            }
            """.trimIndent(),
        )
        val target = myFixture.getReferenceAtCaretPosition()?.resolve() as? com.intellij.psi.PsiFile
        assertNotNull("The layout should resolve to exactly one file", target)
        assertTrue(
            "Expected the KillFeed copy, got ${target!!.virtualFile.path}",
            target.virtualFile.path.contains("KillFeed"),
        )
    }

    /** A message that merely mentions a file name is not a path reference. */
    fun testPlainMessageIsNotAPath() {
        myFixture.configureByText(
            "br.es",
            """
            class Feed { void Load() { Print("Could not create killf<caret>eed row"); } }
            """.trimIndent(),
        )
        assertNull(myFixture.getReferenceAtCaretPosition())
    }

    private fun lineOf(entry: StringTableEntry): Int {
        val field = StringTableEntry::class.java.getDeclaredField("line")
        field.isAccessible = true
        return field.getInt(entry)
    }
}
