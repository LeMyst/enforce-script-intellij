package io.github.lemyst.enfusion.refs

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin
import io.github.lemyst.enfusion.index.EnfusionConfigIndexes
import io.github.lemyst.enfusion.rename.EnIdentifierRename

/**
 * The `Parent` in `class BREffectArea : EffectArea`.
 *
 * Poly-variant, and unavoidably so: config classes live in one flat namespace spread over hundreds of
 * addons, a forward declaration is a real declaration too, and vanilla plus Expansion routinely
 * declare the same name. Offering every candidate is more honest than picking one.
 *
 * Soft, because the parent very often lives in a config the plugin cannot see — `P:\DZ` is opt-in,
 * and a great many base classes only exist inside binarised `config.bin` files that are not text at
 * all. An unresolved parent is normal here and must never be painted as an error.
 */
class EnCfgSuperReference(element: PsiElement, rangeInElement: TextRange) :
    PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project)
            .resolveWithCaching(this, RESOLVER, /* needToPreventRecursion = */ false, incompleteCode)

    override fun handleElementRename(newElementName: String): PsiElement =
        EnIdentifierRename.replaceConfigIdentifier(element, rangeInElement, newElementName)

    private companion object {

        private val RESOLVER = object : ResolveCache.PolyVariantResolver<EnCfgSuperReference> {

            override fun resolve(ref: EnCfgSuperReference, incompleteCode: Boolean): Array<ResolveResult> {
                val project = ref.element.project
                if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY

                val name = ref.value
                if (name.isEmpty()) return ResolveResult.EMPTY_ARRAY

                // allScope, not resolveScope: the vanilla configs arrive as a SyntheticLibrary,
                // which has no OrderEntry and so is invisible to a module scope.
                val scope = GlobalSearchScope.allScope(project)
                val psiManager = PsiManager.getInstance(project)
                val targets = ArrayList<PsiElement>()
                for (file in EnfusionConfigIndexes.findFiles(EnfusionConfigIndexes.CLASSES, name, scope)) {
                    val psiFile = psiManager.findFile(file) ?: continue
                    PsiTreeUtil.findChildrenOfType(psiFile, EnCfgClassMixin::class.java)
                        .filterTo(targets) { it.name == name && it !== ref.element.parent }
                }
                return PsiElementResolveResult.createResults(targets)
            }
        }
    }
}
