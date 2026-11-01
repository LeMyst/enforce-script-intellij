package io.github.lemyst.enfusion.rename

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.lemyst.enfusion.psi.EnNamedElement

/**
 * Extends rename beyond "this declaration and its references".
 *
 * The platform's default processor is correct for a language where every binding is a reference.
 * EnfusionScript is not one: see [EnRenameFamily] for the three name-only bindings that carry no
 * reference at all, and [EnRenameGuard] for why a rename that would reach vanilla is refused rather
 * than half-applied.
 */
class EnfusionRenameProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean = element is EnNamedElement

    /**
     * The refusal point.
     *
     * This hook exists to swap in a different element to rename, but it is also the last place
     * before the dialog opens where the refactoring can be stopped with an explanation. Returning
     * null cancels; without the hint the user would see the command simply do nothing.
     */
    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement? {
        val refusal = EnRenameGuard.refusalReason(element) ?: return element
        CommonRefactoringUtil.showErrorHint(
            element.project, editor, refusal, "Rename", /* helpId = */ null,
        )
        return null
    }

    override fun prepareRenaming(
        element: PsiElement,
        newName: String,
        allRenames: MutableMap<PsiElement, String>,
    ) {
        for (relative in EnRenameFamily.of(element)) allRenames[relative] = newName
    }
}
