package io.github.lemyst.enfusion.rename

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.util.IncorrectOperationException
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Rewriting the identifier a reference occupies.
 *
 * [com.intellij.psi.PsiReferenceBase.handleElementRename] would otherwise route through
 * [com.intellij.psi.ElementManipulators], and a manipulator is registered here only for the *string
 * literal* hosts — the code references sit on `refExpr`, `memberExpr`, `typeRef` and a config's
 * `superClause`, so the lookup finds nothing and rename fails with `IncorrectOperationException`
 * before touching the file.
 *
 * Registering manipulators for those instead would work, but it would also mean answering "what does
 * it mean to set the text of a whole `typeRef`" for callers that are not rename. Editing the single
 * leaf the reference range covers is both narrower and exactly what is wanted.
 */
object EnIdentifierRename {

    fun replaceIdentifier(element: PsiElement, range: TextRange, newName: String): PsiElement =
        replace(element, range, newName, EnfusionTypes.IDENT) {
            EnIdentifierFactory.createScriptIdentifier(element.project, it)
        }

    /**
     * The config-language variant. Separate rather than parameterised at the call site because the
     * token identity differs too: a leaf is only valid in the tree its own grammar produced, so the
     * expected type and the factory have to move together.
     */
    fun replaceConfigIdentifier(element: PsiElement, range: TextRange, newName: String): PsiElement =
        replace(element, range, newName, EnfusionConfigTypes.IDENT) {
            EnIdentifierFactory.createConfigIdentifier(element.project, it)
        }

    private fun replace(
        element: PsiElement,
        range: TextRange,
        newName: String,
        expected: IElementType,
        create: (String) -> PsiElement,
    ): PsiElement {
        val leaf = element.findElementAt(range.startOffset)
        if (leaf == null || leaf.node?.elementType !== expected || leaf.textLength != range.length) {
            throw IncorrectOperationException("Cannot rename: no identifier at $range in '${element.text}'")
        }
        leaf.replace(create(newName))
        return element
    }
}
