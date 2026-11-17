package io.github.lemyst.enfusion.format

import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.common.AbstractBlock
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes

/**
 * `config.cpp` and `mod.cpp` formatting: indentation only, for the reason the script formatter
 * gives. Spacing and line breaks are left as written, so `scope=2;` and `scope = 2;` both survive.
 *
 * One level deeper: the contents of a class body, and the elements of an array initializer written
 * over several lines. A closing `}` and a `{` on a line of its own line up with the line that
 * opened them. That is how every one of the 207 de-rapified vanilla configs on `P:\DZ` is written,
 * with tabs, an array's `{` on the line after its name, and no spaces around `=`, so they come out
 * unchanged.
 *
 * Preprocessor lines, rare in configs, stay at the first column.
 */
class EnfusionConfigBlock(node: ASTNode) : AbstractBlock(node, null, null) {

    private companion object {
        const val ANY_SPACES = 255
        const val ANY_BLANK_LINES = 99

        /** Whatever is there: any spaces, line breaks and blank lines kept as written. */
        val KEEP_AS_WRITTEN: Spacing = Spacing.createSpacing(0, ANY_SPACES, 0, true, ANY_BLANK_LINES)

        /** The rules whose brace-delimited contents are one level deeper. */
        val INDENTING = setOf(EnfusionConfigTypes.CLASS_DECL, EnfusionConfigTypes.ARRAY_VALUE)
    }

    override fun buildChildren(): List<Block> {
        val blocks = ArrayList<Block>()
        var child = myNode.firstChildNode
        while (child != null) {
            if (child.elementType !== TokenType.WHITE_SPACE && child.textRange.length > 0) blocks += EnfusionConfigBlock(child)
            child = child.treeNext
        }
        return blocks
    }

    /**
     * Relative to the parent block, as in the script formatter: [Indent.getNoneIndent] lines a
     * child up with its parent. A property's value, `{` included, stays at the property's level
     * when it starts a line, and only what is between the braces goes deeper.
     */
    override fun getIndent(): Indent {
        val type = myNode.elementType
        if (type === EnfusionConfigTypes.PP_DIRECTIVE) return Indent.getAbsoluteNoneIndent()
        val parent = myNode.treeParent ?: return Indent.getNoneIndent()
        if (parent.elementType !in INDENTING) return Indent.getNoneIndent()
        if (type === EnfusionConfigTypes.LBRACE || type === EnfusionConfigTypes.RBRACE) return Indent.getNoneIndent()
        val open = parent.findChildByType(EnfusionConfigTypes.LBRACE) ?: return Indent.getNoneIndent()
        val close = parent.findChildByType(EnfusionConfigTypes.RBRACE)
        val start = myNode.startOffset
        if (start < open.startOffset) return Indent.getNoneIndent()
        if (close != null && start >= close.startOffset) return Indent.getNoneIndent()
        return Indent.getNormalIndent()
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing = KEEP_AS_WRITTEN

    override fun isLeaf(): Boolean = myNode.firstChildNode == null

    /** Enter inside a class body or an array initializer goes one level deeper. */
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val indent = if (myNode.elementType in INDENTING && opensBefore(newChildIndex)) Indent.getNormalIndent() else Indent.getNoneIndent()
        return ChildAttributes(indent, null)
    }

    /** Whether the new child would come after the block's `{`, not between `class X` and it. */
    private fun opensBefore(newChildIndex: Int): Boolean =
        subBlocks.take(newChildIndex).any { (it as? EnfusionConfigBlock)?.node?.elementType === EnfusionConfigTypes.LBRACE }
}

class EnfusionConfigFormattingModelBuilder : FormattingModelBuilder {

    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        return FormattingModelProvider.createFormattingModelForPsiFile(
            file,
            EnfusionConfigBlock(file.node),
            formattingContext.codeStyleSettings,
        )
    }
}
