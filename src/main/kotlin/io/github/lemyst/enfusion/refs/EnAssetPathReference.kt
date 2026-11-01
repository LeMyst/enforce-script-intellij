package io.github.lemyst.enfusion.refs

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.util.ProcessingContext
import io.github.lemyst.enfusion.resolve.EnConstantEvaluator

/**
 * A path to a file that ships inside a PBO — a layout, an imageset, a texture, an XML data file.
 *
 * Resolution is a **longest-suffix match**, not a prefix rewrite, and that choice is what makes one
 * rule cover every form the codebase uses. `"BattleRoyale/GUI/layouts/hud/br_hud.layout"` is
 * written relative to the PBO prefix; `BR_PARTY_PREFIX + "GUI/layouts/party_hud.layout"` is
 * written relative to an addon root; vanilla writes `"gui/layouts/…"` relative to the work drive.
 * Matching from the right treats all three the same and needs no map of prefixes to directories —
 * which matters because that map lives in `config.cpp` and `$PBOPREFIX$` files this plugin does not
 * read.
 */
class EnAssetPathReference(element: PsiElement, rangeInElement: TextRange) :
    PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project)
            .resolveWithCaching(this, RESOLVER, /* needToPreventRecursion = */ true, incompleteCode)

    private companion object {

        private val RESOLVER = object : ResolveCache.PolyVariantResolver<EnAssetPathReference> {

            override fun resolve(
                ref: EnAssetPathReference,
                incompleteCode: Boolean,
            ): Array<ResolveResult> {
                val element = ref.element
                val project = element.project
                if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY

                // Fold the whole `+` chain, so the prefix constant contributes its segments to the
                // match. Falling back to this literal alone is still useful when the chain contains
                // a runtime value: a suffix match does not need the leading segments.
                val path = EnConstantEvaluator.evaluate(EnConstantEvaluator.concatenationAround(element))
                    ?: ref.value
                val targets = EnAssetPaths.findBySuffix(project, path)
                if (targets.isEmpty()) return ResolveResult.EMPTY_ARRAY

                val psiManager = PsiManager.getInstance(project)
                val files = targets.mapNotNull { psiManager.findFile(it) }
                return PsiElementResolveResult.createResults(files)
            }
        }
    }
}

/**
 * Attaches [EnAssetPathReference] to any literal that names a packaged file.
 *
 * Triggered by this literal's own extension rather than by the folded value, so the reference lands
 * on the fragment that actually carries the file name — which is where a reader points the cursor,
 * and which keeps the highlighted range inside one literal when the path is concatenated.
 */
class EnAssetPathReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val content = EnStringLiterals.content(element) ?: return PsiReference.EMPTY_ARRAY
        if (EXTENSIONS.none { content.endsWith(it, ignoreCase = true) }) return PsiReference.EMPTY_ARRAY
        // A bare extension with no name behind it is not a path.
        if (content.substringAfterLast('/').startsWith('.')) return PsiReference.EMPTY_ARRAY
        val range = EnStringLiterals.contentRange(element) ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(EnAssetPathReference(element, range))
    }

    private companion object {
        /**
         * Deliberately narrow. Every one of these is a file the engine loads by path at runtime, so
         * a literal ending in one is a path with near-certainty — unlike, say, `.c`, which appears
         * in log messages far more often than in a load call.
         */
        private val EXTENSIONS = listOf(
            ".layout", ".imageset", ".edds", ".xml", ".json",
            // Added for `config.cpp`, which is where textures and models are named: `picture`,
            // `logo`, `logoSmall` and `model` all carry one.
            ".paa", ".p3d",
        )
    }
}
