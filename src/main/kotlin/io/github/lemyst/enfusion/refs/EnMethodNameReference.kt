package io.github.lemyst.enfusion.refs

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.github.lemyst.enfusion.index.EnfusionIndexes
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * A method named by a string rather than by a symbol — the language's dispatch idiom.
 *
 * `GetRPCManager().AddRPC(NS, "SetPlayerCount", this)` binds a handler, `AddTimer(5.0, this,
 * "OnPlayerCountChanged")` schedules one, `GetGame().GameScript.Call(this, "ProcessPlayers", NULL)`
 * starts a coroutine. In every case the compiler sees only a string, so nothing but a reference
 * provider connects the call to the method it runs.
 *
 * Where the call carries its target — nearly always `this` — the type is inferred and the answer is
 * exact. Where it does not, the name goes to the global symbol index instead, which is acceptable
 * precisely because dispatch names are long and distinctive.
 */
class EnMethodNameReference(
    element: PsiElement,
    rangeInElement: TextRange,
    private val shape: EnDispatchShapes.Shape,
    private val site: EnCallArg.Site,
) : PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY
        val name = value
        if (name.isEmpty()) return ResolveResult.EMPTY_ARRAY

        val owners = targetClasses(project)
        if (owners.isNotEmpty()) {
            val members = owners.flatMap { EnTypeInference.findMembers(it, name, project) }
                .filter { it.node?.elementType === EnfusionTypes.METHOD_DECL }
            if (members.isNotEmpty()) return PsiElementResolveResult.createResults(members)
        }

        if (!shape.fallbackGlobally) return ResolveResult.EMPTY_ARRAY
        return PsiElementResolveResult.createResults(methodsNamed(project, name))
    }

    override fun getVariants(): Array<Any> {
        val project = element.project
        if (DumbService.isDumb(project)) return emptyArray()
        return targetClasses(project)
            .flatMap { PsiTreeUtil.findChildrenOfType(it, EnMethodDeclMixin::class.java) }
            .mapNotNull { it.name }
            .distinct()
            .toTypedArray()
    }

    /**
     * Whether the call names an object whose type the plugin worked out.
     *
     * This is what separates "this handler does not exist" from "this shape carries no target, so
     * nothing could be checked" — the gate the unresolved-dispatch inspection runs behind.
     */
    fun hasKnownTarget(): Boolean = targetClasses(element.project).isNotEmpty()

    /** The classes the dispatch target belongs to; empty when the shape carries no target. */
    private fun targetClasses(project: Project) =
        shape.targetIndex
            ?.let { site.arguments.getOrNull(it) }
            ?.let { EnTypeInference.classesOf(it, project) }
            .orEmpty()

    /** Every method of that name anywhere, for the shapes that name no target. */
    private fun methodsNamed(project: Project, name: String): List<PsiElement> {
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val found = ArrayList<PsiElement>()
        for (file in EnfusionIndexes.findFiles(EnfusionIndexes.SYMBOLS, name, scope)) {
            val psiFile = psiManager.findFile(file) ?: continue
            PsiTreeUtil.findChildrenOfType(psiFile, EnMethodDeclMixin::class.java)
                .filterTo(found) { it.name == name }
        }
        return found
    }
}

/**
 * The call shapes that dispatch by method name, and where the name and target sit in each.
 *
 * Every entry is taken from a real signature rather than guessed: `Call` and `CallFunctionParams`
 * from `1_core/proto/enscript.c`, `Timer.Run` from `3_game/tools/tools.c`, `AddTimer` from this
 * mod's own `Timeable`, and the RPC trio from Community Framework's documented usage.
 */
object EnDispatchShapes {

    /**
     * @param targetIndex argument holding the object the method belongs to, or null when the shape
     *   carries none.
     * @param fallbackGlobally whether an unresolvable target should widen to a name-only search.
     *   Off for shapes whose name is too generic for that to be more help than noise.
     */
    data class Shape(val nameIndex: Int, val targetIndex: Int?, val fallbackGlobally: Boolean = true)

    private val SHAPES: Map<String, List<Shape>> = mapOf(
        // Community Framework. `SendRPC` and `RemoveRPC` name no target: the handler lives on
        // whichever class registered it, which the call site does not say.
        "AddRPC" to listOf(Shape(nameIndex = 1, targetIndex = 2)),
        "SendRPC" to listOf(Shape(nameIndex = 1, targetIndex = null)),
        "RemoveRPC" to listOf(Shape(nameIndex = 1, targetIndex = null)),

        // Engine coroutines and reflection.
        "Call" to listOf(Shape(nameIndex = 1, targetIndex = 0)),
        "CallFunction" to listOf(Shape(nameIndex = 1, targetIndex = 0)),
        "CallFunctionParams" to listOf(Shape(nameIndex = 1, targetIndex = 0)),

        // Timers. `Run` is a common enough method name that a name-only search would produce more
        // noise than answers, so it resolves through its target or not at all.
        "AddTimer" to listOf(Shape(nameIndex = 2, targetIndex = 1)),
        "Run" to listOf(Shape(nameIndex = 2, targetIndex = 1, fallbackGlobally = false)),
    )

    /** The shape matching [site], or null when this literal is not a dispatch name. */
    fun match(site: EnCallArg.Site): Shape? {
        val candidates = SHAPES[site.calleeName ?: return null] ?: return null
        return candidates.firstOrNull { it.nameIndex == site.index }
    }
}

/** Attaches [EnMethodNameReference] to the name argument of a dispatch call. */
class EnMethodNameReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        if (element.node?.elementType !== EnfusionTypes.LITERAL_EXPR) return PsiReference.EMPTY_ARRAY
        val site = EnCallArg.siteOf(element) ?: return PsiReference.EMPTY_ARRAY
        val shape = EnDispatchShapes.match(site) ?: return PsiReference.EMPTY_ARRAY
        val range = EnStringLiterals.contentRange(element) ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(EnMethodNameReference(element, range, shape, site))
    }
}
