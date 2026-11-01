package io.github.lemyst.enfusion.refs

import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnLiteralExprMixin

/**
 * Tells the platform where the editable content of a string literal starts and ends.
 *
 * Registering one is not optional. Any reference occupying a sub-range of its element goes through
 * `ElementManipulators` sooner or later — completion computes its prefix from the manipulator's
 * range, and `PsiReferenceBase`'s no-range constructor throws outright when none is registered.
 */
class EnStringLiteralManipulator : AbstractElementManipulator<EnLiteralExprMixin>() {

    override fun getRangeInElement(element: EnLiteralExprMixin): TextRange =
        EnStringLiterals.contentRange(element) ?: TextRange.from(0, element.textLength)

    /**
     * Rewrites part of the literal — the whole of rename support for every name written as a string.
     *
     * The platform never edits a reference itself, it asks the manipulator to, which makes a no-op
     * here the most misleading possible stub: rename *appears* to work, the declaration changes, and
     * the usages silently do not.
     *
     * The range is content-relative and may be a sub-range: `"set:x image:y"` carries two
     * independent references, so only the half being renamed is replaced.
     */
    override fun handleContentChange(
        element: EnLiteralExprMixin,
        range: TextRange,
        newContent: String,
    ): EnLiteralExprMixin {
        val updated = range.replace(element.text, newContent)
        val replacement = EnStringLiterals.createLiteral(element.project, updated) ?: return element
        return element.replace(replacement) as EnLiteralExprMixin
    }
}

/** True when this literal is a string rather than a number, `true`/`false` or `null`. */
internal fun PsiElement.isEnStringLiteral(): Boolean =
    node?.findChildByType(EnfusionTypes.STRING) != null || elementType === EnfusionTypes.STRING
