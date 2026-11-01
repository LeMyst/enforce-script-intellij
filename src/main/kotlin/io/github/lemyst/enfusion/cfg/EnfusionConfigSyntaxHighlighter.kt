package io.github.lemyst.enfusion.cfg

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes

/**
 * Highlighting for `config.cpp` and `mod.cpp`.
 *
 * `class` is a real token here, so unlike the object format the keyword can be coloured from the
 * lexer alone. Property keys and class names are still both plain identifiers — separating those
 * needs the structure around them and would be an annotator's job.
 */
class EnfusionConfigSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = EnfusionConfigLexerAdapter()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        when (tokenType) {
            EnfusionConfigTypes.CLASS_KW -> KEYWORD_KEYS
            EnfusionConfigTypes.IDENT -> IDENT_KEYS
            EnfusionConfigTypes.STRING -> STRING_KEYS
            EnfusionConfigTypes.NUMBER -> NUMBER_KEYS
            EnfusionConfigTypes.LBRACE, EnfusionConfigTypes.RBRACE -> BRACE_KEYS
            EnfusionConfigTypes.LBRACKET, EnfusionConfigTypes.RBRACKET -> BRACKET_KEYS
            EnfusionConfigTypes.SEMICOLON, EnfusionConfigTypes.COMMA -> SEMICOLON_KEYS
            EnfusionConfigTypes.EQ, EnfusionConfigTypes.PLUS_EQ, EnfusionConfigTypes.COLON -> OPERATOR_KEYS
            EnfusionConfigTypes.LINE_COMMENT -> LINE_COMMENT_KEYS
            EnfusionConfigTypes.BLOCK_COMMENT -> BLOCK_COMMENT_KEYS
            EnfusionConfigTypes.PP_DIRECTIVE -> DIRECTIVE_KEYS
            TokenType.BAD_CHARACTER -> BAD_CHAR_KEYS
            else -> EMPTY_KEYS
        }

    companion object {
        val KEYWORD: TextAttributesKey = createTextAttributesKey("ENFUSION_CFG_KEYWORD", D.KEYWORD)
        val IDENT: TextAttributesKey = createTextAttributesKey("ENFUSION_CFG_IDENT", D.IDENTIFIER)
        val STRING: TextAttributesKey = createTextAttributesKey("ENFUSION_CFG_STRING", D.STRING)
        val NUMBER: TextAttributesKey = createTextAttributesKey("ENFUSION_CFG_NUMBER", D.NUMBER)
        val BRACES: TextAttributesKey = createTextAttributesKey("ENFUSION_CFG_BRACES", D.BRACES)
        val BRACKETS: TextAttributesKey = createTextAttributesKey("ENFUSION_CFG_BRACKETS", D.BRACKETS)
        val SEMICOLON: TextAttributesKey = createTextAttributesKey("ENFUSION_CFG_SEMICOLON", D.SEMICOLON)
        val OPERATOR: TextAttributesKey =
            createTextAttributesKey("ENFUSION_CFG_OPERATOR", D.OPERATION_SIGN)
        val LINE_COMMENT: TextAttributesKey =
            createTextAttributesKey("ENFUSION_CFG_LINE_COMMENT", D.LINE_COMMENT)
        val BLOCK_COMMENT: TextAttributesKey =
            createTextAttributesKey("ENFUSION_CFG_BLOCK_COMMENT", D.BLOCK_COMMENT)
        val DIRECTIVE: TextAttributesKey =
            createTextAttributesKey("ENFUSION_CFG_DIRECTIVE", D.METADATA)
        val BAD_CHARACTER: TextAttributesKey =
            createTextAttributesKey("ENFUSION_CFG_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        private val IDENT_KEYS = arrayOf(IDENT)
        private val STRING_KEYS = arrayOf(STRING)
        private val NUMBER_KEYS = arrayOf(NUMBER)
        private val BRACE_KEYS = arrayOf(BRACES)
        private val BRACKET_KEYS = arrayOf(BRACKETS)
        private val SEMICOLON_KEYS = arrayOf(SEMICOLON)
        private val OPERATOR_KEYS = arrayOf(OPERATOR)
        private val LINE_COMMENT_KEYS = arrayOf(LINE_COMMENT)
        private val BLOCK_COMMENT_KEYS = arrayOf(BLOCK_COMMENT)
        private val DIRECTIVE_KEYS = arrayOf(DIRECTIVE)
        private val BAD_CHAR_KEYS = arrayOf(BAD_CHARACTER)
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
    }
}

class EnfusionConfigSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        EnfusionConfigSyntaxHighlighter()
}
