package io.github.lemyst.enfusion.resolve

import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.index.EnfusionIndexes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin

/**
 * Which classes sit above and below another, for the type hierarchy view.
 *
 * The language has two distinct "is above" relationships and this models both:
 *
 *  - **`extends`** -- ordinary inheritance.
 *  - **`modded class` layering** -- a `modded class X` patches a previously compiled `X` without
 *    naming it. It states no `extends` at all, so nothing in its own text points at the layer it
 *    modifies; the link exists only because the names match. For this codebase that relationship
 *    matters more than inheritance does, since a mod's work is almost entirely layers.
 *
 * Layers are attached in one direction only -- a base class lists its layers as subtypes, and a
 * layer lists the base as its supertype -- because attaching them both ways would make
 * base -> layer -> base a cycle the tree would happily expand forever.
 */
object EnClassHierarchy {

    /** The classes [cls] sits under: its `extends` target, or the layer it patches. */
    fun supertypesOf(cls: EnClassDeclMixin): List<EnClassDeclMixin> {
        val project = cls.project
        if (DumbService.isDumb(project)) return emptyList()

        val superName = cls.getSuperName()?.let { EnTypeInference.baseTypeName(it) }
        if (superName != null) {
            return EnTypeInference.classesNamed(superName, project).filter { it !== cls }
        }
        // No `extends`. If this is a modded layer, what it sits on is the declaration of the same
        // name that is not itself a layer.
        if (!cls.isModded()) return emptyList()
        return EnTypeInference.classesNamed(cls.name, project).filter { it !== cls && !it.isModded() }
    }

    /**
     * The classes sitting under [cls]: everything that `extends` it, plus -- for a base class -- the
     * `modded class` layers patching it.
     *
     * A subclass of a layered class appears under both the base and each layer, since it genuinely
     * inherits through whichever layer is live. That is a little redundant but never wrong, and the
     * alternative (guessing which layer a subclass "really" extends) is not answerable.
     */
    fun subtypesOf(cls: EnClassDeclMixin): List<EnClassDeclMixin> {
        val project = cls.project
        if (DumbService.isDumb(project)) return emptyList()
        val name = cls.name ?: return emptyList()

        val out = LinkedHashSet<EnClassDeclMixin>()
        if (!cls.isModded()) {
            EnTypeInference.classesNamed(name, project).filterTo(out) { it !== cls && it.isModded() }
        }
        out += extendingClasses(name, project)
        out -= cls
        return out.toList()
    }

    /** Every class whose `extends` clause names [name], found through the inverted index. */
    private fun extendingClasses(
        name: String,
        project: com.intellij.openapi.project.Project,
    ): List<EnClassDeclMixin> {
        val psiManager = PsiManager.getInstance(project)
        val found = ArrayList<EnClassDeclMixin>()
        val scope = GlobalSearchScope.allScope(project)
        for (file in EnfusionIndexes.findFiles(EnfusionIndexes.SUPERS, name, scope)) {
            val psiFile = psiManager.findFile(file) ?: continue
            PsiTreeUtil.findChildrenOfType(psiFile, EnClassDeclMixin::class.java)
                .filterTo(found) { it.getSuperName()?.let(EnTypeInference::baseTypeName) == name }
        }
        return found
    }
}
