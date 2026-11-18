package io.github.lemyst.enfusion.nav

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnOverriders

/**
 * The gutter icon pointing down: on a class, to its `modded class` layers and its subclasses; on
 * a method, to the layers that redeclare it and the subclasses that override it.
 *
 * Slow markers only, computed in the background once the editor is done, since the walk below
 * `EntityAI` reaches thousands of classes. It works in read-only library files too: vanilla's
 * files are where *who patches this?* comes up most.
 */
class EnOverriddenLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        for (element in elements) {
            ProgressManager.checkCanceled()
            when (val parent = element.parent) {
                is EnClassDeclMixin -> if (parent.nameIdentifier === element) classMarker(parent, element)?.let(result::add)
                is EnMethodDeclMixin -> if (parent.nameIdentifier === element) methodMarker(parent, element)?.let(result::add)
            }
        }
    }

    private fun classMarker(cls: EnClassDeclMixin, leaf: PsiElement): LineMarkerInfo<*>? {
        val below = EnOverriders.below(cls)
        if (below.isEmpty) return null
        return NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridenMethod)
            .setTargets(below.layers + below.subclasses)
            .setTooltipText(EnOverriddenText.forClass(below.layers.size, below.subclasses.size))
            .setPopupTitle("Layers and Subclasses of ${cls.name}")
            .createLineMarkerInfo(leaf)
    }

    private fun methodMarker(method: EnMethodDeclMixin, leaf: PsiElement): LineMarkerInfo<*>? {
        val overriders = EnOverriders.overridersOf(method)
        if (overriders.isEmpty()) return null
        val layers = overriders.count { EnOverriddenText.isSameClass(it, method) }
        return NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridenMethod)
            .setTargets(overriders)
            .setTooltipText(EnOverriddenText.forMethod(layers, overriders.size - layers))
            .setPopupTitle("Redeclarations of ${method.name}")
            .createLineMarkerInfo(leaf)
    }
}

internal object EnOverriddenText {

    fun forClass(layers: Int, subclasses: Int): String =
        listOfNotNull(
            if (layers > 0) "Modded by ${count(layers, "layer")}" else null,
            if (subclasses > 0) "${if (layers > 0) "e" else "E"}xtended by ${count(subclasses, "subclass", "subclasses")}" else null,
        ).joinToString(", ")

    fun forMethod(layers: Int, subclasses: Int): String =
        listOfNotNull(
            if (layers > 0) "Redeclared by ${count(layers, "modded layer")}" else null,
            if (subclasses > 0) "${if (layers > 0) "o" else "O"}verridden in ${count(subclasses, "subclass", "subclasses")}" else null,
        ).joinToString(", ")

    /** A redeclaration in another layer of the method's own class, rather than a subclass. */
    fun isSameClass(other: EnMethodDeclMixin, method: EnMethodDeclMixin): Boolean {
        val a = com.intellij.psi.util.PsiTreeUtil.getParentOfType(other, EnClassDeclMixin::class.java)?.name
        val b = com.intellij.psi.util.PsiTreeUtil.getParentOfType(method, EnClassDeclMixin::class.java)?.name
        return a != null && a == b
    }

    private fun count(n: Int, one: String, many: String = "${one}s"): String = "$n ${if (n == 1) one else many}"
}
