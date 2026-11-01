package io.github.lemyst.enfusion.cfg

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
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigFile
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin

/**
 * The class tree of a config.
 *
 * Worth more here than in either other language: a config is almost entirely properties, and the
 * shape a reader actually wants — `CfgPatches` / `CfgMods` / `CfgVehicles` and what hangs under each
 * — is buried in them. Vanilla's `weapons/melee/config.cpp` is 2.5 MB of exactly that.
 */
class EnfusionConfigStructureViewFactory : PsiStructureViewFactory {

    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is EnfusionConfigFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                EnfusionConfigStructureViewModel(psiFile)
        }
    }
}

class EnfusionConfigStructureViewModel(psiFile: PsiFile) :
    StructureViewModelBase(psiFile, EnfusionConfigStructureViewElement(psiFile)),
    StructureViewModel.ElementInfoProvider {

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = false
}

class EnfusionConfigStructureViewElement(private val element: PsiElement) :
    StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) {
        (element as? Navigatable)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? Navigatable)?.canNavigate() ?: false

    override fun canNavigateToSource(): Boolean =
        (element as? Navigatable)?.canNavigateToSource() ?: false

    override fun getAlphaSortKey(): String = (element as? EnCfgClassMixin)?.name ?: ""

    override fun getPresentation(): ItemPresentation =
        (element as? NavigationItem)?.presentation
            ?: PresentationData(element.containingFile?.name, null, null, null)

    /**
     * Direct class children only.
     *
     * No anonymous block to see through, unlike the object format: `item` is a private rule, so its
     * alternatives are inlined and a nested class is a direct child of the class that holds it.
     */
    override fun getChildren(): Array<StructureViewTreeElement> =
        PsiTreeUtil.getChildrenOfTypeAsList(element, EnCfgClassMixin::class.java)
            .map { EnfusionConfigStructureViewElement(it) }
            .toTypedArray()
}
