package io.github.lemyst.enfusion.templates

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnfusionFile
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Where an Enforce Script live template may expand: anywhere in a script, at file scope, in a
 * class body, or among statements. Never inside a comment or a string.
 *
 * The scope is read from the tokens before the caret, not from the PSI around it. The abbreviation
 * just typed is a half-written declaration (`onrpc` alone in a class body), and the grammar has no
 * error recovery, so the tree around it may already have closed the class it sits in.
 */
abstract class EnTemplateContext(presentableName: String) : TemplateContextType(presentableName) {

    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file as? EnfusionFile ?: return false
        val offset = templateActionContext.startOffset
        if (isInCommentOrString(file, offset)) return false
        return accepts(EnTemplateScopes.scopeAt(file, offset))
    }

    protected abstract fun accepts(scope: EnTemplateScopes.Scope): Boolean

    private fun isInCommentOrString(file: PsiFile, offset: Int): Boolean {
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return false
        if (leaf is PsiComment) return leaf.textRange.startOffset < offset
        return leaf.node.elementType === EnfusionTypes.STRING && leaf.textRange.startOffset < offset
    }
}

class EnScriptContext : EnTemplateContext("Enforce Script") {
    override fun accepts(scope: EnTemplateScopes.Scope): Boolean = true
}

class EnFileScopeContext : EnTemplateContext("File scope") {
    override fun accepts(scope: EnTemplateScopes.Scope): Boolean = scope == EnTemplateScopes.Scope.FILE
}

class EnClassBodyContext : EnTemplateContext("Class body") {
    override fun accepts(scope: EnTemplateScopes.Scope): Boolean = scope == EnTemplateScopes.Scope.CLASS
}

class EnStatementContext : EnTemplateContext("Statement") {
    override fun accepts(scope: EnTemplateScopes.Scope): Boolean = scope == EnTemplateScopes.Scope.STATEMENT
}

object EnTemplateScopes {

    enum class Scope { FILE, CLASS, STATEMENT, OTHER }

    private enum class Brace { CLASS, CODE, OTHER }

    /**
     * Each `{` before [offset] is a class body when `class` came since the last `;`, `{` or `}`; a
     * code block after a `)` (a method, `if`, `for`, `switch`) or inside another code block; and
     * anything else (an enum, an array initializer) otherwise.
     */
    fun scopeAt(file: PsiFile, offset: Int): Scope {
        val stack = ArrayList<Brace>()
        val sinceBoundary = ArrayList<IElementType>()
        var leaf: PsiElement? = PsiTreeUtil.firstChild(file)
        while (leaf != null && leaf.textRange.startOffset < offset) {
            if (leaf !is PsiWhiteSpace && leaf !is PsiComment && leaf.textLength > 0) {
                when (val type = leaf.node.elementType) {
                    EnfusionTypes.LBRACE -> {
                        stack += when {
                            Brace.CODE in stack -> Brace.CODE
                            sinceBoundary.lastOrNull() === EnfusionTypes.RPAREN -> Brace.CODE
                            EnfusionTypes.CLASS_KW in sinceBoundary -> Brace.CLASS
                            else -> Brace.OTHER
                        }
                        sinceBoundary.clear()
                    }
                    EnfusionTypes.RBRACE -> {
                        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                        sinceBoundary.clear()
                    }
                    EnfusionTypes.SEMICOLON -> sinceBoundary.clear()
                    EnfusionTypes.PP_DIRECTIVE, EnfusionTypes.INACTIVE_CODE -> Unit
                    else -> sinceBoundary += type
                }
            }
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return when {
            Brace.CODE in stack -> Scope.STATEMENT
            stack.isEmpty() -> Scope.FILE
            stack.last() == Brace.CLASS -> Scope.CLASS
            else -> Scope.OTHER
        }
    }
}
