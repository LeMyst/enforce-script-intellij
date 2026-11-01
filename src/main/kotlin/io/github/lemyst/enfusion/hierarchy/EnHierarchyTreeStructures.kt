package io.github.lemyst.enfusion.hierarchy

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.openapi.project.Project
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.resolve.EnClassHierarchy

/**
 * Children are built per node, on expansion, so a query only ever runs for a branch someone opened.
 * That is what keeps rooting at a heavily-subclassed vanilla type affordable.
 */
abstract class EnHierarchyTreeStructureBase(
    project: Project,
    root: EnClassDeclMixin,
) : HierarchyTreeStructure(project, EnHierarchyNodeDescriptor(project, null, root, true)) {

    protected abstract fun relatedTo(cls: EnClassDeclMixin): List<EnClassDeclMixin>

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val cls = descriptor.psiElement as? EnClassDeclMixin ?: return emptyArray()
        return relatedTo(cls)
            .map { EnHierarchyNodeDescriptor(myProject, descriptor, it, false) }
            .toTypedArray()
    }
}

/** Upwards: the `extends` target, or the layer a `modded class` patches. */
class EnSupertypesHierarchyTreeStructure(project: Project, root: EnClassDeclMixin) :
    EnHierarchyTreeStructureBase(project, root) {

    override fun relatedTo(cls: EnClassDeclMixin): List<EnClassDeclMixin> =
        EnClassHierarchy.supertypesOf(cls)
}

/** Downwards: subclasses, plus the `modded class` layers of a base class. */
class EnSubtypesHierarchyTreeStructure(project: Project, root: EnClassDeclMixin) :
    EnHierarchyTreeStructureBase(project, root) {

    override fun relatedTo(cls: EnClassDeclMixin): List<EnClassDeclMixin> =
        EnClassHierarchy.subtypesOf(cls)
}
