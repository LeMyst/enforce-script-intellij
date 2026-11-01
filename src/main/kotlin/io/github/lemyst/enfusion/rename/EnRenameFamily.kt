package io.github.lemyst.enfusion.rename

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnClassHierarchy
import io.github.lemyst.enfusion.resolve.EnOverrideResolution
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * The other declarations a rename has to carry with it.
 *
 * Renaming one declaration and its usages is not enough in this language, because a second
 * declaration of the same name is frequently *bound to the first by name alone* with nothing in the
 * text pointing either way:
 *
 *  - `modded class X` patches a previously compiled `X`. Rename the base and the layer no longer
 *    patches anything — it silently declares a new class instead, and the mod stops working with no
 *    compile error at all.
 *  - `override void Foo()` overrides by name. Rename the base method and the override becomes a
 *    redeclaration, which *is* a compile error, but only at build time.
 *  - `void ClassName()` is a constructor purely because the names match. Rename the class and it
 *    demotes to an ordinary method that is never called.
 *
 * None of these is reachable through [com.intellij.psi.PsiReference], so a rename that only followed
 * references would leave every one of them broken.
 */
object EnRenameFamily {

    /**
     * Guards against a runaway walk on a widely-overridden base method such as `OnUpdate`. Reaching
     * it means the family is far larger than any deliberate rename, and the guard in
     * [EnRenameGuard] will have refused the refactoring long before, since a hierarchy that large
     * always reaches vanilla.
     */
    private const val LIMIT = 500

    /** Every declaration that must be renamed alongside [element], excluding [element] itself. */
    fun of(element: PsiElement): List<PsiElement> = when (element) {
        is EnMethodDeclMixin -> methodFamily(element)
        is EnClassDeclMixin -> classFamily(element)
        else -> emptyList()
    }

    /** The transitive closure over "overrides" and "is overridden by", in both directions. */
    private fun methodFamily(method: EnMethodDeclMixin): List<PsiElement> {
        val seen = LinkedHashSet<EnMethodDeclMixin>()
        val queue = ArrayDeque<EnMethodDeclMixin>()
        seen += method
        queue += method
        while (queue.isNotEmpty() && seen.size < LIMIT) {
            for (neighbour in neighboursOf(queue.removeFirst())) {
                if (seen.add(neighbour)) queue += neighbour
            }
        }
        return seen.filter { it !== method }
    }

    private fun neighboursOf(method: EnMethodDeclMixin): List<EnMethodDeclMixin> =
        EnOverrideResolution.superDeclarationsOf(method) + overridersOf(method)

    private fun overridersOf(method: EnMethodDeclMixin): List<EnMethodDeclMixin> {
        val name = method.name ?: return emptyList()
        val owner = PsiTreeUtil.getParentOfType(method, EnClassDeclMixin::class.java) ?: return emptyList()
        return EnClassHierarchy.subtypesOf(owner)
            .flatMap { EnOverrideResolution.directMethodsNamed(it, name) }
    }

    /**
     * Every other declaration of the class's name — the base and its `modded` layers — plus the
     * constructors and destructors of all of them, whose names track the class's.
     */
    private fun classFamily(cls: EnClassDeclMixin): List<PsiElement> {
        val name = cls.name ?: return emptyList()
        val layers = EnTypeInference.classesNamed(name, cls.project).filter { it !== cls }
        val family = ArrayList<PsiElement>(layers)
        for (layer in layers + cls) family += structors(layer, name)
        return family
    }

    /** `void Foo()` and `void ~Foo()` inside `class Foo` — bound to the class by name only. */
    private fun structors(owner: EnClassDeclMixin, name: String): List<EnMethodDeclMixin> {
        val body = owner.node.findChildByType(EnfusionTypes.CLASS_BODY)?.psi ?: return emptyList()
        return PsiTreeUtil.getChildrenOfTypeAsList(body, EnMethodDeclMixin::class.java)
            .filter { it.name == name }
    }
}
