package io.github.lemyst.enfusion.refs

import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import io.github.lemyst.enfusion.psi.impl.EnLiteralExprMixin

/**
 * Registers every string-literal reference provider for EnfusionScript.
 *
 * All of them attach to the same element — `literalExpr` — and self-filter, because the interesting
 * distinctions cannot be expressed as element patterns. Two of the six are decided purely by the
 * string's own content, and the rest need "argument *n* of a call named X", which
 * [com.intellij.patterns.PlatformPatterns] has no vocabulary for; see [EnCallArg].
 *
 * This works only because [EnLiteralExprMixin] implements `ContributedReferenceHost` and calls the
 * registry itself. Grammar-Kit PSI is never consulted through the registry otherwise.
 */
class EnfusionReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        val literal = psiElement(EnLiteralExprMixin::class.java)
        registrar.registerReferenceProvider(literal, StringTableKeyReferenceProvider())
        registrar.registerReferenceProvider(literal, EnAssetPathReferenceProvider())
        registrar.registerReferenceProvider(literal, EnWidgetNameReferenceProvider())
        registrar.registerReferenceProvider(literal, EnImageSetReferenceProvider())
        registrar.registerReferenceProvider(literal, EnInputNameReferenceProvider())
        registrar.registerReferenceProvider(literal, EnMethodNameReferenceProvider())
    }
}
