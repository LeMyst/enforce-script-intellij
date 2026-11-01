package io.github.lemyst.enfusion.resolve

import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * Narrows a name-only method resolution down to the overloads whose parameter count could actually
 * accept the call -- `game.c` declares both `string GetWorldName()` and
 * `proto void GetWorldName( out string world_name )` on the same class, and `GetGame().GetWorldName()`
 * should resolve to the first alone, not to both. [EnTypeInference.findMembers] and the symbol index
 * both match by name only; nothing upstream of this knows how many arguments the call site passed.
 *
 * Deliberately conservative in two ways: it only ever removes candidates, never widens the set beyond
 * what was already found, and it backs off to the untouched set whenever the count is not actually
 * known (not a call at all) or narrowing would eliminate everything (an unusual arity this cannot
 * account for -- ellipsis-like idioms, a miscounted default -- is better shown than hidden).
 */
object EnOverloadFilter {

    fun narrow(candidates: List<PsiElement>, refElement: PsiElement): List<PsiElement> {
        val methods = candidates.filterIsInstance<EnMethodDeclMixin>()
        if (methods.size <= 1) return candidates
        val argCount = callArgCount(refElement) ?: return candidates

        val accepting = methods.filter { argCount in it.minParams()..it.maxParams() }
        if (accepting.isEmpty() || accepting.size == methods.size) return candidates

        val nonMethods = candidates.filter { it !is EnMethodDeclMixin }
        return accepting + nonMethods
    }

    /** The argument count of the `callExpr` [refElement] is the callee of, or null when it is not one. */
    private fun callArgCount(refElement: PsiElement): Int? {
        val call = refElement.parent ?: return null
        if (call.node?.elementType !== EnfusionTypes.CALL_EXPR) return null
        if (call.children.firstOrNull() !== refElement) return null
        val argList = call.children.getOrNull(1) ?: return null
        if (argList.node?.elementType !== EnfusionTypes.ARG_LIST) return null
        return argList.children.size
    }

    private fun EnMethodDeclMixin.params(): List<PsiElement> {
        val paramList = node.findChildByType(EnfusionTypes.PARAM_LIST)?.psi ?: return emptyList()
        return paramList.children.filter { it.node?.elementType === EnfusionTypes.PARAM }
    }

    private fun EnMethodDeclMixin.maxParams(): Int = params().size

    /** A param with a default value (`EQ expr`) is not required at the call site. */
    private fun EnMethodDeclMixin.minParams(): Int =
        params().count { it.node?.findChildByType(EnfusionTypes.EQ) == null }
}
