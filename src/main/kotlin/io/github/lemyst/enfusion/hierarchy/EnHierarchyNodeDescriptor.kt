package io.github.lemyst.enfusion.hierarchy

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin

/**
 * One row in the hierarchy tree.
 *
 * [myHighlightedText] has to be populated even though [NodeDescriptor.myName] is also set: the
 * hierarchy renderer draws the highlighted text and ignores the plain name, so a descriptor that
 * only sets the latter renders as a blank row.
 *
 * The trailing grey text is `EnClassDeclMixin`'s own presentation location -- `modded` or
 * `: SuperName`, then the origin and file tail. In a tree where the same class name legitimately
 * appears several times, that is the only thing telling the rows apart.
 */
class EnHierarchyNodeDescriptor(
    project: Project,
    parent: NodeDescriptor<*>?,
    element: PsiElement,
    isBase: Boolean,
) : HierarchyNodeDescriptor(project, parent, element, isBase) {

    override fun update(): Boolean {
        var changed = super.update()
        val text = CompositeAppearance()

        val cls = psiElement as? EnClassDeclMixin
        if (cls == null || !cls.isValid) {
            text.ending.addText("(invalid)", getInvalidPrefixAttributes())
        } else {
            text.ending.addText(cls.name.orEmpty())
            val location = cls.presentation?.locationString
            if (!location.isNullOrEmpty()) text.ending.addText("  $location", getPackageNameAttributes())
        }

        if (text != myHighlightedText) {
            myHighlightedText = text
            changed = true
        }
        myName = myHighlightedText.text
        return changed
    }
}
