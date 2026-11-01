package io.github.lemyst.enfusion.objfmt

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectTypes

/**
 * Collapses every brace-delimited block to `{...}`: a widget declaration (`objectDecl`) and its
 * anonymous children block (`childBlock`) -- the only two rules in `EnfusionObject.bnf` that use
 * braces. This is what makes a deeply nested `.layout` navigable at all; widget trees routinely run
 * five or six levels deep.
 *
 * A block folds only when it spans more than one line, same rule as [io.github.lemyst.enfusion.lang.EnfusionFoldingBuilder].
 */
class EnfusionObjectFoldingBuilder : FoldingBuilderEx() {

    private companion object {
        val FOLDABLE = setOf(EnfusionObjectTypes.OBJECT_DECL, EnfusionObjectTypes.CHILD_BLOCK)
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
        val lbrace = node.findChildByType(EnfusionObjectTypes.LBRACE) ?: return null
        val rbrace = node.findChildByType(EnfusionObjectTypes.RBRACE) ?: return null
        return TextRange(lbrace.startOffset, rbrace.textRange.endOffset)
    }

    override fun getPlaceholderText(node: ASTNode): String = "{...}"

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
