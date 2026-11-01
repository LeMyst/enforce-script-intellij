package io.github.lemyst.enfusion.objfmt

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.github.lemyst.enfusion.objfmt.lexer._EnfusionObjectLexer
import io.github.lemyst.enfusion.objfmt.parser.EnfusionObjectParser
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectFile
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectTypes

/** The raw lexer, fully restartable — used for highlighting and for the parser alike. */
class EnfusionObjectLexerAdapter : FlexAdapter(_EnfusionObjectLexer(null))

class EnfusionObjectParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = EnfusionObjectLexerAdapter()

    override fun createParser(project: Project?): PsiParser = EnfusionObjectParser()

    override fun getFileNodeType(): IFileElementType = FILE

    /**
     * Spaces and tabs only — **not** newlines.
     *
     * This is the one line that makes the grammar work. A property is `key value*` with no
     * terminator and a value may be a bare identifier, so if the line break were whitespace the
     * parser could not tell where `position 0 0` ends and `size 1 1` begins.
     */
    override fun getWhitespaceTokens(): TokenSet = TokenSet.create(TokenType.WHITE_SPACE)

    override fun getCommentTokens(): TokenSet =
        TokenSet.create(EnfusionObjectTypes.LINE_COMMENT, EnfusionObjectTypes.BLOCK_COMMENT)

    override fun getStringLiteralElements(): TokenSet = TokenSet.create(EnfusionObjectTypes.STRING)

    override fun createElement(node: ASTNode): PsiElement =
        EnfusionObjectTypes.Factory.createElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = EnfusionObjectFile(viewProvider)

    companion object {
        val FILE: IFileElementType = IFileElementType(EnfusionObjectLanguage)
    }
}
