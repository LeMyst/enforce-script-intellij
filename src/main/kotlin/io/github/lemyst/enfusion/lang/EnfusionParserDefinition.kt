package io.github.lemyst.enfusion.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.github.lemyst.enfusion.lexer.EnfusionDefines
import io.github.lemyst.enfusion.lexer.EnfusionPreprocessingLexer
import io.github.lemyst.enfusion.parser.EnfusionParser
import io.github.lemyst.enfusion.psi.EnfusionFile
import io.github.lemyst.enfusion.psi.EnfusionTokenSets
import io.github.lemyst.enfusion.psi.EnfusionTypes

class EnfusionParserDefinition @JvmOverloads constructor(
    private val defines: Set<String> = EnfusionDefines.DEFAULT,
) : ParserDefinition {

    /**
     * The preprocessing lexer, not the raw one: it resolves `#ifdef` regions so the token stream
     * handed to the parser is brace-balanced. M3 sources the define set from project settings
     * instead of the built-in default.
     */
    override fun createLexer(project: Project?): Lexer = EnfusionPreprocessingLexer(defines)

    override fun createParser(project: Project?): PsiParser = EnfusionParser()

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet = EnfusionTokenSets.COMMENTS

    override fun getStringLiteralElements(): TokenSet = EnfusionTokenSets.STRINGS

    override fun createElement(node: ASTNode): PsiElement = EnfusionTypes.Factory.createElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = EnfusionFile(viewProvider)

    companion object {
        val FILE: IFileElementType = IFileElementType(EnfusionLanguage)
    }
}
