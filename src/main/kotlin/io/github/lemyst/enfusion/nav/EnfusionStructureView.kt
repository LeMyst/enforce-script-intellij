package io.github.lemyst.enfusion.nav

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionFile
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

class EnfusionStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is EnfusionFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                EnfusionStructureViewModel(psiFile)
        }
    }
}

class EnfusionStructureViewModel(psiFile: PsiFile) :
    StructureViewModelBase(psiFile, EnfusionStructureViewElement(psiFile)),
    StructureViewModel.ElementInfoProvider {

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean =
        element.value is EnClassDeclMixin

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean {
        val value = element.value
        return value is EnNamedElement && value !is EnClassDeclMixin &&
            value.node?.elementType !== EnfusionTypes.ENUM_DECL
    }
}

class EnfusionStructureViewElement(private val element: PsiElement) :
    StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) {
        (element as? com.intellij.pom.Navigatable)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean =
        (element as? com.intellij.pom.Navigatable)?.canNavigate() ?: false

    override fun canNavigateToSource(): Boolean =
        (element as? com.intellij.pom.Navigatable)?.canNavigateToSource() ?: false

    override fun getAlphaSortKey(): String = (element as? EnNamedElement)?.name ?: ""

    override fun getPresentation(): ItemPresentation =
        (element as? EnMethodDeclMixin)?.signaturePresentation()
            ?: (element as? com.intellij.navigation.NavigationItem)?.presentation
            ?: com.intellij.ide.projectView.PresentationData(
                element.containingFile?.name, null, null, null,
            )

    override fun getChildren(): Array<StructureViewTreeElement> =
        declarationChildren(element).map { EnfusionStructureViewElement(it) }.toTypedArray()

    /**
     * The declarations directly owned by [parent].
     *
     * `varDecl` is deliberately unwrapped: it is not itself named, because one declaration can
     * introduce several names (`int a, b;`), so the tree shows its `varDeclarator` children.
     */
    private fun declarationChildren(parent: PsiElement): List<PsiElement> {
        val scope: PsiElement = when {
            parent is EnfusionFile -> parent
            parent is EnClassDeclMixin ->
                parent.node.findChildByType(EnfusionTypes.CLASS_BODY)?.psi ?: return emptyList()
            parent.node?.elementType === EnfusionTypes.ENUM_DECL -> parent
            else -> return emptyList()
        }

        val result = mutableListOf<PsiElement>()
        for (child in scope.children) {
            when {
                child.node?.elementType === EnfusionTypes.VAR_DECL ->
                    result += PsiTreeUtil.getChildrenOfTypeAsList(child, PsiElement::class.java)
                        .filter { it.node?.elementType === EnfusionTypes.VAR_DECLARATOR }
                child is EnNamedElement -> result += child
            }
        }
        return result
    }
}
