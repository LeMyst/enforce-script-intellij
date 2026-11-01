package io.github.lemyst.enfusion.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.ContributedReferenceHost
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry

/**
 * A literal expression, and — when it is a string — a reference host.
 *
 * `getReferences()` has to be overridden here rather than left to the platform. `PsiElementBase`
 * delegates it to `SharedPsiElementImplUtil.getReferences()`, which only ever calls
 * `getReference()`; the provider registry is consulted for `ContributedReferenceHost` and
 * `PsiLanguageInjectionHost` and nothing else. Implementing the marker interface and making the
 * registry call explicitly is what puts Grammar-Kit PSI back on the ordinary
 * `<psi.referenceContributor>` path.
 *
 * The plural form matters beyond registration: `"set:battleroyale_gui image:DayZBRLogo_White"`
 * carries two independent targets in one literal, and only `getReferences()` can express that.
 */
abstract class EnLiteralExprMixin(node: ASTNode) : ASTWrapperPsiElement(node), ContributedReferenceHost {

    override fun getReferences(): Array<PsiReference> =
        ReferenceProvidersRegistry.getReferencesFromProviders(this)

    /**
     * Ctrl+Click goes through `getReferences()`, but plenty of platform code still asks for the
     * single reference — so answer with the first rather than with the inherited null.
     */
    override fun getReference(): PsiReference? = references.firstOrNull()
}
