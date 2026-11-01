package io.github.lemyst.enfusion

import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType
import io.github.lemyst.enfusion.lexer.EnfusionLexerAdapter
import io.github.lemyst.enfusion.psi.EnfusionTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case here is a construct that a naive C-family lexer gets wrong, taken verbatim from a
 * scan of the vanilla corpus.
 */
class EnfusionLexerTest {

    private fun lex(text: String): List<Pair<Any?, String>> {
        val lexer: Lexer = EnfusionLexerAdapter()
        lexer.start(text)
        val out = mutableListOf<Pair<Any?, String>>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != TokenType.WHITE_SPACE) {
                out += lexer.tokenType to text.substring(lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        return out
    }

    private fun types(text: String) = lex(text).map { it.first }

    private fun assertNoBadChars(text: String) =
        assertFalse("unexpected BAD_CHARACTER in: $text", types(text).contains(TokenType.BAD_CHARACTER))

    /** `array<ref array<PIXEL>>` must close as two separate GT tokens, never a single `>>`. */
    @Test
    fun nestedGenericsNeverProduceShiftRight() {
        val t = types("ref array<ref array<PIXEL>> m_Pixels;")
        assertEquals(2, t.count { it == EnfusionTypes.GT })
        assertTrue(t.none { it == EnfusionTypes.LTLT })
    }

    /** `\d` is not a defined escape; the compiler passes it through and so must the lexer. */
    @Test
    fun unknownStringEscapesAreAccepted() {
        val text = """"dz\\characters\\zombies\data\\Mummy_dead.rvmat""""
        assertNoBadChars(text)
        assertEquals(listOf(EnfusionTypes.STRING), types(text))
    }

    @Test
    fun stringWithEscapedQuoteIsOneToken() {
        assertEquals(listOf(EnfusionTypes.STRING), types(""""say \"hi\" now""""))
    }

    @Test
    fun numericLiteralForms() {
        assertEquals(EnfusionTypes.INT_LITERAL, types("0xff8e8045").single())
        assertEquals(EnfusionTypes.INT_LITERAL, types("1024").single())
        assertEquals(EnfusionTypes.FLOAT_LITERAL, types("0.5").single())
        assertEquals(EnfusionTypes.FLOAT_LITERAL, types("0.75242724772f").single())
    }

    @Test
    fun docCommentsAreDistinctFromPlainComments() {
        assertEquals(EnfusionTypes.LINE_DOC_COMMENT, types("//! Super root").single())
        assertEquals(EnfusionTypes.LINE_DOC_COMMENT, types("//!< trailing doc").single())
        assertEquals(EnfusionTypes.LINE_DOC_COMMENT, types("/// group").single())
        assertEquals(EnfusionTypes.LINE_COMMENT, types("// plain").single())
        assertEquals(EnfusionTypes.BLOCK_DOC_COMMENT, types("/** \\brief x */").single())
        assertEquals(EnfusionTypes.BLOCK_DOC_COMMENT, types("/*! x */").single())
        assertEquals(EnfusionTypes.BLOCK_COMMENT, types("/* x */").single())
        assertEquals(EnfusionTypes.BLOCK_COMMENT, types("/**/").single())
    }

    @Test
    fun preprocessorDirectivesAreSingleTokens() {
        assertEquals(EnfusionTypes.PP_DIRECTIVE, types("#ifdef SERVER").single())
        assertEquals(EnfusionTypes.PP_DIRECTIVE, types("#ifndef AUTOTEST").single())
        assertEquals(EnfusionTypes.PP_DIRECTIVE, types("#endif").single())
        assertEquals(EnfusionTypes.PP_DIRECTIVE, types("#define DAYZ_X_XX").single())
        // A directive with a trailing comment stays one token.
        assertEquals(EnfusionTypes.PP_DIRECTIVE, types("#ifdef WORKBENCH // uses its own renderer").single())
    }

    /** `"#STR_BR_FOO"` is a string, not a directive — STRING wins by longest match. */
    @Test
    fun hashInsideStringIsNotADirective() {
        assertEquals(listOf(EnfusionTypes.STRING), types(""""#STR_BR_WINNER""""))
    }

    /** Contextual keywords must stay IDENT so they can still be used as identifiers. */
    @Test
    fun contextualKeywordsStayIdentifiers() {
        for (word in listOf("modded", "sealed", "extends", "local", "auto", "owned",
                            "reference", "external", "event", "Class", "NULL")) {
            assertEquals("'$word' must lex as IDENT", listOf(EnfusionTypes.IDENT), types(word))
        }
    }

    @Test
    fun hardKeywordsAreReserved() {
        assertEquals(listOf(EnfusionTypes.CLASS_KW), types("class"))
        assertEquals(listOf(EnfusionTypes.FOREACH), types("foreach"))
        assertEquals(listOf(EnfusionTypes.OVERRIDE), types("override"))
        // ...but only as whole words.
        assertEquals(listOf(EnfusionTypes.IDENT), types("classy"))
    }

    /** `~` is both the destructor sigil and bitwise NOT; the lexer emits one token for both. */
    @Test
    fun tildeIsASingleToken() {
        assertEquals(
            listOf(EnfusionTypes.VOID, EnfusionTypes.TILDE, EnfusionTypes.IDENT,
                   EnfusionTypes.LPAREN, EnfusionTypes.RPAREN),
            types("void ~WorldEditorAPI()"),
        )
        assertTrue(types("flags &= ~ECE_DYNAMIC;").contains(EnfusionTypes.TILDE))
    }

    /** There are zero character literals in 417k lines, so `'` is a genuine mistake. */
    @Test
    fun singleQuoteIsABadCharacter() {
        assertTrue(types("'x'").contains(TokenType.BAD_CHARACTER))
    }

    @Test
    fun byteOrderMarkIsWhitespaceNotABadCharacter() {
        assertNoBadChars("﻿class Foo {}")
    }

    @Test
    fun foreachWithTwoVariablesAndNestedGenerics() {
        assertNoBadChars("foreach (array<ref array<float>> polygonData : area.PRAPolygons) {}")
    }
}
