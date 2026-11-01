package io.github.lemyst.enfusion.nav

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnOverrideResolution

/**
 * The gutter icon on a method that overrides or re-declares a same-named method elsewhere -- another
 * `modded class` layer, or a superclass. See [EnOverrideResolution] for why this exists at all: the
 * method's own name is a declaration, not a reference, so nothing else links it back.
 *
 * Must be called only for leaf elements (the platform's contract for this extension point), which is
 * why it matches on the identifier itself rather than the whole `EnMethodDeclMixin`.
 */
class EnOverrideLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val method = element.parent as? EnMethodDeclMixin ?: return null
        if (method.nameIdentifier !== element) return null

        val targets = EnOverrideResolution.superDeclarationsOf(method)
        if (targets.isEmpty()) return null

        return NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridingMethod)
            .setTargets(targets)
            .setTooltipText("Go to overridden method")
            .createLineMarkerInfo(element)
    }
}
