package io.github.lemyst.enfusion.nav

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import io.github.lemyst.enfusion.lexer.EnfusionLexerAdapter
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTokenSets
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * Supplies the words scanner the platform uses to build its index of identifier occurrences, which
 * is what makes Find Usages and rename-safety checks possible at all.
 *
 * The scanner runs on the raw lexer rather than the preprocessing one: occurrences inside an
 * inactive `#ifdef` branch are still real text the user may want to find.
 */
class EnfusionFindUsagesProvider : FindUsagesProvider {

    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(
            EnfusionLexerAdapter(),
            TokenSet.create(EnfusionTypes.IDENT),
            EnfusionTokenSets.COMMENTS,
            EnfusionTokenSets.STRINGS,
        ).apply {
            // Words inside a string are otherwise indexed under IN_STRINGS alone, and the default
            // reference search only looks there when the target is a file. Everything this plugin
            // navigates from a literal -- widget names, stringtable keys, RPC and timer handler
            // names -- has a target that is not a file, so without this Find Usages and rename see
            // none of them, however well Ctrl+Click resolves. The flag is named for the file-path
            // case but what it actually does is add IN_FOREIGN_LANGUAGES, which the search does
            // consult.
            setMayHaveFileRefsInLiterals(true)
        }

    override fun canFindUsagesFor(element: PsiElement): Boolean = element is EnNamedElement

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String = when (element) {
        is EnClassDeclMixin -> if (element.isModded()) "modded class" else "class"
        is EnMethodDeclMixin -> when {
            element.isConstructor() -> "constructor"
            element.isDestructor() -> "destructor"
            else -> "method"
        }
        else -> when (element.node?.elementType) {
            EnfusionTypes.ENUM_DECL -> "enum"
            EnfusionTypes.ENUM_CONSTANT -> "enum constant"
            EnfusionTypes.TYPEDEF_DECL -> "typedef"
            EnfusionTypes.PARAM -> "parameter"
            EnfusionTypes.FOREACH_VAR -> "variable"
            else -> "variable"
        }
    }

    override fun getDescriptiveName(element: PsiElement): String =
        (element as? EnNamedElement)?.name ?: ""

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        getDescriptiveName(element)
}
