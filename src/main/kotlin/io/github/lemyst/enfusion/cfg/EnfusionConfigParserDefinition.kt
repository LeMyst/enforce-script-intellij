package io.github.lemyst.enfusion.cfg

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
import io.github.lemyst.enfusion.cfg.lexer._EnfusionConfigLexer
import io.github.lemyst.enfusion.cfg.parser.EnfusionConfigParser
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigFile
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes

class EnfusionConfigLexerAdapter : FlexAdapter(_EnfusionConfigLexer(null))

class EnfusionConfigParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = EnfusionConfigLexerAdapter()

    override fun createParser(project: Project?): PsiParser = EnfusionConfigParser()

    override fun getFileNodeType(): IFileElementType = FILE

    /** Newlines included, unlike the object format — nothing in a config depends on line breaks. */
    override fun getWhitespaceTokens(): TokenSet = TokenSet.create(TokenType.WHITE_SPACE)

    /**
     * `#define` is listed as a comment token so the two files in the corpus that use one still parse.
     * Comment-class tokens are skipped by the parser but survive in the tree, which is the same trick
     * the script side uses for its preprocessor directives.
     */
    override fun getCommentTokens(): TokenSet = TokenSet.create(
        EnfusionConfigTypes.LINE_COMMENT,
        EnfusionConfigTypes.BLOCK_COMMENT,
        EnfusionConfigTypes.PP_DIRECTIVE,
    )

    override fun getStringLiteralElements(): TokenSet = TokenSet.create(EnfusionConfigTypes.STRING)

    override fun createElement(node: ASTNode): PsiElement =
        EnfusionConfigTypes.Factory.createElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = EnfusionConfigFile(viewProvider)

    companion object {
        val FILE: IFileElementType = IFileElementType(EnfusionConfigLanguage)
    }
}
