package io.github.lemyst.enfusion

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.cfg.EnfusionConfigLexerAdapter
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes
import io.github.lemyst.enfusion.lexer.EnfusionLexerAdapter
import io.github.lemyst.enfusion.objfmt.EnfusionObjectLexerAdapter
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectTypes
import io.github.lemyst.enfusion.psi.EnfusionTypes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Block comments in all three lexers. A comment with no terminator runs to the end of the file; one
 * with a terminator lexes exactly as before. The cost bound matters because these lexers run for
 * highlighting and indexing of every file in a project, including ones nobody has reviewed.
 */
class EnfusionBlockCommentLexerTest {

    private class Language(val name: String, val lexer: () -> Lexer, val comment: IElementType, val doc: IElementType?)

    private val languages = listOf(
        Language("script", ::EnfusionLexerAdapter, EnfusionTypes.BLOCK_COMMENT, EnfusionTypes.BLOCK_DOC_COMMENT),
        Language("object", ::EnfusionObjectLexerAdapter, EnfusionObjectTypes.BLOCK_COMMENT, null),
        Language("config", ::EnfusionConfigLexerAdapter, EnfusionConfigTypes.BLOCK_COMMENT, null),
    )

    private fun lex(language: Language, text: String): List<Pair<IElementType, String>> {
        val lexer = language.lexer()
        lexer.start(text)
        val out = mutableListOf<Pair<IElementType, String>>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE) out += type to text.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return out
    }

    private fun first(language: Language, text: String) = lex(language, text).first()

    @Test
    fun terminatedCommentsEndAtTheFirstTerminator() {
        for (l in languages) {
            for (comment in listOf("/* a */", "/*/ a */", "/* a **/", "/* * / */")) {
                assertEquals(l.name, comment, first(l, "$comment x */").second)
            }
        }
    }

    // Not in the list above for scripts: there, an empty comment followed later by a terminator lexes
    // as one doc comment, because the doc rule reads its opener and then a body. That predates the
    // unclosed-comment rule and is unchanged. (Line comments here: Kotlin nests block comments.)
    @Test
    fun emptyCommentEndsAtItsOwnTerminator() {
        for (l in languages.drop(1)) assertEquals(l.name, "/**/", first(l, "/**/ x */").second)
    }

    @Test
    fun scriptDocCommentsAreUnchanged() {
        val script = languages.first()
        assertEquals(EnfusionTypes.BLOCK_DOC_COMMENT to "/** doc */", first(script, "/** doc */ int a;"))
        assertEquals(EnfusionTypes.BLOCK_DOC_COMMENT to "/*! doc */", first(script, "/*! doc */ int a;"))
        // An empty comment is not a doc comment, and must not swallow what follows.
        assertEquals(EnfusionTypes.BLOCK_COMMENT to "/**/", first(script, "/**/ int a;"))
    }

    @Test
    fun unclosedCommentRunsToTheEndOfTheFile() {
        for (l in languages) {
            for (text in listOf("/* a\nb { c", "/*", "/*/", "/* a *", "/* a **", "/** doc")) {
                assertEquals(l.name, listOf(l.comment to text), lex(l, text))
            }
            assertEquals(l.name, listOf(l.comment to "/* a */", l.comment to "/* b"), lex(l, "/* a */ /* b"))
        }
    }

    // Without a match, JFlex scanned to the end of the file at every opener and then fell back to a
    // one-character token, which is quadratic: this 250 KB took 32 s per lexer, and now takes 2 ms.
    @Test(timeout = 10_000)
    fun manyUnclosedOpenersCostLinearTime() {
        for (l in languages) {
            for (unit in listOf("/*a", "/**a", "/*\n")) {
                val text = unit.repeat(250_000 / unit.length)
                assertEquals(l.name, listOf(l.comment to text), lex(l, text))
            }
        }
    }
}

/** The error that replaces the parse error an unclosed comment used to cause. */
class EnfusionUnclosedCommentAnnotatorTest : BasePlatformTestCase() {

    private fun errors(): List<Pair<String, String>> =
        myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description to it.text }

    fun testUnclosedCommentIsReportedOnItsOpener() {
        myFixture.configureByText("test.es", "class A\n{\n}\n/* unfinished\nclass B {}\n")
        assertEquals(listOf("Unclosed comment" to "/*"), errors())
    }

    fun testClosedCommentsAreNotReported() {
        myFixture.configureByText("test.es", "/* a */\n/** b */\nclass A\n{\n}\n/**/\n")
        assertEmpty(errors())
    }

    fun testLayoutAndConfig() {
        myFixture.configureByText("test.layout", "FrameWidgetClass root {\n}\n/* unfinished\n")
        assertEquals(listOf("Unclosed comment" to "/*"), errors())
        myFixture.configureByText("config.cpp", "class CfgPatches\n{\n};\n/* unfinished\n")
        assertEquals(listOf("Unclosed comment" to "/*"), errors())
    }
}
