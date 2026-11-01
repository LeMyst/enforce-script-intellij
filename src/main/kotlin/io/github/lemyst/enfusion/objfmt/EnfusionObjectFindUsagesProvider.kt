package io.github.lemyst.enfusion.objfmt

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectTypes
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin

/**
 * Find Usages, and therefore rename, for widgets and imageset entries.
 *
 * The words scanner is the load-bearing part: it is what puts a widget's name into the platform's
 * identifier index, and `ReferencesSearch` will not look inside a file whose words were never
 * indexed. Without it, renaming `RowHealthIcon` in a `.layout` would find none of the
 * `FindAnyWidget("RowHealthIcon")` calls that name it.
 *
 * `STRING` is passed as the literal set so that a quoted `"Size To Content V"` key contributes its
 * words too — layout properties are quoted whenever they contain spaces.
 */
class EnfusionObjectFindUsagesProvider : FindUsagesProvider {

    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(
            EnfusionObjectLexerAdapter(),
            TokenSet.create(EnfusionObjectTypes.IDENT),
            TokenSet.create(EnfusionObjectTypes.LINE_COMMENT, EnfusionObjectTypes.BLOCK_COMMENT),
            TokenSet.create(EnfusionObjectTypes.STRING),
        ).apply {
            // Same reason as on the script side: a layout's `text "#STR_BR_WIN"` is a reference to
            // something that is not a file, so its words have to be searchable outside IN_STRINGS.
            setMayHaveFileRefsInLiterals(true)
        }

    override fun canFindUsagesFor(element: PsiElement): Boolean = element is EnObjDeclMixin

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String =
        (element as? EnObjDeclMixin)?.getClassName() ?: "object"

    override fun getDescriptiveName(element: PsiElement): String =
        (element as? EnObjDeclMixin)?.name ?: ""

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        getDescriptiveName(element)
}
