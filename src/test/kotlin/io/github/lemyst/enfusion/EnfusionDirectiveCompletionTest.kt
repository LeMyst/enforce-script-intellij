package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Completion for the flag in `#ifdef`, and specifically the half that needs `config.cpp`.
 *
 * A mod's own flags exist nowhere but a `defines[]` array. Until the config language existed the
 * plugin could not know `DAYZ_BATTLEROYALE` was a flag at all, and a mistyped one is silent in both
 * directions — the guarded code simply never compiles in, with nothing reported at build time or
 * after.
 */
class EnfusionDirectiveCompletionTest : BasePlatformTestCase() {

    private fun completionsAfter(prefix: String, vararg extraFiles: Pair<String, String>): List<String> {
        extraFiles.forEach { (name, text) -> myFixture.addFileToProject(name, text) }
        myFixture.configureByText("a.es", "$prefix<caret>\nclass Foo\n{\n}\n")
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private val modConfig = "config.cpp" to
        """
        class CfgMods
        {
            class DZ_BattleRoyale_Client
            {
                defines[]=
                {
                    "DAYZ_BATTLEROYALE",
                    "BR_PARTY"
                };
            };
        };
        """.trimIndent()

    fun testProjectDefinesAreOffered() {
        val offered = completionsAfter("#ifdef ", modConfig)
        assertTrue("project defines missing from $offered", offered.containsAll(listOf("DAYZ_BATTLEROYALE", "BR_PARTY")))
    }

    fun testEngineDefinesAreOfferedToo() {
        val offered = completionsAfter("#ifdef ", modConfig)
        assertTrue("engine defines missing", offered.contains("SERVER"))
        assertTrue("engine defines missing", offered.contains("DIAG"))
    }

    fun testPartialFlagFiltersTheList() {
        val offered = completionsAfter("#ifdef DAYZ_", modConfig)
        assertTrue("expected DAYZ_BATTLEROYALE in $offered", offered.contains("DAYZ_BATTLEROYALE"))
        assertFalse("SERVER should not match the prefix DAYZ_", offered.contains("SERVER"))
    }

    fun testIfndefIsCoveredAsWell() {
        assertTrue(completionsAfter("#ifndef ", modConfig).contains("BR_PARTY"))
    }

    /**
     * A commented-out entry contributes nothing, which is not an accident worth relying on by
     * mistake: this mod parks unused flags as `//"BLUE_ZONE"` and they must not be offered as if
     * they were live.
     */
    fun testCommentedOutDefinesAreNotOffered() {
        val offered = completionsAfter(
            "#ifdef ",
            "config.cpp" to "class CfgMods\n{\n\tclass M\n\t{\n\t\tdefines[]=\n\t\t{\n\t\t\t\"LIVE_FLAG\",\n\t\t\t//\"BLUE_ZONE\"\n\t\t};\n\t};\n};\n",
        )
        assertTrue(offered.contains("LIVE_FLAG"))
        assertFalse("a commented-out flag is not declared", offered.contains("BLUE_ZONE"))
    }

    /** Nothing to do with directives, so the contributor must keep out of the way. */
    fun testOrdinaryCodeIsUnaffected() {
        myFixture.configureByText("a.es", "class Foo\n{\n\tvoid Bar()\n\t{\n\t\tint x<caret>\n\t}\n}\n")
        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings.orEmpty()
        assertFalse("directive flags leaked into ordinary completion: $offered", offered.contains("SERVER"))
    }
}
