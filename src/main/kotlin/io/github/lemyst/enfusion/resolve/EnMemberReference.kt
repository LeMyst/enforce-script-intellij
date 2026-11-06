package io.github.lemyst.enfusion.resolve

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.index.EnfusionIndexes
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.rename.EnIdentifierRename

/**
 * Resolves the member half of `Qualifier.member` — `Math.PI2`, `BattleRoyaleUtils.Debug(...)`,
 * `BattleRoyaleServer.GetInstance()`.
 *
 * The qualifier may name a type (`Math.PI2`), be a value whose declared type must be read
 * (`player.GetIdentity()`), or be a call whose return type carries the chain
 * (`GetZone().GetArea()`). [EnTypeInference] answers all three; the member is then looked up in
 * those classes and their superclasses.
 *
 * Because the reference exists in both directions, this is also what makes Find Usages work on a
 * static member's declaration: without a reference pointing at it, a declaration has no usages to
 * find.
 */
class EnMemberReference(element: PsiElement, rangeInElement: TextRange) :
    PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project)
            .resolveWithCaching(this, RESOLVER, /* needToPreventRecursion = */ true, incompleteCode)

    override fun handleElementRename(newElementName: String): PsiElement =
        EnIdentifierRename.replaceIdentifier(element, rangeInElement, newElementName)

    private companion object {

        private val RESOLVER = object : ResolveCache.PolyVariantResolver<EnMemberReference> {

            override fun resolve(ref: EnMemberReference, incompleteCode: Boolean): Array<ResolveResult> {
                val project = ref.element.project
                if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY

                val name = ref.value
                if (name.isEmpty()) return ResolveResult.EMPTY_ARRAY

                // The qualifier may name a type (`Math.PI2`) or be a value whose type must be
                // inferred (`player.GetIdentity()`); EnTypeInference handles both, plus chained
                // calls such as `GetZone().GetArea()`.
                val qualifier = ref.element.children.firstOrNull { it !is com.intellij.psi.PsiWhiteSpace }

                // `super.EEKilled(killer)` -- `super` has no type of its own to infer; what it
                // reaches depends on the enclosing class, and must exclude that class's own body.
                if (qualifier?.node?.elementType === EnfusionTypes.SUPER_EXPR) {
                    val owner = PsiTreeUtil.getParentOfType(ref.element, EnClassDeclMixin::class.java)
                        ?: return ResolveResult.EMPTY_ARRAY
                    val targets = EnTypeInference.findSuperMembers(owner, name, project)
                    return PsiElementResolveResult.createResults(EnOverloadFilter.narrow(targets, ref.element))
                }

                // `CallType.Client` -- an enum is not a class, so classesOf finds nothing for it and
                // the constant lives directly inside the ENUM_DECL the qualifier names.
                enumConstants(qualifier, name).let {
                    if (it.isNotEmpty()) return PsiElementResolveResult.createResults(it)
                }

                val owners = EnTypeInference.classesOf(qualifier, project)
                if (owners.isEmpty()) return ResolveResult.EMPTY_ARRAY

                val targets = LinkedHashSet<PsiElement>()
                for (owner in owners) EnTypeInference.findMembers(owner, name, project, targets)
                val narrowed = EnOverloadFilter.narrow(targets.toList(), ref.element)
                return PsiElementResolveResult.createResults(narrowed)
            }

            /**
             * Constants of the enum [qualifier] names, when it names one at all.
             *
             * Resolving a member qualifier resolves its own qualifier in turn, down to the head of
             * the chain, so this recursion is as deep as the chain is long. It counts against the
             * same nesting cap as type inference, or a long enough `x.b.b.b` overflowed the stack.
             */
            private fun enumConstants(qualifier: PsiElement?, name: String): List<PsiElement> {
                val reference = qualifier?.reference as? PsiPolyVariantReference ?: return emptyList()
                val found = ArrayList<PsiElement>()
                val results = EnTypeInference.nested(ResolveResult.EMPTY_ARRAY) { reference.multiResolve(false) }
                for (result in results) {
                    val declaration = result.element ?: continue
                    if (declaration.node?.elementType !== EnfusionTypes.ENUM_DECL) continue
                    PsiTreeUtil.findChildrenOfType(declaration, EnNamedElement::class.java)
                        .filterTo(found) {
                            it.name == name && it.node?.elementType === EnfusionTypes.ENUM_CONSTANT
                        }
                }
                return found
            }
        }
    }
}
