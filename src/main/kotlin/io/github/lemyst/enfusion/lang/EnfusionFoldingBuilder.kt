package io.github.lemyst.enfusion.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Folds brace-delimited blocks, `#ifdef` regions, and code the current define set excludes.
 *
 * Brace blocks are class bodies, statement blocks (method bodies and `if`/`for`/`while` bodies --
 * `blockStmt` covers all of them), enum bodies, switch bodies and array literals. That is every place
 * `LBRACE .. RBRACE` appears in `Enfusion.bnf`; nothing else in the grammar uses braces.
 *
 * Anything folded must span more than one line -- a one-line `{}` has nothing worth hiding, and
 * folding it would just be visual noise.
 */
class EnfusionFoldingBuilder : FoldingBuilderEx() {

    private companion object {
        val FOLDABLE = setOf(
            EnfusionTypes.CLASS_BODY,
            EnfusionTypes.BLOCK_STMT,
            EnfusionTypes.ENUM_DECL,
            EnfusionTypes.SWITCH_STMT,
            EnfusionTypes.ARRAY_LITERAL_EXPR,
        )
    }

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val descriptors = ArrayList<FoldingDescriptor>()
        val directives = ArrayList<ASTNode>()
        collect(root.node, document, descriptors, directives)
        foldConditionalRegions(directives, document, descriptors)
        return descriptors.toTypedArray()
    }

    /**
     * One depth-first pass, which visits leaves in document order -- that ordering is what lets the
     * directive list be paired with a simple stack afterwards.
     */
    private fun collect(
        node: ASTNode,
        document: Document,
        out: MutableList<FoldingDescriptor>,
        directives: MutableList<ASTNode>,
    ) {
        when {
            node.elementType in FOLDABLE ->
                braceRange(node)?.let { addIfMultiLine(node, it, document, out) }

            // Preprocessor tokens survive into the tree because they are comment-class (see
            // EnfusionTokenSets.COMMENTS), which is the only reason folding can see them at all.
            node.elementType === EnfusionTypes.PP_DIRECTIVE -> directives += node

            // A branch the define set excluded, already collapsed to a single token by
            // EnfusionPreprocessingLexer. Only appears where a region could not keep both branches.
            node.elementType === EnfusionTypes.INACTIVE_CODE ->
                addIfMultiLine(node, node.textRange, document, out)
        }
        var child = node.firstChildNode
        while (child != null) {
            collect(child, document, out, directives)
            child = child.treeNext
        }
    }

    /**
     * Pairs `#ifdef`/`#ifndef` with its `#endif` and folds the whole region, nesting included.
     *
     * The region is folded as one unit rather than per `#else` arm: two descriptors starting on the
     * same line collapse into a single gutter control, so a per-arm fold would silently shadow the
     * region fold on every `#ifdef` that has an `#else`.
     *
     * Unbalanced directives are ignored rather than guessed at -- an `#endif` with no opener, or an
     * `#ifdef` that never closes, simply contributes no fold.
     */
    private fun foldConditionalRegions(
        directives: List<ASTNode>,
        document: Document,
        out: MutableList<FoldingDescriptor>,
    ) {
        val open = ArrayDeque<ASTNode>()
        for (directive in directives) {
            val text = directive.text.trimStart()
            when {
                text.startsWith("#ifdef") || text.startsWith("#ifndef") -> open.addLast(directive)
                text.startsWith("#endif") -> {
                    val opener = open.removeLastOrNull() ?: continue
                    val endOffset = directive.textRange.endOffset
                    if (endOffset > document.textLength) continue
                    // At least one line of content between the directives. A directive always runs
                    // to end of line, so a region is never single-line and the brace rule -- "start
                    // and end on different lines" -- would accept even an empty `#ifdef`/`#endif`
                    // pair, adding a gutter control that hides nothing.
                    val startLine = document.getLineNumber(opener.startOffset)
                    val endLine = document.getLineNumber(endOffset)
                    if (endLine > startLine + 1) {
                        out += FoldingDescriptor(opener, TextRange(opener.startOffset, endOffset))
                    }
                }
            }
        }
    }

    private fun addIfMultiLine(
        anchor: ASTNode,
        range: TextRange,
        document: Document,
        out: MutableList<FoldingDescriptor>,
    ) {
        if (range.isEmpty || range.endOffset > document.textLength) return
        if (document.getLineNumber(range.startOffset) == document.getLineNumber(range.endOffset)) return
        out += FoldingDescriptor(anchor, range)
    }

    /**
     * The braces are always direct children of the foldable node itself -- a nested block's own
     * braces belong to *that* node, never to this one -- so a plain child lookup is unambiguous.
     */
    private fun braceRange(node: ASTNode): TextRange? {
        val lbrace = node.findChildByType(EnfusionTypes.LBRACE) ?: return null
        val rbrace = node.findChildByType(EnfusionTypes.RBRACE) ?: return null
        return TextRange(lbrace.startOffset, rbrace.textRange.endOffset)
    }

    override fun getPlaceholderText(node: ASTNode): String = when (node.elementType) {
        // Keeps the flag visible while collapsed: `#ifdef SERVER ... #endif` says which branch this
        // is, which is the whole reason to look at a preprocessor region in the first place.
        EnfusionTypes.PP_DIRECTIVE -> node.text.trim() + " ... #endif"
        EnfusionTypes.INACTIVE_CODE -> "/* inactive */"
        else -> "{...}"
    }

    /**
     * Inactive code starts collapsed; everything else starts expanded.
     *
     * Only a branch the define set positively excluded reaches this, and never one of the balanced
     * both-branches-kept regions that make up the overwhelming majority -- so this hides code that is
     * genuinely not compiled, not code the plugin merely failed to choose between.
     */
    override fun isCollapsedByDefault(node: ASTNode): Boolean =
        node.elementType === EnfusionTypes.INACTIVE_CODE
}
