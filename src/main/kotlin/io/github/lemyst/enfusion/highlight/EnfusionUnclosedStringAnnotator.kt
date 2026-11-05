package io.github.lemyst.enfusion.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Reports a string literal with no closing quote.
 *
 * The lexer lexes an unclosed string as a string running to the end of the line, so that the quote
 * handler can pair a `"` as it is typed. The parser therefore accepts it, and this is what still
 * flags it, on the string itself rather than as a parse error on whatever follows.
 */
class EnfusionUnclosedStringAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.node?.elementType !== EnfusionTypes.STRING) return
        if (isClosed(element.text)) return
        holder.newAnnotation(HighlightSeverity.ERROR, "Missing closing quote").create()
    }

    /** Ends in a `"` that is not itself escaped: an even number of backslashes stands before it. */
    private fun isClosed(text: String): Boolean {
        if (text.length < 2 || !text.endsWith('"')) return false
        var backslashes = 0
        var index = text.length - 2
        while (index >= 1 && text[index] == '\\') {
            backslashes++
            index--
        }
        return backslashes % 2 == 0
    }
}
