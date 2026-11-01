package io.github.lemyst.enfusion.refs

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.cfg.EnfusionConfigFileType
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes
import io.github.lemyst.enfusion.lang.EnfusionFileType
import io.github.lemyst.enfusion.objfmt.EnfusionObjectFileType
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectTypes
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Reading the content of a quoted string, shared by the `.c` literals and the `.layout` values.
 *
 * Ranges are computed over the *raw* text between the quotes rather than over a decoded value, so
 * a `TextRange` handed to a reference always points at real characters in the file. That is exact
 * for every literal this plugin navigates from: widget names, stringtable keys, imageset
 * references, RPC handler names and asset paths contain no escape sequences — paths in this
 * language are written with forward slashes.
 */
object EnStringLiterals {

    /**
     * The range of the content inside the quotes, relative to [element].
     *
     * Returns null for anything that is not a complete double-quoted string, which is how the
     * numeric and boolean alternatives of `literalExpr` opt out.
     */
    fun contentRange(element: PsiElement): TextRange? {
        val text = element.text ?: return null
        if (text.length < 2) return null
        if (text.first() != '"' || text.last() != '"') return null
        return TextRange(1, text.length - 1)
    }

    /** The raw text between the quotes, or null when [element] is not a quoted string. */
    fun content(element: PsiElement): String? =
        contentRange(element)?.substring(element.text)

    /**
     * A sub-range of the content, given offsets *within the content*.
     *
     * Callers match against the string returned by [content], so their offsets are content-relative
     * and have to be shifted past the opening quote before a reference can use them.
     */
    /**
     * A replacement string literal carrying [text], quotes included.
     *
     * Both of these exist for the manipulators, and both parse a whole declaration rather than
     * building a leaf: a literal has no meaning at file scope in either grammar, so it has to come
     * out of a context that gives it one. The wrappers are the smallest each language accepts.
     */
    fun createLiteral(project: Project, text: String): PsiElement? {
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            "_edit.es", EnfusionFileType, "class _Edit\n{\n\tvoid _Edit2()\n\t{\n\t\t_v = $text;\n\t}\n}\n",
        )
        return PsiTreeUtil.collectElements(file) {
            it.node?.elementType === EnfusionTypes.LITERAL_EXPR
        }.firstOrNull()
    }

    fun createObjectValue(project: Project, text: String): PsiElement? {
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            "_edit.layout", EnfusionObjectFileType, "WidgetClass _Edit {\n key $text\n}\n",
        )
        return PsiTreeUtil.collectElements(file) {
            it.node?.elementType === EnfusionObjectTypes.VALUE
        }.firstOrNull()
    }

    fun createConfigValue(project: Project, text: String): PsiElement? {
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            "config.cpp", EnfusionConfigFileType, "class _Edit { key = $text; };\n",
        )
        return PsiTreeUtil.collectElements(file) {
            it.node?.elementType === EnfusionConfigTypes.VALUE
        }.firstOrNull()
    }

    fun subRange(element: PsiElement, startInContent: Int, endInContent: Int): TextRange? {
        val content = contentRange(element) ?: return null
        if (startInContent < 0 || endInContent > content.length || startInContent > endInContent) return null
        return TextRange(content.startOffset + startInContent, content.startOffset + endInContent)
    }
}
