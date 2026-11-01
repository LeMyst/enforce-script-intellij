package io.github.lemyst.enfusion.refs

import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Answering "is this literal argument *n* of a call named X?".
 *
 * [com.intellij.patterns.PlatformPatterns] cannot express this — it matches on element type and
 * ancestry, not on sibling position combined with a callee name — so the reference providers that
 * need it self-filter through here instead of through a pattern.
 *
 * Two of the six providers deliberately do *not* use it. Stringtable keys and asset paths are
 * recognised by their own content, because they appear in unrelated call shapes and, in the server
 * case, in no call at all.
 */
object EnCallArg {

    /** A literal's position in the call it belongs to. */
    data class Site(val calleeName: String?, val index: Int, val arguments: List<PsiElement>)

    /**
     * The call site [literal] is an argument of, or null when it is not an argument.
     *
     * Climbs through concatenations and parentheses first, so
     * `AddRPC(NS, PREFIX + "Handler", this)` still reports index 1 rather than losing the literal
     * inside an expression the caller did not ask about.
     */
    fun siteOf(literal: PsiElement): Site? {
        var argument = literal
        while (true) {
            val parent = argument.parent ?: return null
            when (parent.node?.elementType) {
                EnfusionTypes.ADD_EXPR, EnfusionTypes.PAREN_EXPR -> argument = parent
                EnfusionTypes.ARG_LIST -> {
                    val call = parent.parent ?: return null
                    if (call.node?.elementType !== EnfusionTypes.CALL_EXPR) return null
                    // `children` yields composite nodes only, so the parentheses and commas are
                    // already gone and this is exactly the argument list.
                    val arguments = parent.children.toList()
                    val index = arguments.indexOf(argument)
                    if (index < 0) return null
                    return Site(calleeName(call), index, arguments)
                }
                else -> return null
            }
        }
    }

    /** True when [literal] is argument [index] of a call to one of [names]. */
    fun isArgumentOf(literal: PsiElement, names: Set<String>, index: Int): Boolean {
        val site = siteOf(literal) ?: return false
        return site.index == index && site.calleeName in names
    }

    /**
     * The name being called.
     *
     * `callExpr ::= expr argList`, so the callee is the first child: a `refExpr` for a free function
     * (`CreateWidgets(...)`) or a `memberExpr` for the usual qualified form
     * (`row.FindAnyWidget(...)`), whose trailing identifier is the method name.
     */
    private fun calleeName(call: PsiElement): String? {
        val callee = call.children.firstOrNull() ?: return null
        return when (callee.node?.elementType) {
            EnfusionTypes.REF_EXPR -> callee.node.findChildByType(EnfusionTypes.IDENT)?.text
            EnfusionTypes.MEMBER_EXPR -> callee.node.lastChildNode
                ?.takeIf { it.elementType === EnfusionTypes.IDENT }?.text
            else -> null
        }
    }
}
