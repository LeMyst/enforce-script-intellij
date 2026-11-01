package io.github.lemyst.enfusion.resolve

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
import io.github.lemyst.enfusion.index.EnfusionIndexes
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.rename.EnIdentifierRename

/**
 * Resolves an identifier that names a type — a `typeRef` in a declaration, or the qualifier of a
 * static call such as `Math.Clamp(...)`.
 *
 * Deliberately **poly-variant**. `modded class X` layers over a previously compiled `X`, so a name
 * legitimately has several declarations: `PlayerBase` has five in this setup (vanilla plus four
 * modded layers). Several of those layers are gated by mutually exclusive `#ifdef SERVER` /
 * `#ifdef JM_COT`, which the plugin cannot evaluate — so it offers every candidate rather than
 * pretending to know which one is live.
 *
 * Marked *soft*: an unresolved name is not an error. Locals, parameters and Community Framework
 * symbols all legitimately fail to resolve today, and none of them should be painted red.
 */
class EnTypeReference(element: PsiElement, rangeInElement: TextRange) :
    PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project)
            .resolveWithCaching(this, RESOLVER, /* needToPreventRecursion = */ false, incompleteCode)

    override fun handleElementRename(newElementName: String): PsiElement =
        EnIdentifierRename.replaceIdentifier(element, rangeInElement, newElementName)

    private companion object {

        /**
         * Not a lambda: `PolyVariantResolver` carries a default bridge method and so is not
         * SAM-convertible from Kotlin.
         */
        private val RESOLVER = object : ResolveCache.PolyVariantResolver<EnTypeReference> {

            override fun resolve(ref: EnTypeReference, incompleteCode: Boolean): Array<ResolveResult> {
                val project = ref.element.project

                // Go to Declaration is DumbAware, so this runs during indexing. Querying a
                // FileBasedIndex then throws IndexNotReadyException.
                if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY

                val name = ref.value
                if (name.isEmpty()) return ResolveResult.EMPTY_ARRAY

                // allScope, NOT element.resolveScope: a module scope is built from OrderEntry roots
                // and a SyntheticLibrary has none, so resolveScope would silently exclude the whole
                // vanilla script root and every vanilla type would fail to resolve from mod code.
                val scope = GlobalSearchScope.allScope(project)
                val psiManager = PsiManager.getInstance(project)

                val targets = ArrayList<PsiElement>()
                for (file in EnfusionIndexes.findFiles(EnfusionIndexes.TYPES, name, scope)) {
                    val psiFile = psiManager.findFile(file) ?: continue
                    PsiTreeUtil.findChildrenOfType(psiFile, EnNamedElement::class.java)
                        .filterTo(targets) {
                            it.name == name && EnfusionIndexes.isTypeDeclaration(it)
                        }
                }
                return PsiElementResolveResult.createResults(targets)
            }
        }
    }
}
