package io.github.lemyst.enfusion.refs

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjValueMixin

/**
 * The same content-matched providers, attached to values inside a `.layout` or `.imageset`.
 *
 * Layouts carry exactly the string kinds script does — `text "#STR_BR_WIN"` names a stringtable
 * row, `image0 "set:dayz_gui image:DayZLogo"` names an imageset entry, and an imageset's
 * `path "{GUID}…/dayzbr_gui.edds"` names a texture. Because those three providers match on content
 * rather than on call shape, they transplant to a second language unchanged.
 *
 * Widget names are absent on purpose: inside a layout a widget name is a *declaration*, not a
 * reference to one.
 */
class EnfusionObjectReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        val value = psiElement(EnObjValueMixin::class.java)
        registrar.registerReferenceProvider(value, StringTableKeyReferenceProvider())
        registrar.registerReferenceProvider(value, EnImageSetReferenceProvider())
        registrar.registerReferenceProvider(value, EnAssetPathReferenceProvider())
    }
}

/** The layout-side counterpart of [EnStringLiteralManipulator]; see there for why one is required. */
class EnObjValueManipulator : AbstractElementManipulator<EnObjValueMixin>() {

    override fun getRangeInElement(element: EnObjValueMixin): TextRange =
        EnStringLiterals.contentRange(element) ?: TextRange.from(0, element.textLength)

    override fun handleContentChange(
        element: EnObjValueMixin,
        range: TextRange,
        newContent: String,
    ): EnObjValueMixin {
        val updated = range.replace(element.text, newContent)
        val replacement = EnStringLiterals.createObjectValue(element.project, updated) ?: return element
        return element.replace(replacement) as EnObjValueMixin
    }
}
