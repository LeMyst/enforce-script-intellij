package io.github.lemyst.enfusion.cfg

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes

/**
 * Collapses class bodies and multi-line arrays.
 *
 * Both matter here. A vanilla `config.cpp` is one long list of sibling classes, and Expansion's are
 * deeply nested, so folding the bodies is what makes either navigable. Arrays get the same treatment
 * because `requiredAddons[]` and `files[]` are routinely a screenful on their own.
 *
 * A block folds only when it spans more than one line, the same rule as the other two languages.
 */
class EnfusionConfigFoldingBuilder : FoldingBuilderEx() {

    private companion object {
        val FOLDABLE = setOf(EnfusionConfigTypes.CLASS_DECL, EnfusionConfigTypes.ARRAY_VALUE)
    }

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val descriptors = ArrayList<FoldingDescriptor>()
        collect(root.node, document, descriptors)
        return descriptors.toTypedArray()
    }

    private fun collect(node: ASTNode, document: Document, out: MutableList<FoldingDescriptor>) {
        if (node.elementType in FOLDABLE) {
            braceRange(node)?.let { range ->
                if (document.getLineNumber(range.startOffset) != document.getLineNumber(range.endOffset)) {
                    out += FoldingDescriptor(node, range)
                }
            }
        }
        var child = node.firstChildNode
        while (child != null) {
            collect(child, document, out)
            child = child.treeNext
        }
    }

    private fun braceRange(node: ASTNode): TextRange? {
        val lbrace = node.findChildByType(EnfusionConfigTypes.LBRACE) ?: return null
        val rbrace = node.findChildByType(EnfusionConfigTypes.RBRACE) ?: return null
        return TextRange(lbrace.startOffset, rbrace.textRange.endOffset)
    }

    override fun getPlaceholderText(node: ASTNode): String = "{...}"

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
