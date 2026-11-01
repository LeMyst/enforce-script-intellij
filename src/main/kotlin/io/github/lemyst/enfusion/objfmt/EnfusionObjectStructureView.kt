package io.github.lemyst.enfusion.objfmt

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.editor.Editor
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectFile
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjTree
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin

/**
 * The widget tree of a layout, or the image list of an imageset.
 *
 * This is the cheapest useful thing the new PSI buys: a 400-line layout is nested six deep and
 * almost entirely geometry properties, so a tree of just the named objects is how you find the
 * widget you are looking for.
 */
class EnfusionObjectStructureViewFactory : PsiStructureViewFactory {

    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is EnfusionObjectFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                EnfusionObjectStructureViewModel(psiFile)
        }
    }
}

class EnfusionObjectStructureViewModel(psiFile: PsiFile) :
    StructureViewModelBase(psiFile, EnfusionObjectStructureViewElement(psiFile)),
    StructureViewModel.ElementInfoProvider {

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = false
}

class EnfusionObjectStructureViewElement(private val element: PsiElement) :
    StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) {
        (element as? Navigatable)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? Navigatable)?.canNavigate() ?: false

    override fun canNavigateToSource(): Boolean =
        (element as? Navigatable)?.canNavigateToSource() ?: false

    override fun getAlphaSortKey(): String = (element as? EnObjDeclMixin)?.name ?: ""

    override fun getPresentation(): ItemPresentation =
        (element as? NavigationItem)?.presentation
            ?: PresentationData(element.containingFile?.name, null, null, null)

    /**
     * Direct object children, looking through the anonymous `{ … }` children block.
     *
     * That block is a real node in the tree but names nothing, so showing it would add a nameless
     * level between every widget and its children.
     */
    override fun getChildren(): Array<StructureViewTreeElement> =
        EnObjTree.objectChildrenOf(element)
            .map { EnfusionObjectStructureViewElement(it) }
            .toTypedArray()
}
