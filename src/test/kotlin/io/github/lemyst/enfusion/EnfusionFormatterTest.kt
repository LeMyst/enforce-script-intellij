package io.github.lemyst.enfusion

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.format.EnfusionCodeStyleSettings
import io.github.lemyst.enfusion.lang.EnfusionFileType
import java.io.File

/**
 * The formatter indents and does nothing else.
 *
 * The strongest check here is the last one: reformatting *vanilla* source, which is already written
 * to the conventions the settings default to, must produce byte-identical text. A formatter that
 * disagrees with the corpus about tabs, Allman braces or preprocessor indentation would rewrite
 * every file the first time someone pressed Ctrl+Alt+L, and this is what makes that impossible to
 * ship unnoticed.
 */
class EnfusionFormatterTest : BasePlatformTestCase() {

    private fun reformat(text: String): String {
        myFixture.configureByText("a.es", text)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        return myFixture.file.text
    }

    fun testIndentsClassAndMethodBodiesWithTabs() {
        val formatted = reformat(
            """
            class Foo
            {
            void Bar()
            {
            int x = 1;
            }
            }
            """.trimIndent(),
        )
        assertEquals(
            "class Foo\n{\n\tvoid Bar()\n\t{\n\t\tint x = 1;\n\t}\n}",
            formatted,
        )
    }

    /**
     * Tabs are only the *default*, never a lock: unticking "Use tab character" in
     * Settings | Editor | Code Style | EnfusionScript | Tabs and Indents yields spaces.
     *
     * This works because the block model asks for [com.intellij.formatting.Indent.getNormalIndent]
     * and lets the engine render it from the user's indent options, rather than emitting whitespace
     * itself.
     */
    fun testIndentCharacterFollowsTheUserSetting() {
        val options = CodeStyle.getSettings(project).getIndentOptions(EnfusionFileType)
        val wasTabs = options.USE_TAB_CHARACTER
        val wasIndent = options.INDENT_SIZE
        try {
            options.USE_TAB_CHARACTER = false
            options.INDENT_SIZE = 4
            val formatted = reformat("class Foo\n{\nint x;\n}")
            assertEquals("class Foo\n{\n    int x;\n}", formatted)
        } finally {
            options.USE_TAB_CHARACTER = wasTabs
            options.INDENT_SIZE = wasIndent
        }
    }

    /** Already-correct input is a fixed point -- formatting twice changes nothing. */
    fun testCorrectlyIndentedCodeIsUnchanged() {
        val source = "class Foo\n{\n\tvoid Bar()\n\t{\n\t\tint x = 1;\n\t}\n}"
        assertEquals(source, reformat(source))
    }

    /**
     * `switch` and `enum` keywords are children of an indenting rule, so indenting by parent type
     * alone would push them one level deeper on every single format.
     */
    fun testSwitchAndEnumKeywordsAreNotIndented() {
        val source = "enum Foo\n{\n\tA,\n\tB\n}"
        assertEquals(source, reformat(source))

        val withSwitch = "class Foo\n{\n\tvoid Bar()\n\t{\n\t\tswitch (x)\n\t\t{\n\t\t}\n\t}\n}"
        assertEquals(withSwitch, reformat(withSwitch))
    }

    /** Braces stay on their own line: no wrapping rules means no line breaks are touched. */
    fun testAllmanBracesAreNotJoined() {
        val source = "class Foo\n{\n\tvoid Bar()\n\t{\n\t}\n}"
        assertEquals(source, reformat(source))
    }

    /** Spacing is left exactly as written, however unusual. */
    fun testUnusualSpacingIsPreserved() {
        val source = "class Foo\n{\n\tvoid Bar( int  x , int y )\n\t{\n\t}\n}"
        assertEquals(source, reformat(source))
    }

