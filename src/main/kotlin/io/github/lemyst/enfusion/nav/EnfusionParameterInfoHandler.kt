package io.github.lemyst.enfusion.nav

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnCallTargets

/**
 * Parameter info (Ctrl+P): the signatures a call may be meant for, with the parameter the caret is
 * in highlighted.
 *
 * Most of the engine API is `proto native` with no documentation beyond the signature, so this is
 * the difference between seeing `CreateObject(string type, vector pos, bool create_local = false,
 * ...)` while typing the call and Ctrl+Clicking into vanilla to read it. Every overload is listed
 * ([EnCallTargets]), and one that cannot take the arguments typed so far is greyed out.
 */
class EnfusionParameterInfoHandler : ParameterInfoHandler<PsiElement, EnMethodDeclMixin> {

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        if (DumbService.isDumb(context.project)) return null
        val argList = argListAt(context.file, context.offset) ?: return null
        val overloads = EnCallTargets.overloadsOf(argList)
        if (overloads.isEmpty()) return null
        context.itemsToShow = overloads.toTypedArray()
        return argList
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
        argListAt(context.file, context.offset)

    override fun updateParameterInfo(argList: PsiElement, context: UpdateParameterInfoContext) {
        // The caret has moved into a different call: this hint is for the old one. Compared by where
        // the call's `(` is, not by identity: every keystroke reparses the call, so the element the
        // hint was opened on is replaced by an equal one, and identity would close it mid-typing.
        val owner = context.parameterOwner
        if (owner != null && owner.isValid && owner.textRange.startOffset != argList.textRange.startOffset) {
            context.removeHint()
            return
        }
        context.parameterOwner = argList
        context.setCurrentParameter(commasBefore(argList, context.offset))
    }

    override fun updateUI(method: EnMethodDeclMixin, context: ParameterInfoUIContext) {
        val parameters = parametersOf(method)
        val argumentCount = context.parameterOwner?.let(::argumentCount) ?: 0
        if (parameters.isEmpty()) {
            context.setupUIComponentPresentation(
                NO_PARAMETERS, -1, -1, argumentCount > 0, false, false, context.defaultParameterColor,
            )
            return
        }

        val current = context.currentParameterIndex
        val text = StringBuilder()
        var highlightStart = -1
        var highlightEnd = -1
        parameters.forEachIndexed { index, parameter ->
            if (index > 0) text.append(", ")
            if (index == current) highlightStart = text.length
            text.append(parameter.text.replace(WHITESPACE, " ").trim())
            if (index == current) highlightEnd = text.length
        }
        // More arguments than this overload takes: shown, but greyed out.
        val disabled = current >= parameters.size || argumentCount > parameters.size
        context.setupUIComponentPresentation(
            text.toString(), highlightStart, highlightEnd, disabled, false, false, context.defaultParameterColor,
        )
    }

    private fun parametersOf(method: EnMethodDeclMixin): List<PsiElement> {
        val list = method.node.findChildByType(EnfusionTypes.PARAM_LIST)?.psi ?: return emptyList()
        return list.children.filter { it.node?.elementType === EnfusionTypes.PARAM }
    }

    /** The arguments written so far, counted from tokens for the same reason as [argListAt]. */
    private fun argumentCount(argList: PsiElement): Int {
        val open = argList.firstChild ?: return 0
        var depth = 0
        var commas = 0
        var any = false
        var leaf = PsiTreeUtil.nextVisibleLeaf(open)
        while (leaf != null) {
            val type = leaf.node?.elementType
            if (type in OPENERS) depth++
            if (type in CLOSERS) {
                if (depth == 0) break
                depth--
            }
            if (type in STATEMENT_BOUNDARIES && depth == 0) break
            if (depth == 0 && isArgumentComma(leaf, argList)) commas++ else any = true
            leaf = PsiTreeUtil.nextVisibleLeaf(leaf)
        }
        return if (any || commas > 0) commas + 1 else 0
    }

    /** Which argument [offset] is in: the top-level commas between the call's `(` and the caret. */
    private fun commasBefore(argList: PsiElement, offset: Int): Int {
        val open = argList.firstChild ?: return 0
        var depth = 0
        var commas = 0
        var leaf = PsiTreeUtil.nextVisibleLeaf(open)
        while (leaf != null && leaf.textRange.endOffset <= offset) {
            val type = leaf.node?.elementType
            if (type in OPENERS) depth++
            if (type in CLOSERS) depth--
            if (depth == 0 && isArgumentComma(leaf, argList)) commas++
            leaf = PsiTreeUtil.nextVisibleLeaf(leaf)
        }
        return commas
    }

    /** A comma between arguments, not one inside `new map<string, int>` written as an argument. */
    private fun isArgumentComma(leaf: PsiElement, argList: PsiElement): Boolean {
        if (leaf.node?.elementType !== EnfusionTypes.COMMA) return false
        val typeArguments = PsiTreeUtil.findFirstParent(leaf, true) {
            it == argList || it.node?.elementType === EnfusionTypes.TYPE_ARG_LIST
        }
        return typeArguments == null || typeArguments == argList
    }

    /**
     * The argument list of the innermost call whose parentheses enclose [offset], found from tokens:
     * the nearest `(` before the caret that nothing closes, and that opens a call's arguments.
     *
     * Not from the PSI around the caret, because a call being typed is often unparsable for a
     * keystroke or two: `Send("a", ;` ends the argument list at the stray `;`, and the caret is then
     * outside it as far as the parser is concerned, which would close the hint. The `(` itself still
     * belongs to the call, so that is what is looked for. A `(` that opens something else (an `if`
     * condition, a parenthesised argument) is stepped over, so `Send((a + <caret>` shows `Send`.
     * A brace ends the search: a call never spans a block.
     */
    private fun argListAt(file: PsiFile, offset: Int): PsiElement? {
        var leaf: PsiElement? = file.findElementAt(offset - 1)
        if (leaf is PsiWhiteSpace || leaf is PsiComment) leaf = PsiTreeUtil.prevVisibleLeaf(leaf)
        var depth = 0
        while (leaf != null) {
            val type = leaf.node?.elementType
            when {
                type in STATEMENT_BOUNDARIES -> return null
                type in CLOSERS -> depth++
                type in OPENERS && depth > 0 -> depth--
                type === EnfusionTypes.LPAREN -> {
                    val argList = leaf.parent
                    if (argList?.node?.elementType === EnfusionTypes.ARG_LIST && isCallArguments(argList)) {
                        return argList
                    }
                }
            }
            leaf = PsiTreeUtil.prevVisibleLeaf(leaf)
        }
        return null
    }

    /** An attribute's `[Obsolete("...")]` has an argument list too, but no method behind it. */
    private fun isCallArguments(argList: PsiElement): Boolean {
        val owner = argList.parent?.node?.elementType
        return owner === EnfusionTypes.CALL_EXPR || owner === EnfusionTypes.NEW_EXPR
    }

    private companion object {
        val OPENERS = TokenSet.create(EnfusionTypes.LPAREN, EnfusionTypes.LBRACKET)
        val CLOSERS = TokenSet.create(EnfusionTypes.RPAREN, EnfusionTypes.RBRACKET)
        val STATEMENT_BOUNDARIES = TokenSet.create(EnfusionTypes.LBRACE, EnfusionTypes.RBRACE)
        val WHITESPACE = Regex("\\s+")
        const val NO_PARAMETERS = "<no parameters>"
    }
}
