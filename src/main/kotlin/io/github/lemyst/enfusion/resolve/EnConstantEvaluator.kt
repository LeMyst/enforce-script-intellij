package io.github.lemyst.enfusion.resolve

import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.refs.EnStringLiterals

/**
 * Folds a string-valued expression to its literal text.
 *
 * Required rather than nice to have: asset paths in this codebase are usually assembled rather than
 * written whole. `Party/` and `Extra/KillFeed/` both write
 * `BR_PARTY_PREFIX + "GUI/layouts/party_hud.layout"`, so a provider that only understood a bare
 * literal would miss most of the layouts it is meant to find.
 *
 * Only `+` over strings is folded, and only through `const` declarations with a literal initializer.
 * Anything else — a variable, a call, a field written at runtime — yields null, which is what makes
 * `PREFIX + "GUI/layouts/" + layout_name` produce no reference instead of a wrong one.
 */
object EnConstantEvaluator {

    private const val MAX_DEPTH = 8

    /** The folded value of [expr], or null when any part of it is not a compile-time constant. */
    fun evaluate(expr: PsiElement?): String? {
        if (expr == null) return null
        return RecursionManager.doPreventingRecursion(expr, /* memoize = */ false) { fold(expr, 0) }
    }

    /**
     * The whole concatenation [literal] belongs to.
     *
     * A provider triggers on one literal but has to resolve the entire path, so it climbs to the
     * outermost `+` before folding. Climbing stops at anything that is not an addition, which keeps
     * `Foo(a + b)` from being read as part of a larger expression.
     */
    fun concatenationAround(literal: PsiElement): PsiElement {
        var current = literal
        while (true) {
            val parent = current.parent ?: return current
            if (parent.node?.elementType !== EnfusionTypes.ADD_EXPR) return current
            current = parent
        }
    }

    private fun fold(expr: PsiElement?, depth: Int): String? {
        if (expr == null || depth > MAX_DEPTH) return null
        return when (expr.node?.elementType) {
            EnfusionTypes.LITERAL_EXPR -> EnStringLiterals.content(expr)

            EnfusionTypes.PAREN_EXPR -> fold(significantChildren(expr).firstOrNull(), depth + 1)

            // `-` never applies to strings, so a subtraction is not a constant string.
            EnfusionTypes.ADD_EXPR -> {
                if (expr.node.findChildByType(EnfusionTypes.PLUS) == null) null
                else {
                    val operands = significantChildren(expr)
                    if (operands.size != 2) null
                    else {
                        val left = fold(operands[0], depth + 1)
                        val right = fold(operands[1], depth + 1)
                        if (left == null || right == null) null else left + right
                    }
                }
            }

            EnfusionTypes.REF_EXPR, EnfusionTypes.MEMBER_EXPR -> foldConstant(expr, depth)

            else -> null
        }
    }

    /** The initializer of a `const string` the identifier resolves to. */
    private fun foldConstant(expr: PsiElement, depth: Int): String? {
        val reference = expr.reference as? PsiPolyVariantReference ?: return null
        for (result in reference.multiResolve(false)) {
            val declarator = result.element ?: continue
            if (declarator.node?.elementType !== EnfusionTypes.VAR_DECLARATOR) continue
            if (!isConst(declarator)) continue
            val initializer = initializerOf(declarator) ?: continue
            fold(initializer, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * `const` sits on the enclosing `varDecl` among the modifiers, not on the declarator — one
     * declaration can introduce several names and they share every modifier.
     */
    private fun isConst(declarator: PsiElement): Boolean {
        val declaration = declarator.parent ?: return false
        if (declaration.node?.elementType !== EnfusionTypes.VAR_DECL) return false
        return declaration.node.getChildren(null).any { child ->
            child.findChildByType(EnfusionTypes.CONST) != null || child.elementType === EnfusionTypes.CONST
        }
    }

    /** The expression after `=`, which is the last significant child of the declarator. */
    private fun initializerOf(declarator: PsiElement): PsiElement? {
        if (declarator.node.findChildByType(EnfusionTypes.EQ) == null) return null
        return significantChildren(declarator).lastOrNull()
    }

    private fun significantChildren(element: PsiElement): List<PsiElement> =
        element.children.filter { it !is com.intellij.psi.PsiWhiteSpace && it !is com.intellij.psi.PsiComment }
}
