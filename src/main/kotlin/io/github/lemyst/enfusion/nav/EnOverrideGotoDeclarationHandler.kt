package io.github.lemyst.enfusion.nav

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnOverrideResolution

/**
 * Makes Ctrl+Click on an `override` method's own name jump to what it overrides.
 *
 * Ordinary Ctrl+Click follows a [com.intellij.psi.PsiReference], and a declaration's name is not
 * one -- it already is the target everyone else's reference resolves to. Without this handler,
 * clicking your own `override void ConnectFromCLI()` did nothing at all, which is indistinguishable
 * from a broken reference unless you already know the platform's rule. The gutter icon
 * ([EnOverrideLineMarkerProvider]) offers the same targets visually; this is what makes the more
 * obvious gesture work too.
 */
class EnOverrideGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val method = sourceElement?.parent as? EnMethodDeclMixin ?: return null
        if (method.nameIdentifier !== sourceElement) return null
        val targets = EnOverrideResolution.superDeclarationsOf(method)
        return targets.ifEmpty { null }?.toTypedArray()
    }
}
