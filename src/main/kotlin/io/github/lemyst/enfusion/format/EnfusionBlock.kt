package io.github.lemyst.enfusion.format

import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.common.AbstractBlock
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Indentation only -- deliberately no spacing or wrapping rules at all.
 *
 * A formatter that rewrites spacing or line breaks would touch every line of a 2,811-file corpus the
 * first time anyone pressed Ctrl+Alt+L, and vanilla's conventions are strict enough that any
 * disagreement is a huge diff rather than a tidy-up. Two of them, measured rather than assumed:
 * indentation is **tabs**, exclusively (3,776 tab-indented lines in `dayzgame.c`, zero space-indented),
 * and braces are strict Allman (552 lines with a lone `{`, zero with a trailing one). Emitting no
 * spacing constraints is what leaves both alone.
 *
 * Preprocessor directives are indented like ordinary code, which also came from counting: 143 of them
 * are indented in `dayzgame.c` against 29 at column 0, and those 29 are top-level wrappers that
 * normal indentation leaves at column 0 anyway. Forcing column 0 -- the obvious guess -- would have
 * been wrong for the large majority.
 */
class EnfusionBlock(
    node: ASTNode,
    private val settings: com.intellij.psi.codeStyle.CodeStyleSettings,
) : AbstractBlock(node, null, null) {

    private companion object {
        /**
         * Bounded, not `Int.MAX_VALUE`: the blank-line count is arithmetic inside
         * `WhiteSpace.setLineFeeds`, and passing the maximum overflows into an `AssertionError` from
         * deep in the formatting engine rather than anything that names the cause.
         */
        const val ANY_SPACES = 255
        const val ANY_BLANK_LINES = 99

        val KEEP_AS_WRITTEN: Spacing =
            Spacing.createSpacing(0, ANY_SPACES, 0, true, ANY_BLANK_LINES)

        /** The rules whose brace-delimited contents are one level deeper. */
        val INDENTING = setOf(
            EnfusionTypes.CLASS_BODY,
            EnfusionTypes.BLOCK_STMT,
            EnfusionTypes.ENUM_DECL,
            EnfusionTypes.SWITCH_STMT,
            EnfusionTypes.ARRAY_LITERAL_EXPR,
        )
    }

    override fun buildChildren(): List<Block> {
        val blocks = ArrayList<Block>()
        var child = myNode.firstChildNode
        while (child != null) {
            if (child.elementType !== TokenType.WHITE_SPACE && child.textRange.length > 0) {
                blocks += EnfusionBlock(child, settings)
            }
            child = child.treeNext
        }
        return blocks
    }

    /**
     * Indent is **relative to the parent block**, which is the whole model here.
     *
     * [Indent.getNoneIndent] therefore means "line up with my parent", not "go to column 0", and it
     * is the right default for everything that is not inside braces. Neither alternative works:
     * `null` means *unspecified*, which the engine resolves to a continuation indent and pushed every
     * top-level declaration one tab to the right.
     *
     * Position matters too, not just the parent's type: `enum`, `switch` and their names are children
     * of an indenting rule, and indenting by parent type alone would push the `switch` keyword one
     * level deeper every time the file was formatted.
     */
    override fun getIndent(): Indent {
        val type = myNode.elementType

        // A directive is the one thing that opts out of the whole scheme. `getAbsoluteNoneIndent` is
        // the only indent that means column 0 outright rather than "align with my parent", which is
        // exactly what pinning a compile-time boundary needs.
        if (type === EnfusionTypes.PP_DIRECTIVE && directivesAtFirstColumn()) {
            return Indent.getAbsoluteNoneIndent()
        }

        val parent = myNode.treeParent ?: return Indent.getNoneIndent()
        if (parent.elementType !in INDENTING) return Indent.getNoneIndent()

        if (type === EnfusionTypes.LBRACE || type === EnfusionTypes.RBRACE) return Indent.getNoneIndent()

        val open = parent.findChildByType(EnfusionTypes.LBRACE) ?: return Indent.getNoneIndent()
        val close = parent.findChildByType(EnfusionTypes.RBRACE)
        val start = myNode.startOffset
        if (start < open.startOffset) return Indent.getNoneIndent()
        if (close != null && start >= close.startOffset) return Indent.getNoneIndent()
        return Indent.getNormalIndent()
    }

    /**
     * Permissive by construction: any amount of whitespace is acceptable, line breaks and blank lines
     * are kept.
     *
     * Returning `null` here is *not* equivalent -- with no constraint at all the formatter
     * regenerates the whitespace itself, which collapsed alignment tabs inside a line into spaces
     * and dropped a blank first line. Stating a constraint the existing text already satisfies is
     * what leaves it untouched.
     */
    private fun directivesAtFirstColumn(): Boolean =
        settings.getCustomSettings(EnfusionCodeStyleSettings::class.java).PREPROCESSOR_AT_FIRST_COLUMN

    override fun getSpacing(child1: Block?, child2: Block): Spacing? = KEEP_AS_WRITTEN

    override fun isLeaf(): Boolean = myNode.firstChildNode == null

    /** Where the caret lands on Enter inside a block. */
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val indent = if (myNode.elementType in INDENTING) Indent.getNormalIndent() else Indent.getNoneIndent()
        return ChildAttributes(indent, null)
    }
}
