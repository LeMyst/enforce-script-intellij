package io.github.lemyst.enfusion.refs

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.github.lemyst.enfusion.index.EnfusionObjectIndexes
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectFile
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnLiteralExprMixin
import io.github.lemyst.enfusion.resolve.EnConstantEvaluator
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * A widget name: the string handed to `FindAnyWidget` and friends.
 *
 * The interesting part is scoping. Layouts carry no `scriptclass`, so nothing in the file says which
 * class owns it; the link runs the other way, through the `CreateWidgets("…layout")` call in the
 * class that loads it. Resolution therefore starts from the enclosing class, collects every layout
 * that class loads, and searches only those — which is both precise and cheap.
 *
 * Only when the class loads no layout at all does it fall back to the global index. That happens for
 * helper classes whose widgets were created by a parent, and there the answer is genuinely ambiguous.
 */
class EnWidgetNameReference(element: PsiElement, rangeInElement: TextRange) :
    PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY
        val name = value
        if (name.isEmpty()) return ResolveResult.EMPTY_ARRAY
        return PsiElementResolveResult.createResults(EnWidgetTargets.resolve(element, name))
    }

    /** Widget names of the layouts this class loads — the completion a reader actually wants. */
    override fun getVariants(): Array<Any> {
        val project = element.project
        if (DumbService.isDumb(project)) return emptyArray()
        return EnWidgetTargets.namesInScope(element).toTypedArray()
    }
}

object EnWidgetTargets {

    /** How far up the superclass chain to keep looking for a layout. */
    private const val MAX_SUPERS = 8

    val CALLS = setOf("FindAnyWidget", "FindWidget", "FindAnyWidgetById")

    /** Calls whose string argument names a layout to load. */
    private val LOADERS = setOf("CreateWidgets")

    fun resolve(element: PsiElement, name: String): List<PsiElement> {
        val scoped = layoutsOf(element).flatMap { file -> declarationsNamed(file, name) }
        if (scoped.isNotEmpty()) return scoped
        return globalFallback(element, name)
    }

    fun namesInScope(element: PsiElement): List<String> =
        layoutsOf(element)
            .flatMap { file -> PsiTreeUtil.findChildrenOfType(file, EnObjDeclMixin::class.java) }
            .mapNotNull { it.name }
            .distinct()

    /** Every widget of that name anywhere, used only when the class scope produced nothing. */
    private fun globalFallback(element: PsiElement, name: String): List<PsiElement> {
        val project = element.project
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        return EnfusionObjectIndexes.findFiles(EnfusionObjectIndexes.WIDGETS, name, scope)
            .mapNotNull { psiManager.findFile(it) }
            .flatMap { declarationsNamed(it, name) }
    }

    private fun declarationsNamed(file: com.intellij.psi.PsiFile, name: String): List<PsiElement> =
        PsiTreeUtil.findChildrenOfType(file, EnObjDeclMixin::class.java).filter { it.name == name }

    /**
     * The layout files reachable from the class enclosing [element].
     *
     * Cached per class: a class typically holds several `FindAnyWidget` calls and each would
     * otherwise repeat the same tree walk and the same filename lookups.
     */
    private fun layoutsOf(element: PsiElement): List<com.intellij.psi.PsiFile> {
        val owner = PsiTreeUtil.getParentOfType(element, EnClassDeclMixin::class.java)
            ?: return emptyList()
        return CachedValuesManager.getCachedValue(owner) {
            CachedValueProvider.Result.create(
                computeLayouts(owner),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }
    }

    private fun computeLayouts(owner: EnClassDeclMixin): List<com.intellij.psi.PsiFile> {
        val project = owner.project
        val psiManager = PsiManager.getInstance(project)
        val files = LinkedHashSet<VirtualFile>()
        for (layer in relatedClasses(owner)) {
            for (path in layoutPathsIn(layer)) {
                files += EnAssetPaths.findBySuffix(project, path)
            }
        }
        return files.mapNotNull { psiManager.findFile(it) }.filterIsInstance<EnfusionObjectFile>()
    }

    /**
     * The class itself, its `modded class` layers, and its superclasses.
     *
     * All three matter: a HUD row is often created by a base class and populated by a modded layer,
     * and the `CreateWidgets` call may sit in any of them.
     */
    private fun relatedClasses(owner: EnClassDeclMixin): List<EnClassDeclMixin> {
        val project = owner.project
        val result = LinkedHashSet<EnClassDeclMixin>()
        result += owner

        var names = listOfNotNull(owner.name)
        val visited = HashSet<String>(names)
        var depth = 0
        while (names.isNotEmpty() && depth <= MAX_SUPERS) {
            val layers = names.flatMap { EnTypeInference.classesNamed(it, project) }
            result += layers
            names = layers.mapNotNull { it.getSuperName()?.substringBefore('<')?.trim() }
                .filter { visited.add(it) }
            depth++
        }
        return result.toList()
    }

    /** Folded `.layout` paths passed to a widget-creating call inside [owner]. */
    private fun layoutPathsIn(owner: EnClassDeclMixin): List<String> {
        val paths = ArrayList<String>()
        for (literal in PsiTreeUtil.findChildrenOfType(owner, EnLiteralExprMixin::class.java)) {
            val content = EnStringLiterals.content(literal) ?: continue
            if (!content.endsWith(".layout", ignoreCase = true)) continue
            // Restricting to loader calls keeps a layout name mentioned in a log message from
            // widening the search scope.
            if (!isLoaderArgument(literal)) continue
            paths += EnConstantEvaluator.evaluate(EnConstantEvaluator.concatenationAround(literal))
                ?: content
        }
        return paths
    }

    private fun isLoaderArgument(literal: PsiElement): Boolean {
        val site = EnCallArg.siteOf(literal) ?: return false
        return site.calleeName in LOADERS
    }
}

/** Attaches [EnWidgetNameReference] to argument 0 of a widget lookup. */
class EnWidgetNameReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        if (element.node?.elementType !== EnfusionTypes.LITERAL_EXPR) return PsiReference.EMPTY_ARRAY
        if (!EnCallArg.isArgumentOf(element, EnWidgetTargets.CALLS, 0)) return PsiReference.EMPTY_ARRAY
        val range = EnStringLiterals.contentRange(element) ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(EnWidgetNameReference(element, range))
    }
}
