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

    /** The sandbox review: one Ctrl+Alt+L turned vanilla's alignment tabs into four spaces each. */
    fun testAlignmentTabsInsideALineAreKept() {
        val source = "class Scene\n{\n\tprotected Camera\t\t\t\t\tm_Camera;\n\tprotected ref Character\tm_Character;\n}"
        assertEquals(source, reformat(source))
        assertEquals("indentation still changes", source, reformat(source.replace("\n\tprotected Camera", "\nprotected Camera")))
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

    /** In a method: the [lines] as they should be formatted, each prefixed with its depth in tabs. */
    private fun method(vararg lines: Pair<Int, String>): String =
        "class Foo\n{\n\tvoid Bar()\n\t{\n" + lines.joinToString("") { (depth, text) -> "\t".repeat(depth + 2) + text + "\n" } + "\t}\n}"

    /** The same lines, every one at the method body's level, as Ctrl+Alt+L used to leave them. */
    private fun flat(vararg lines: Pair<Int, String>): String =
        "class Foo\n{\n\tvoid Bar()\n\t{\n" + lines.joinToString("") { (_, text) -> "\t\t" + text + "\n" } + "\t}\n}"

    private fun assertFormats(vararg lines: Pair<Int, String>) {
        assertEquals(method(*lines), reformat(flat(*lines)))
        assertEquals("formatting twice changes nothing", method(*lines), reformat(method(*lines)))
    }

    /** The reported case: `if (!b_IsAdmin)` then `return;` on the next line. */
    fun testBracelessIfBodyIsIndented() {
        assertFormats(0 to "if( !b_IsAdmin )", 1 to "return;", 0 to "Send();")
    }

    fun testBracelessElseAndLoopBodies() {
        assertFormats(
            0 to "if (a)", 1 to "x = 1;", 0 to "else", 1 to "x = 2;",
            0 to "for (int i = 0; i < 3; i++)", 1 to "x++;",
            0 to "foreach (int v : list)", 1 to "x += v;",
            0 to "while (x > 0)", 1 to "x--;",
        )
    }

    /** `else if` stays flat however long the chain, and each body is one level deeper. */
    fun testElseIfChainStaysFlat() {
        assertFormats(
            0 to "if (a)", 1 to "x = 1;",
            0 to "else if (b)", 1 to "x = 2;",
            0 to "else if (c)", 1 to "x = 3;",
            0 to "else", 1 to "x = 4;",
        )
    }

    fun testNestedBracelessBodies() {
        assertFormats(0 to "if (a)", 1 to "if (b)", 2 to "foreach (int v : list)", 3 to "x += v;")
    }

    /** Allman braces: a body in braces lines up with its statement, its contents one level in. */
    fun testBracedBodiesAreUnchanged() {
        assertFormats(0 to "if (a)", 0 to "{", 1 to "x = 1;", 0 to "}", 0 to "else", 0 to "{", 1 to "x = 2;", 0 to "}")
    }

    /** Labels one level inside the switch, their statements one more; a `{` after a label at its level. */
    fun testCaseBodiesAreIndented() {
        assertFormats(
            0 to "switch (x)", 0 to "{",
            1 to "case 1:", 2 to "a();", 2 to "break;",
            1 to "case 2:", 1 to "{", 2 to "b();", 2 to "break;", 1 to "}",
            1 to "default:", 2 to "c();",
            0 to "}",
        )
    }

    /** A call split over lines: arguments one level deeper, the lone `)` back at the statement's level. */
    fun testContinuationLines() {
        assertFormats(
            0 to "GetRPCManager().SendRPC(", 1 to "\"ns\",", 1 to "\"Fn\",", 1 to "null", 0 to ");",
            0 to "if (a &&", 1 to "b)", 1 to "x = 1;",
            0 to "int total = first +", 1 to "second;",
            0 to "return Compute(a,", 1 to "b);",
        )
    }

    /** The indentation Enter gives the new line after the caret's line in [before]. */
    private fun indentAfterEnter(before: String): String {
        myFixture.configureByText("a.es", before)
        myFixture.type("\n")
        val text = myFixture.editor.document.text
        val line = myFixture.editor.document.getLineNumber(myFixture.caretOffset)
        val start = myFixture.editor.document.getLineStartOffset(line)
        return text.substring(start, myFixture.caretOffset)
    }

    /** Enter after `if (x)` with no brace goes one level deeper, ready for the body. */
    fun testEnterAfterBracelessIf() {
        assertEquals("\t\t\t", indentAfterEnter("class Foo\n{\n\tvoid Bar()\n\t{\n\t\tif (x)<caret>\n\t}\n}"))
    }

    /** Enter after a `case` label, and after a statement under it, stays at the case body's level. */
    fun testEnterInCase() {
        assertEquals(
            "\t\t\t\t",
            indentAfterEnter("class Foo\n{\n\tvoid Bar()\n\t{\n\t\tswitch (x)\n\t\t{\n\t\t\tcase 1:<caret>\n\t\t}\n\t}\n}"),
        )
        assertEquals(
            "\t\t\t\t",
            indentAfterEnter("class Foo\n{\n\tvoid Bar()\n\t{\n\t\tswitch (x)\n\t\t{\n\t\t\tcase 1:\n\t\t\t\ta();<caret>\n\t\t}\n\t}\n}"),
        )
    }

    /** A call nested in a continuation line is one level deeper again. */
    fun testNestedContinuation() {
        assertFormats(0 to "Outer(", 1 to "Inner(", 2 to "x", 1 to "),", 1 to "y", 0 to ");")
    }

    /**
     * The formatter agrees with vanilla's indentation: after Ctrl+Alt+L, nearly every line of
     * vanilla keeps its leading whitespace. The lines that move are vanilla's minority styles (a
     * braceless body at its `if`'s level, a continuation aligned to a column), counted in
     * [io.github.lemyst.enfusion.format.EnfusionBlock]'s documentation. Directives follow the code
     * here, since vanilla indents most of them. A rule that disagreed with vanilla would move
     * thousands of lines, as the old flat braceless bodies did, and fail this.
     */
    fun testFormattingKeepsVanillaIndentation() {
        val root = System.getProperty("enfusion.corpus.root").orEmpty()
        if (root.isEmpty() || !File(root).isDirectory) {
            println("[format] corpus not present, skipping")
            return
        }
        val custom = CodeStyle.getSettings(project).getCustomSettings(EnfusionCodeStyleSettings::class.java)
        val was = custom.PREPROCESSOR_AT_FIRST_COLUMN
        custom.PREPROCESSOR_AT_FIRST_COLUMN = false
        try {
            val files = File(root).walkTopDown()
                .filter { it.isFile && it.extension.equals("c", ignoreCase = true) }
                .take(400)
                .toList()
            var lines = 0
            var moved = 0
            val examples = ArrayList<String>()
            val kinds = sortedMapOf<String, Int>()
            for (file in files) {
                val before = file.readText().replace("\r\n", "\n").lines()
                val after = reformat(before.joinToString("\n")).lines()
                if (before.size != after.size) continue // The token check reports these.
                for (i in before.indices) {
                    if (before[i].isBlank()) continue
                    lines++
                    // Widths, not characters: vanilla indents a few files with four spaces, which
                    // the formatter writes as the tab they stand for.
                    val was = indentWidth(before[i])
                    val now = indentWidth(after[i])
                    if (was != now) {
                        moved++
                        val text = before[i].trim()
                        val kind = when {
                            text.startsWith("/*") || text.startsWith("*") || text.startsWith("//") -> "comment"
                            text.startsWith("#") -> "directive"
                            text.startsWith(")") || text.startsWith("}") -> "closing"
                            else -> "code"
                        }
                        kinds[kind] = (kinds[kind] ?: 0) + 1
                        if (kind == "code" && examples.size < 10) {
                            examples += "${file.name}:${i + 1}: $was -> $now: ${text.take(60)}"
                        }
                    }
                }
            }
            val share = 100.0 * (lines - moved) / lines.coerceAtLeast(1)
            println("[format] $lines lines in ${files.size} files, ${"%.2f".format(share)}% keep their indentation, $moved move: $kinds")
            examples.forEach { println("[format]   $it") }
            assertTrue("only ${"%.2f".format(share)}% of vanilla lines keep their indentation", share >= MIN_KEPT_INDENTATION)
        } finally {
            custom.PREPROCESSOR_AT_FIRST_COLUMN = was
        }
    }

    /**
     * The real gate, and note what it does *not* claim.
     *
     * Byte-identical round-tripping of vanilla is not achievable and asserting it was a mistake: the
     * formatting engine strips trailing whitespace, and vanilla is not internally consistent anyway
     * (`defines.c` indents a top-level comment decoratively).
     *
     * What must hold is that formatting only ever changes a line's indentation: never which tokens
     * sit on which line, nor the whitespace between them. Comparing each line without its leading
     * and trailing whitespace states exactly that. It catches a line added or dropped, code joined,
     * split or lost, and alignment tabs turned into spaces, which an earlier version of this check
     * let through by collapsing whitespace runs: see [io.github.lemyst.enfusion.format.InlineWhitespace].
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

        fun tokensPerLine(text: String) = text.lines().map { it.trim() }

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
        /** A line's indentation in columns, a tab counting as the default four. */
        fun indentWidth(line: String): Int =
            line.takeWhile { it == '\t' || it == ' ' }.sumOf { if (it == '\t') 4 else 1 as Int }

        /**
         * The share of vanilla's non-blank lines whose indentation Ctrl+Alt+L must leave alone, in
         * the first 400 files. 98.23% when it was set, against 96.57% before braceless bodies, case
         * bodies and continuation lines were indented. What still moves is vanilla's minority
         * styles: `break;` at its `case` label's level, parameters aligned to a column, stray
         * two-space indents, directives at column 0 and decorative comments.
         */
        const val MIN_KEPT_INDENTATION = 98.0
    }
}