    /**
     * Directives are pinned to the first column by default; the code inside the region is not.
     *
     * Vanilla writes it both ways — 143 indented against 29 at column 0 in `dayzgame.c` alone — so
     * the corpus does not settle this and it is a preference, not a fact. The default follows the
     * C-family convention, where a directive at column 0 reads as the compile-time boundary it is
     * rather than blending into the runtime control flow it wraps.
     */
    fun testDirectivesArePinnedToTheFirstColumn() {
        val formatted = reformat("class Foo\n{\n\tvoid Bar()\n\t{\n\t\t#ifdef SERVER\n\t\tint x = 1;\n\t\t#endif\n\t}\n}")
        assertEquals("class Foo\n{\n\tvoid Bar()\n\t{\n#ifdef SERVER\n\t\tint x = 1;\n#endif\n\t}\n}", formatted)
    }

    /** And the other way round, for files that follow vanilla's other habit. */
    fun testDirectivesFollowTheCodeWhenTheOptionIsOff() {
        val custom = CodeStyle.getSettings(project).getCustomSettings(EnfusionCodeStyleSettings::class.java)
        val was = custom.PREPROCESSOR_AT_FIRST_COLUMN
        try {
            custom.PREPROCESSOR_AT_FIRST_COLUMN = false
            val formatted = reformat("class Foo\n{\n\tvoid Bar()\n\t{\n#ifdef SERVER\n\t\tint x = 1;\n#endif\n\t}\n}")
            assertEquals(
                "class Foo\n{\n\tvoid Bar()\n\t{\n\t\t#ifdef SERVER\n\t\tint x = 1;\n\t\t#endif\n\t}\n}",
                formatted,
            )
        } finally {
            custom.PREPROCESSOR_AT_FIRST_COLUMN = was
        }
    }

    /** A top-level directive was already at column 0 and must not move either way. */
    fun testTopLevelDirectiveStaysAtColumnZero() {
        val source = "#ifdef SERVER\nclass Foo\n{\n}\n#endif"
        assertEquals(source, reformat(source))
    }

    /**
     * The real gate, and note what it does *not* claim.
     *
     * Byte-identical round-tripping of vanilla is not achievable and asserting it was a mistake: the
     * formatting engine unconditionally rewrites alignment tabs *inside* a line as spaces and strips
     * trailing whitespace, and vanilla is not internally consistent anyway (`defines.c` indents a
     * top-level comment decoratively).
     *
     * What must hold is that formatting only ever changes *how much* whitespace there is -- never
     * which tokens sit on which line. Comparing each line with its whitespace runs collapsed states
     * exactly that, and still catches the failures that would matter: a line added or dropped, or
     * code joined, split or lost.
     *
     * Skips when the corpus is absent, exactly like the parse tests. A silent skip proves nothing,
     * so a green run without `P:` mounted is not evidence.
     */
    fun testFormattingNeverDamagesVanillaCode() {
        val root = System.getProperty("enfusion.corpus.root").orEmpty()
        if (root.isEmpty() || !File(root).isDirectory) {
            println("[format] corpus not present, skipping")
            return
        }
        val files = File(root).walkTopDown()
            .filter { it.isFile && it.extension.equals("c", ignoreCase = true) }
            .take(150)
            .toList()

        fun tokensPerLine(text: String) = text.lines().map { it.trim().replace(WHITESPACE_RUN, " ") }

        val damaged = ArrayList<String>()
        for (file in files) {
            val source = file.readText().replace("\r\n", "\n")
            val before = tokensPerLine(source)
            val after = tokensPerLine(reformat(source))
            if (before != after) {
                val at = before.indices.firstOrNull { it >= after.size || before[it] != after[it] }
                damaged += "${file.name}:${at?.plus(1)}"
            }
        }
        assertTrue(
            "formatting altered code in ${damaged.size} of ${files.size} vanilla files: " +
                damaged.take(3).joinToString(),
            damaged.isEmpty(),
        )
        println("[format] ${files.size} vanilla files kept every line intact")
    }

    private companion object {
        val WHITESPACE_RUN = Regex("\\s+")
    }
}
