package io.github.lemyst.enfusion.format

import com.intellij.formatting.ASTBlock
import com.intellij.formatting.Block
import com.intellij.formatting.Spacing
import com.intellij.lang.ASTNode

/**
 * The spacing for the whitespace between two blocks: read-only inside a line, [lineSpacing] across
 * lines.
 *
 * Any spacing constraint lets the formatting engine rewrite the whitespace, even one the text
 * already meets, and it writes a tab inside a line as spaces. Vanilla aligns names with tabs,
 * `protected Camera<tabs>m_Camera;`, and one Ctrl+Alt+L turned each tab into four spaces. That
 * misaligns the names, since a tab's width depends on the column it starts at. Read-only
 * whitespace is left exactly as written. Whitespace holding a line break keeps [lineSpacing], so
 * that indentation is still the formatter's.
 */
internal object InlineWhitespace {

    fun between(parent: ASTNode, child1: Block?, child2: Block, lineSpacing: Spacing): Spacing {
        if (child1 == null) return lineSpacing
        // Only the nodes between the two blocks are read, usually one whitespace leaf. Reading
        // `parent.chars` built the whole parent's text on every call, and a body is asked once per
        // pair of its children: reformatting a 525,000-line vanilla config took over 8 minutes.
        val first = (child1 as? ASTBlock)?.node
        val second = (child2 as? ASTBlock)?.node
        if (first != null && second != null && first.treeParent === second.treeParent) {
            var node = first.treeNext
            while (node != null && node !== second) {
                if (node.textContains('\n')) return lineSpacing
                node = node.treeNext
            }
            if (node === second) return Spacing.getReadOnlySpacing()
        }
        // Blocks that are not siblings: the gap read from the parent's text, as before.
        val text = parent.chars
        val base = parent.startOffset
        val from = (child1.textRange.endOffset - base).coerceIn(0, text.length)
        val to = (child2.textRange.startOffset - base).coerceIn(from, text.length)
        for (i in from until to) if (text[i] == '\n') return lineSpacing
        return Spacing.getReadOnlySpacing()
    }
}
