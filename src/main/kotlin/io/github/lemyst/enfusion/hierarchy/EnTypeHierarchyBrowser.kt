package io.github.lemyst.enfusion.hierarchy

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.TypeHierarchyBrowserBase
import com.intellij.ide.util.treeView.AlphaComparator
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import javax.swing.JPanel
import javax.swing.JTree

/**
 * The `Ctrl+H` browser.
 *
 * The platform's three standard tabs are Class, Supertypes and Subtypes. Class is served by the same
 * structure as Subtypes here rather than by the Java-style "ancestor chain down to this class, then
 * its subtypes": building that path needs a single unambiguous parent per node, and a `modded class`
 * has no such thing -- it can sit over several layers, none of which it names. Supertypes answers
 * "what is above this" perfectly well on its own tab.
 */
class EnTypeHierarchyBrowser(project: Project, element: PsiElement) :
    TypeHierarchyBrowserBase(project, element) {

    /** The language has no interfaces at all -- every type is a class, an enum or a typedef. */
    override fun isInterface(psiElement: PsiElement): Boolean = false

    override fun canBeDeleted(psiElement: PsiElement): Boolean = psiElement is EnClassDeclMixin

    /** No namespaces, so the qualified name is just the name. */
    override fun getQualifiedName(psiElement: PsiElement): String =
        (psiElement as? EnClassDeclMixin)?.name.orEmpty()

    override fun isApplicableElement(element: PsiElement): Boolean = element is EnClassDeclMixin

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? =
        descriptor.psiElement

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        createTreeAndSetupCommonActions(trees, IdeActions.GROUP_TYPE_HIERARCHY_POPUP)
    }

    override fun createHierarchyTreeStructure(type: String, psiElement: PsiElement): HierarchyTreeStructure? {
        val cls = psiElement as? EnClassDeclMixin ?: return null
        return when (type) {
            getSupertypesHierarchyType() -> EnSupertypesHierarchyTreeStructure(myProject, cls)
            getSubtypesHierarchyType(), getTypeHierarchyType() ->
                EnSubtypesHierarchyTreeStructure(myProject, cls)
            else -> null
        }
    }

    override fun getComparator(): Comparator<NodeDescriptor<*>>? = AlphaComparator.getInstance()

    /** The legend explains Java's interface/class colouring, which has no counterpart here. */
    override fun createLegendPanel(): JPanel? = null
}
