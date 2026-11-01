package io.github.lemyst.enfusion.resolve

import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * Where an `override` method's body actually comes from.
 *
 * A method's own name identifier carries no [com.intellij.psi.PsiReference] -- only *usages* of a
 * name do, never the declaration itself -- so `modded class DayZGame { override void
 * ConnectFromCLI() {} }` had no path back to vanilla's `ConnectFromCLI` at all: Ctrl+Click on your
 * own declaration is not a reference, it already *is* the target. This is what stands in for one,
 * feeding both [io.github.lemyst.enfusion.nav.EnOverrideLineMarkerProvider] (the gutter icon) and
 * [io.github.lemyst.enfusion.nav.EnOverrideGotoDeclarationHandler] (making Ctrl+Click work too).
 *
 * Two distinct shapes produce a hit, and a method can match either or both:
 *  - **`modded class` layering** -- the same class name declared again elsewhere. `modded` classes
 *    do not `extends` the layer they patch, so this is not a superclass walk: it is every *other*
 *    declaration of the same class name, searched for a method of the same name directly in its body.
 *  - **Ordinary inheritance** -- `class B extends A { override void Foo() }`, resolved through
 *    [EnTypeInference.findMembers], which already walks the superclass chain.
 */
object EnOverrideResolution {

    fun superDeclarationsOf(method: EnMethodDeclMixin): List<EnMethodDeclMixin> {
        val name = method.name ?: return emptyList()
        val owner = PsiTreeUtil.getParentOfType(method, EnClassDeclMixin::class.java) ?: return emptyList()
        val project = method.project
        val found = LinkedHashSet<EnMethodDeclMixin>()

        for (layer in EnTypeInference.classesNamed(owner.name, project)) {
            if (layer === owner) continue
            found += directMethodsNamed(layer, name)
        }

        owner.getSuperName()?.let { superName ->
            for (parent in EnTypeInference.classesNamed(superName, project)) {
                EnTypeInference.findMembers(parent, name, project)
                    .filterIsInstance<EnMethodDeclMixin>()
                    .forEach { found += it }
            }
        }

        found -= method
        return found.toList()
    }

    /** Methods declared directly in [owner]'s own body, ignoring anything it inherits. */
    fun directMethodsNamed(owner: EnClassDeclMixin, name: String): List<EnMethodDeclMixin> {
        val body = owner.node.findChildByType(EnfusionTypes.CLASS_BODY)?.psi ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(body, EnMethodDeclMixin::class.java).filter { it.name == name }
    }
}
