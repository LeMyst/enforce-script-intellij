package io.github.lemyst.enfusion.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.lemyst.enfusion.lexer.EnfusionLexerAdapter
import io.github.lemyst.enfusion.psi.EnfusionTokenSets
import io.github.lemyst.enfusion.psi.EnfusionTypes

class EnfusionSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = EnfusionLexerAdapter()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        when {
            EnfusionTokenSets.KEYWORDS.contains(tokenType) -> KEYWORD_KEYS
            EnfusionTokenSets.PRIMITIVE_TYPES.contains(tokenType) -> TYPE_KEYS
            EnfusionTokenSets.NUMBERS.contains(tokenType) -> NUMBER_KEYS
            EnfusionTokenSets.OPERATORS.contains(tokenType) -> OPERATOR_KEYS
            EnfusionTokenSets.BRACES.contains(tokenType) -> BRACE_KEYS
            EnfusionTokenSets.PARENS.contains(tokenType) -> PAREN_KEYS
            EnfusionTokenSets.BRACKETS.contains(tokenType) -> BRACKET_KEYS
            EnfusionTokenSets.DOC_COMMENTS.contains(tokenType) -> DOC_COMMENT_KEYS
            tokenType == EnfusionTypes.STRING -> STRING_KEYS
            tokenType == EnfusionTypes.IDENT -> IDENT_KEYS
            tokenType == EnfusionTypes.LINE_COMMENT -> LINE_COMMENT_KEYS
            tokenType == EnfusionTypes.BLOCK_COMMENT -> BLOCK_COMMENT_KEYS
            tokenType == EnfusionTypes.PP_DIRECTIVE -> PP_DIRECTIVE_KEYS
            tokenType == EnfusionTypes.INACTIVE_CODE -> INACTIVE_CODE_KEYS
            tokenType == EnfusionTypes.SEMICOLON -> SEMICOLON_KEYS
            tokenType == EnfusionTypes.COMMA -> COMMA_KEYS
            tokenType == EnfusionTypes.DOT -> DOT_KEYS
            tokenType == TokenType.BAD_CHARACTER -> BAD_CHAR_KEYS
            else -> EMPTY_KEYS
        }

    companion object {
        val KEYWORD: TextAttributesKey = createTextAttributesKey("ENFUSION_KEYWORD", D.KEYWORD)
        val TYPE: TextAttributesKey = createTextAttributesKey("ENFUSION_TYPE", D.KEYWORD)
        val IDENT: TextAttributesKey = createTextAttributesKey("ENFUSION_IDENT", D.IDENTIFIER)
        val STRING: TextAttributesKey = createTextAttributesKey("ENFUSION_STRING", D.STRING)
        val NUMBER: TextAttributesKey = createTextAttributesKey("ENFUSION_NUMBER", D.NUMBER)
        val LINE_COMMENT: TextAttributesKey =
            createTextAttributesKey("ENFUSION_LINE_COMMENT", D.LINE_COMMENT)
        val BLOCK_COMMENT: TextAttributesKey =
            createTextAttributesKey("ENFUSION_BLOCK_COMMENT", D.BLOCK_COMMENT)
        val DOC_COMMENT: TextAttributesKey =
            createTextAttributesKey("ENFUSION_DOC_COMMENT", D.DOC_COMMENT)
        val PP_DIRECTIVE: TextAttributesKey =
            createTextAttributesKey("ENFUSION_PP_DIRECTIVE", D.METADATA)
        val INACTIVE_CODE: TextAttributesKey =
            createTextAttributesKey("ENFUSION_INACTIVE_CODE", D.LINE_COMMENT)
        val OPERATOR: TextAttributesKey =
            createTextAttributesKey("ENFUSION_OPERATOR", D.OPERATION_SIGN)
        val BRACES: TextAttributesKey = createTextAttributesKey("ENFUSION_BRACES", D.BRACES)
        val PARENTHESES: TextAttributesKey =
            createTextAttributesKey("ENFUSION_PARENTHESES", D.PARENTHESES)
        val BRACKETS: TextAttributesKey = createTextAttributesKey("ENFUSION_BRACKETS", D.BRACKETS)
        val SEMICOLON: TextAttributesKey = createTextAttributesKey("ENFUSION_SEMICOLON", D.SEMICOLON)
        val COMMA: TextAttributesKey = createTextAttributesKey("ENFUSION_COMMA", D.COMMA)
        val DOT: TextAttributesKey = createTextAttributesKey("ENFUSION_DOT", D.DOT)
        val BAD_CHARACTER: TextAttributesKey = createTextAttributesKey(
            "ENFUSION_BAD_CHARACTER",
            com.intellij.openapi.editor.HighlighterColors.BAD_CHARACTER,
        )

        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        private val TYPE_KEYS = arrayOf(TYPE)
        private val IDENT_KEYS = arrayOf(IDENT)
        private val STRING_KEYS = arrayOf(STRING)
        private val NUMBER_KEYS = arrayOf(NUMBER)
        private val LINE_COMMENT_KEYS = arrayOf(LINE_COMMENT)
        private val BLOCK_COMMENT_KEYS = arrayOf(BLOCK_COMMENT)
        private val DOC_COMMENT_KEYS = arrayOf(DOC_COMMENT)
        private val PP_DIRECTIVE_KEYS = arrayOf(PP_DIRECTIVE)
        private val INACTIVE_CODE_KEYS = arrayOf(INACTIVE_CODE)
        private val OPERATOR_KEYS = arrayOf(OPERATOR)
        private val BRACE_KEYS = arrayOf(BRACES)
        private val PAREN_KEYS = arrayOf(PARENTHESES)
        private val BRACKET_KEYS = arrayOf(BRACKETS)
        private val SEMICOLON_KEYS = arrayOf(SEMICOLON)
        private val COMMA_KEYS = arrayOf(COMMA)
        private val DOT_KEYS = arrayOf(DOT)
        private val BAD_CHAR_KEYS = arrayOf(BAD_CHARACTER)
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
    }
}
