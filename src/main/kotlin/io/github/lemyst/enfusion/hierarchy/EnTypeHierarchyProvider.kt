package io.github.lemyst.enfusion.hierarchy

import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.TypeHierarchyBrowserBase
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin

/** Supplies `Ctrl+H` with the class to root the hierarchy at. */
class EnTypeHierarchyProvider : HierarchyProvider {

    /**
     * Resolves a *reference* under the caret before falling back to the enclosing declaration, so
     * `Ctrl+H` on `PlayerBase` in `extends PlayerBase` opens PlayerBase's hierarchy rather than that
     * of the class being declared -- the same convention Java follows, and the more useful answer in
     * a file whose whole point is what it derives from.
     */
    override fun getTarget(dataContext: DataContext): PsiElement? {
        (CommonDataKeys.PSI_ELEMENT.getData(dataContext) as? EnClassDeclMixin)?.let { return it }

        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return null
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        val offset = editor.caretModel.offset

        file.findReferenceAt(offset)?.let { reference ->
            val resolved = when (reference) {
                is PsiPolyVariantReference -> reference.multiResolve(false).firstNotNullOfOrNull { it.element }
                else -> reference.resolve()
            }
            if (resolved is EnClassDeclMixin) return resolved
        }

        val element = file.findElementAt(offset) ?: return null
        return PsiTreeUtil.getParentOfType(element, EnClassDeclMixin::class.java)
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser =
        EnTypeHierarchyBrowser(target.project, target)

    override fun browserActivated(browser: HierarchyBrowser) {
        (browser as EnTypeHierarchyBrowser).changeView(TypeHierarchyBrowserBase.getTypeHierarchyType())
    }
}
