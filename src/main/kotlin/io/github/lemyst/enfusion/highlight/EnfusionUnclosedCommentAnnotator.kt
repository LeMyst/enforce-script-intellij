package io.github.lemyst.enfusion.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectTypes
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Reports a block comment with no closing `*` `/`, in scripts, layouts and configs.
 *
 * All three lexers let an unclosed comment run to the end of the file as one comment, because the
 * alternative, falling back to a lone `/` and rescanning from every later opener, is quadratic in
 * the file size. The parser therefore accepts it, and this is what still flags it. The error sits on
 * the opener alone: the comment covers the rest of the file, and underlining all of it would bury
 * every other report.
 */
class EnfusionUnclosedCommentAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val type = element.node?.elementType ?: return
        if (type !== EnfusionTypes.BLOCK_COMMENT && type !== EnfusionObjectTypes.BLOCK_COMMENT &&
            type !== EnfusionConfigTypes.BLOCK_COMMENT
        ) return
        val text = element.text
        // `/*/` ends in `*/` too, but its `*` belongs to the opener, hence the length check.
        if (text.length >= 4 && text.endsWith("*/")) return
        val start = element.textRange.startOffset
        holder.newAnnotation(HighlightSeverity.ERROR, "Unclosed comment")
            .range(TextRange(start, start + 2))
            .create()
    }
}
