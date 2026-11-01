package io.github.lemyst.enfusion.cfg

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin

/**
 * Find Usages, and therefore rename, for config classes.
 *
 * The words scanner is the load-bearing part, as in the other two languages: `ReferencesSearch` will
 * not look inside a file whose words were never indexed. Without it, renaming `BREffectArea` would
 * find neither the `class X : BREffectArea` that extends it nor the `requiredAddons[]` entry naming
 * it.
 */
class EnfusionConfigFindUsagesProvider : FindUsagesProvider {

    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(
            EnfusionConfigLexerAdapter(),
            TokenSet.create(EnfusionConfigTypes.IDENT),
            TokenSet.create(
                EnfusionConfigTypes.LINE_COMMENT,
                EnfusionConfigTypes.BLOCK_COMMENT,
                EnfusionConfigTypes.PP_DIRECTIVE,
            ),
            TokenSet.create(EnfusionConfigTypes.STRING),
        ).apply {
            // An addon is named by a *string* in `requiredAddons[]`, and words in strings are
            // otherwise indexed under IN_STRINGS alone, which the default reference search consults
            // only for file targets. Same flag, same reason, as the other two languages.
            setMayHaveFileRefsInLiterals(true)
        }

    override fun canFindUsagesFor(element: PsiElement): Boolean = element is EnCfgClassMixin

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String {
        val cls = element as? EnCfgClassMixin ?: return "class"
        return if (cls.isForwardDeclaration()) "forward declaration" else "config class"
    }

    override fun getDescriptiveName(element: PsiElement): String =
        (element as? EnCfgClassMixin)?.name ?: ""

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        getDescriptiveName(element)
}
