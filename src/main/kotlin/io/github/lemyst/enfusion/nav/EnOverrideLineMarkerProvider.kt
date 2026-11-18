package io.github.lemyst.enfusion.nav

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnOverrideResolution
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * The gutter icon on a method that overrides or re-declares a same-named method elsewhere -- another
 * `modded class` layer, or a superclass. See [EnOverrideResolution] for why this exists at all: the
 * method's own name is a declaration, not a reference, so nothing else links it back.
 *
 * On a `modded class`, the same icon leads to the class it patches and to the other layers. Its
 * downward icon lists only subclasses, so without this nothing in the gutter led from a mod's
 * layer to the vanilla class.
 *
 * Must be called only for leaf elements (the platform's contract for this extension point), which is
 * why it matches on the identifier itself rather than the whole `EnMethodDeclMixin`.
 */
class EnOverrideLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? =
        when (val parent = element.parent) {
            is EnMethodDeclMixin -> if (parent.nameIdentifier === element) methodMarker(parent, element) else null
            is EnClassDeclMixin -> if (parent.nameIdentifier === element && parent.isModded()) layerMarker(parent, element) else null
            else -> null
        }

    private fun methodMarker(method: EnMethodDeclMixin, leaf: PsiElement): LineMarkerInfo<*>? {
        val targets = EnOverrideResolution.superDeclarationsOf(method)
        if (targets.isEmpty()) return null

        return NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridingMethod)
            .setTargets(targets)
            .setTooltipText("Go to overridden method")
            .createLineMarkerInfo(leaf)
    }

    private fun layerMarker(cls: EnClassDeclMixin, leaf: PsiElement): LineMarkerInfo<*>? {
        val name = cls.name ?: return null
        // The original first: it is the one usually wanted, and the popup keeps this order.
        val others = EnTypeInference.classesNamed(name, cls.project).filter { it !== cls }.sortedBy { it.isModded() }
        if (others.isEmpty()) return null
        return NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridingMethod)
            .setTargets(others)
            .setTooltipText(layerText(name, others.count { !it.isModded() }, others.count { it.isModded() }))
            .setPopupTitle("$name and Its Other Layers")
            .createLineMarkerInfo(leaf)
    }

    internal companion object {
        fun layerText(name: String, originals: Int, layers: Int): String =
            listOfNotNull(
                if (originals > 0) "Patches $name" else null,
                if (layers > 0) "${if (originals > 0) "beside" else "Beside"} $layers other ${if (layers == 1) "layer" else "layers"}" else null,
            ).joinToString(", ")
    }
}
