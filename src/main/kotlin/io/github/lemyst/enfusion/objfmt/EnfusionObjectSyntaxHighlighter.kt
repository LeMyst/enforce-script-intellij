package io.github.lemyst.enfusion.objfmt

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
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectTypes

/**
 * Highlighting for `.layout` and `.imageset`.
 *
 * Identifiers are not split into class names and property keys here: telling them apart needs the
 * brace that follows, which a lexer-driven highlighter cannot see. An annotator could, and would be
 * the right place for it if the flat colouring ever proves hard to read.
 */
class EnfusionObjectSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = EnfusionObjectLexerAdapter()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        when (tokenType) {
            EnfusionObjectTypes.IDENT -> IDENT_KEYS
            EnfusionObjectTypes.STRING -> STRING_KEYS
            EnfusionObjectTypes.NUMBER -> NUMBER_KEYS
            EnfusionObjectTypes.LBRACE, EnfusionObjectTypes.RBRACE -> BRACE_KEYS
            EnfusionObjectTypes.LINE_COMMENT -> LINE_COMMENT_KEYS
            EnfusionObjectTypes.BLOCK_COMMENT -> BLOCK_COMMENT_KEYS
            TokenType.BAD_CHARACTER -> BAD_CHAR_KEYS
            else -> EMPTY_KEYS
        }

    companion object {
        val IDENT: TextAttributesKey = createTextAttributesKey("ENFUSION_OBJ_IDENT", D.IDENTIFIER)
        val STRING: TextAttributesKey = createTextAttributesKey("ENFUSION_OBJ_STRING", D.STRING)
        val NUMBER: TextAttributesKey = createTextAttributesKey("ENFUSION_OBJ_NUMBER", D.NUMBER)
        val BRACES: TextAttributesKey = createTextAttributesKey("ENFUSION_OBJ_BRACES", D.BRACES)
        val LINE_COMMENT: TextAttributesKey =
            createTextAttributesKey("ENFUSION_OBJ_LINE_COMMENT", D.LINE_COMMENT)
        val BLOCK_COMMENT: TextAttributesKey =
            createTextAttributesKey("ENFUSION_OBJ_BLOCK_COMMENT", D.BLOCK_COMMENT)
        val BAD_CHARACTER: TextAttributesKey =
            createTextAttributesKey("ENFUSION_OBJ_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

        private val IDENT_KEYS = arrayOf(IDENT)
        private val STRING_KEYS = arrayOf(STRING)
        private val NUMBER_KEYS = arrayOf(NUMBER)
        private val BRACE_KEYS = arrayOf(BRACES)
        private val LINE_COMMENT_KEYS = arrayOf(LINE_COMMENT)
        private val BLOCK_COMMENT_KEYS = arrayOf(BLOCK_COMMENT)
        private val BAD_CHAR_KEYS = arrayOf(BAD_CHARACTER)
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
    }
}

class EnfusionObjectSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        EnfusionObjectSyntaxHighlighter()
}
