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
 *
 * The rest of the indentation follows vanilla's majority too, counted over every file of
 * `P:\scripts`:
 * - the body of an `if` / `else` / `for` / `foreach` / `while` without braces is one level deeper
 *   (8,483 lines against 192);
 * - statements under `case X:` are one level deeper than the label (1,763 against 24), while a `{`
 *   right after a label stays at its level (649 against 12);
 * - a line continuing a statement is one level deeper (82 of 122 such lines; the others are
 *   scattered between two levels back and aligned columns), and a lone closing `)` goes back to the
 *   statement's level (49 against 5).
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

        /** The statements whose body may be a single statement without braces. */
        val BODY_OWNERS = setOf(
            EnfusionTypes.IF_STMT,
            EnfusionTypes.FOR_STMT,
            EnfusionTypes.FOREACH_STMT,
            EnfusionTypes.WHILE_STMT,
        )

        /**
         * The rules whose later lines continue the first: expressions, argument and parameter
         * lists, declarations and `return`. A child that starts a line in one of them is a
         * continuation line.
         */
        val CONTINUED = setOf(
            EnfusionTypes.ADD_EXPR, EnfusionTypes.AND_EXPR, EnfusionTypes.ASSIGN_EXPR, EnfusionTypes.BIT_AND_EXPR,
            EnfusionTypes.BIT_OR_EXPR, EnfusionTypes.BIT_XOR_EXPR, EnfusionTypes.CALL_EXPR, EnfusionTypes.CAST_EXPR,
            EnfusionTypes.EQ_EXPR, EnfusionTypes.EXPR, EnfusionTypes.GENERIC_QUALIFIER_EXPR, EnfusionTypes.INDEX_EXPR,
            EnfusionTypes.MEMBER_EXPR, EnfusionTypes.MUL_EXPR, EnfusionTypes.NEW_EXPR, EnfusionTypes.OPERAND,
            EnfusionTypes.OR_EXPR, EnfusionTypes.PAREN_EXPR, EnfusionTypes.REL_EXPR, EnfusionTypes.SHIFT_EXPR,
            EnfusionTypes.UNARY_EXPR, EnfusionTypes.ARG_LIST, EnfusionTypes.PARAM_LIST, EnfusionTypes.EXPR_LIST,
            EnfusionTypes.VAR_DECLARATOR, EnfusionTypes.VAR_DECL, EnfusionTypes.LOCAL_VAR_DECL_STMT,
            EnfusionTypes.RETURN_STMT,
        )

        /** Tokens that close what a continuation opened, and go back to its first line's level. */
        val CLOSING = setOf(EnfusionTypes.RPAREN, EnfusionTypes.RBRACKET, EnfusionTypes.SEMICOLON)
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
        when (parent.elementType) {
            in INDENTING -> {}
            EnfusionTypes.SWITCH_CASE -> return caseIndent()
            in BODY_OWNERS -> return bodyIndent(parent)
            in CONTINUED ->
                return if (type in CLOSING) Indent.getNoneIndent() else Indent.getContinuationWithoutFirstIndent()
            else -> return Indent.getNoneIndent()
        }

        if (type === EnfusionTypes.LBRACE || type === EnfusionTypes.RBRACE) return Indent.getNoneIndent()

        val open = parent.findChildByType(EnfusionTypes.LBRACE) ?: return Indent.getNoneIndent()
        val close = parent.findChildByType(EnfusionTypes.RBRACE)
        val start = myNode.startOffset
        if (start < open.startOffset) return Indent.getNoneIndent()
        if (close != null && start >= close.startOffset) return Indent.getNoneIndent()
        return Indent.getNormalIndent()
    }

    /** Under `case X:`, a statement is one level deeper, but a `{` block stays at the label's level. */
    private fun caseIndent(): Indent =
        if (myNode.elementType === EnfusionTypes.STATEMENT && !isBlock(myNode)) Indent.getNormalIndent() else Indent.getNoneIndent()

    /**
     * A body without braces is one level deeper than its `if`, `for`, `foreach` or `while`. A body
     * in braces is not (Allman: the `{` lines up with the statement), and neither is the `if` of an
     * `else if`, so that a chain of them stays flat.
     */
    private fun bodyIndent(parent: ASTNode): Indent {
        if (myNode.elementType !== EnfusionTypes.STATEMENT || isBlock(myNode)) return Indent.getNoneIndent()
        if (parent.elementType === EnfusionTypes.IF_STMT && isElseIf(myNode)) return Indent.getNoneIndent()
        return Indent.getNormalIndent()
    }

    private fun isBlock(statement: ASTNode): Boolean = statement.firstChildNode?.elementType === EnfusionTypes.BLOCK_STMT

    private fun isElseIf(statement: ASTNode): Boolean {
        if (statement.firstChildNode?.elementType !== EnfusionTypes.IF_STMT) return false
        var previous = statement.treePrev
        while (previous != null && (previous.elementType === TokenType.WHITE_SPACE || previous.textLength == 0)) previous = previous.treePrev
        return previous?.elementType === EnfusionTypes.ELSE
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

    /**
     * Where the caret lands on Enter: inside braces, under a `case` label and after an `if (…)` or
     * loop header still waiting for its body, one level deeper; inside an unfinished expression, at
     * the continuation indent.
     */
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val indent = when (myNode.elementType) {
            in INDENTING, EnfusionTypes.SWITCH_CASE -> Indent.getNormalIndent()
            in BODY_OWNERS -> if (hasBody()) Indent.getNoneIndent() else Indent.getNormalIndent()
            in CONTINUED -> Indent.getContinuationIndent()
            else -> Indent.getNoneIndent()
        }
        return ChildAttributes(indent, null)
    }

    /**
     * An `if (…)` or loop header with no body yet is incomplete, so that Enter after it asks this
     * block where to go rather than its parent. A `case` always is: the statements after it, however
     * many, belong to it.
     */
    override fun isIncomplete(): Boolean = when (myNode.elementType) {
        EnfusionTypes.SWITCH_CASE -> true
        in BODY_OWNERS -> !hasBody()
        // The wrapper around every statement: as finished as the statement it holds, which is what
        // Enter asks of the block before the caret.
        EnfusionTypes.STATEMENT -> subBlocks.lastOrNull()?.isIncomplete == true
        else -> super.isIncomplete()
    }

    /** Whether the statement has its body: a `STATEMENT` after the closing `)` of its header. */
    private fun hasBody(): Boolean {
        var child = myNode.lastChildNode
        while (child != null && (child.elementType === TokenType.WHITE_SPACE || child.textLength == 0)) child = child.treePrev
        return child?.elementType === EnfusionTypes.STATEMENT
    }
}
