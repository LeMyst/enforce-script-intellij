package io.github.lemyst.enfusion.doc

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnfusionTokenSets
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Finds the comment that documents a declaration: a `/** */` or `/*! */` block, or consecutive
 * `//!` lines, directly above it; or else a `//!<` (or `//!`) after it on the same line.
 *
 * "Directly above" allows no blank line in between, which is what keeps a file's licence header
 * or a section banner from becoming the documentation of the first class under it. A comment
 * that shares its line with code before it is that code's trailing comment, never the next
 * declaration's.
 */
object EnDocCommentFinder {

    /** The raw text of each comment documenting [declaration], in file order. */
    fun commentsOf(declaration: PsiElement): List<String> {
        val above = above(anchorOf(declaration))
        if (above.isNotEmpty()) return above
        return listOfNotNull(trailing(declaration))
    }

    /**
     * The element whose previous siblings hold the comment. A field's name is a declarator inside
     * its declaration (`int a, b;`), and the comment sits before the declaration.
     */
    private fun anchorOf(declaration: PsiElement): PsiElement {
        if (declaration.node?.elementType === EnfusionTypes.VAR_DECLARATOR) {
            val parent = declaration.parent
            val type = parent?.node?.elementType
            if (type === EnfusionTypes.VAR_DECL || type === EnfusionTypes.LOCAL_VAR_DECL_STMT) return parent
        }
        return declaration
    }

    private fun above(anchor: PsiElement): List<String> {
        val found = ArrayList<PsiComment>()
        var previous = anchor.prevSibling
        while (previous != null) {
            if (previous is PsiWhiteSpace) {
                if (previous.text.count { it == '\n' } > 1) break
                previous = previous.prevSibling
                continue
            }
            if (previous !is PsiComment || !startsOwnLine(previous)) break
            val type = previous.node.elementType
            val text = previous.text
            if (type === EnfusionTypes.LINE_DOC_COMMENT && !isTrailingForm(text)) {
                found += previous
                previous = previous.prevSibling
                continue
            }
            // A block stands alone: `//!` lines above it belong to something else, or to nothing.
            if (type === EnfusionTypes.BLOCK_DOC_COMMENT && !isTrailingForm(text) && found.isEmpty()) {
                found += previous
            }
            break
        }
        return found.asReversed().map { it.text }
    }

    /**
     * A doc comment after the declaration, on its last line: `int m_Count; //!< text`,
     * `NONE, //!< text`, and `void EOnInit(IEntity other, int extra) //!EntityEvent.INIT`, where it
     * sits between the parameter list and a body on the next line.
     */
    private fun trailing(declaration: PsiElement): String? {
        val type = declaration.node?.elementType
        val starts = buildList {
            if (type === EnfusionTypes.METHOD_DECL) {
                declaration.node.findChildByType(EnfusionTypes.PARAM_LIST)?.psi?.let { add(it) }
            }
            if (type !== EnfusionTypes.CLASS_DECL && type !== EnfusionTypes.ENUM_DECL) add(declaration)
        }
        // Vanilla documents most of its constants with a plain `//` after them on the same line
        // (`const int ECE_TRACE = 4; // trace under entity when being placed`), so for a field or
        // an enum constant a plain comment counts too. Never for a method: there, a trailing plain
        // comment is as often a note to self as anything else.
        val plainToo = type === EnfusionTypes.VAR_DECLARATOR || type === EnfusionTypes.ENUM_CONSTANT
        for (start in starts) {
            var leaf = PsiTreeUtil.nextLeaf(start)
            while (leaf != null) {
                val leafType = leaf.node.elementType
                when {
                    leaf is PsiWhiteSpace -> if (leaf.textContains('\n')) break
                    leafType === EnfusionTypes.COMMA || leafType === EnfusionTypes.SEMICOLON -> Unit
                    leaf is PsiComment && EnfusionTokenSets.DOC_COMMENTS.contains(leafType) -> return leaf.text
                    leaf is PsiComment && plainToo && leafType === EnfusionTypes.LINE_COMMENT -> return leaf.text
                    else -> break
                }
                leaf = PsiTreeUtil.nextLeaf(leaf)
            }
        }
        return null
    }

    // `//!<`, and the same `<` after a block comment's opener: Doxygen's marker for a comment that
    // documents what came before it. (Not a KDoc: the block opener would nest a Kotlin comment.)
    private fun isTrailingForm(text: String): Boolean =
        text.startsWith("//!<") || text.startsWith("///<") || text.startsWith("/**<") || text.startsWith("/*!<")

    /** Only whitespace between the start of the line and [comment]. */
    private fun startsOwnLine(comment: PsiComment): Boolean {
        val text = comment.containingFile?.viewProvider?.contents ?: return true
        var offset = comment.textRange.startOffset - 1
        while (offset >= 0) {
            val c = text[offset]
            if (c == '\n') return true
            if (!c.isWhitespace()) return false
            offset--
        }
        return true
    }
}
