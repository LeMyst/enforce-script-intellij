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
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.rename.EnIdentifierRename

/**
 * A bare identifier: a local, parameter, `foreach` variable, field, file-scope global, or a type
 * name used as a static qualifier.
 *
 * Resolution follows the language's shadowing order — innermost first — and stops at the first
 * scope that produces a hit, so a local named `Math` correctly wins over `class Math`.
 */
class EnIdentifierReference(element: PsiElement, rangeInElement: TextRange) :
    PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project)
            .resolveWithCaching(this, RESOLVER, /* needToPreventRecursion = */ true, incompleteCode)

    override fun handleElementRename(newElementName: String): PsiElement =
        EnIdentifierRename.replaceIdentifier(element, rangeInElement, newElementName)

    private companion object {

        private val RESOLVER = object : ResolveCache.PolyVariantResolver<EnIdentifierReference> {

            override fun resolve(
                ref: EnIdentifierReference,
                incompleteCode: Boolean,
            ): Array<ResolveResult> {
                val element = ref.element
                val project = element.project
                if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY
                val name = ref.value
                if (name.isEmpty()) return ResolveResult.EMPTY_ARRAY

                localsAndParameters(element, name).ifNotEmpty { return it }
                EnOverloadFilter.narrow(fieldsOfEnclosingClass(element, name), element).ifNotEmpty { return it }
                EnOverloadFilter.narrow(EnTypeInference.fileScopeDeclarationsNamed(name, project), element).ifNotEmpty { return it }
                // Any type name, not just a class: an enum used as a static qualifier
                // (`CallType.Client`) is the common case, and enums are not EnClassDeclMixin.
                return PsiElementResolveResult.createResults(
                    EnTypeInference.typesNamed(name, project),
                )
            }
        }

        private inline fun List<PsiElement>.ifNotEmpty(block: (Array<ResolveResult>) -> Unit) {
            if (isNotEmpty()) block(PsiElementResolveResult.createResults(this))
        }

        /**
         * Declarations inside the enclosing method: parameters, locals and `foreach` variables.
         *
         * Position is not compared. EnfusionScript has no use-before-declaration idiom worth
         * modelling here, and a name declared twice in one method is a compile error anyway.
         */
        private fun localsAndParameters(element: PsiElement, name: String): List<PsiElement> {
            val method = PsiTreeUtil.getParentOfType(element, EnMethodDeclMixin::class.java)
                ?: return emptyList()
            return PsiTreeUtil.findChildrenOfType(method, EnNamedElement::class.java)
                .filter { it.name == name && isLocal(it) }
        }

        private fun isLocal(element: EnNamedElement): Boolean =
            when (element.node?.elementType) {
                EnfusionTypes.PARAM, EnfusionTypes.FOREACH_VAR -> true
                // A varDeclarator that is not a member or a global is a local.
                EnfusionTypes.VAR_DECLARATOR -> !EnfusionIndexes.isSymbolDeclaration(element)
                else -> false
            }

        /**
         * Constructors and destructors are excluded. They are members named after the class, so
         * `PlayerBase.Cast(man)` written inside `modded class PlayerBase` used to stop here, on
         * `PlayerBase()` and `~PlayerBase()`, instead of reaching the type. Nothing can name one
         * in an expression anyway: construction is always `new X(...)`.
         */
        private fun fieldsOfEnclosingClass(element: PsiElement, name: String): List<PsiElement> {
            val owner = PsiTreeUtil.getParentOfType(element, EnClassDeclMixin::class.java)
                ?: return emptyList()
            return EnTypeInference.findMembers(owner, name, element.project)
                .filterNot { it is EnMethodDeclMixin && (it.isConstructor() || it.isDestructor()) }
        }
    }
}
