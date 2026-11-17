package io.github.lemyst.enfusion.resolve

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * The other direction from [EnOverrideResolution]: who patches or extends a class, and who
 * redeclares one of its methods. *When two mods conflict over `OnScheduledTick`, who else
 * redeclares it?* had no answer short of Find in Files on the name.
 *
 * Below a class sit:
 *  - for an original declaration, its `modded class` layers. A layer has no layers below it: there
 *    is no load order the plugin can know, so the layers of one class are all side by side, and
 *    the existing icon on a layer already links the others.
 *  - every class that extends it, transitively, with each one's layers.
 *
 * Computed once per class and cached on it, together with each method name declared below, so the
 * hundreds of methods of vanilla's `PlayerBase` cost one walk, not one each.
 */
object EnOverriders {

    /**
     * Bound on the classes walked. `EntityAI` has thousands of descendants in vanilla alone; past
     * this the list is incomplete but the editor stays responsive.
     */
    private const val MAX_CLASSES = 5000

    class Below(
        /** The `modded class` layers of an original declaration. */
        val layers: List<EnClassDeclMixin>,
        /** Every class extending it, transitively, with every layer of each. */
        val subclasses: List<EnClassDeclMixin>,
        /** Each method name declared in any of those, with its declarations. */
        val methods: Map<String, List<EnMethodDeclMixin>>,
    ) {
        val isEmpty: Boolean get() = layers.isEmpty() && subclasses.isEmpty()
    }

    private val EMPTY = Below(emptyList(), emptyList(), emptyMap())

    fun below(cls: EnClassDeclMixin): Below {
        if (DumbService.isDumb(cls.project)) return EMPTY
        return CachedValuesManager.getCachedValue(cls) {
            CachedValueProvider.Result.create(compute(cls), PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    /**
     * Declarations of [method] below its class with as many parameters: the modded layers'
     * redeclarations of an original's method, and the subclasses' overrides.
     */
    fun overridersOf(method: EnMethodDeclMixin): List<EnMethodDeclMixin> {
        val name = method.name ?: return emptyList()
        if (method.isStatic() || method.isConstructor() || method.isDestructor()) return emptyList()
        val owner = PsiTreeUtil.getParentOfType(method, EnClassDeclMixin::class.java) ?: return emptyList()
        val arity = arityOf(method)
        return below(owner).methods[name].orEmpty().filter { it !== method && !it.isStatic() && arityOf(it) == arity }
    }

    private fun compute(cls: EnClassDeclMixin): Below {
        val project = cls.project
        val name = cls.name ?: return EMPTY
        val layers = if (cls.isModded()) emptyList() else EnTypeInference.classesNamed(name, project).filter { it.isModded() }

        val subclasses = LinkedHashSet<EnClassDeclMixin>()
        val visited = HashSet<String>()
        visited += name
        val queue = ArrayDeque<String>()
        queue += name
        while (queue.isNotEmpty() && subclasses.size < MAX_CLASSES) {
            ProgressManager.checkCanceled()
            for (child in EnClassHierarchy.extendingClasses(queue.removeFirst(), project)) {
                val childName = child.name ?: continue
                if (!visited.add(childName)) continue
                subclasses += EnTypeInference.classesNamed(childName, project)
                queue += childName
            }
        }
        subclasses -= cls

        val methods = HashMap<String, MutableList<EnMethodDeclMixin>>()
        for (below in layers + subclasses) {
            val body = below.node.findChildByType(EnfusionTypes.CLASS_BODY)?.psi ?: continue
            for (method in PsiTreeUtil.getChildrenOfTypeAsList(body, EnMethodDeclMixin::class.java)) {
                val methodName = method.name ?: continue
                methods.getOrPut(methodName) { ArrayList() } += method
            }
        }
        return Below(layers, subclasses.toList(), methods)
    }

    fun arityOf(method: EnMethodDeclMixin): Int {
        val list = method.node.findChildByType(EnfusionTypes.PARAM_LIST) ?: return 0
        return list.getChildren(null).count { it.elementType === EnfusionTypes.PARAM }
    }
}
