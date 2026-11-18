package io.github.lemyst.enfusion.resolve

import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * The value of an integer constant: an enum constant, or a `const int` with an initializer.
 *
 * Flags are where a value helps most. `ECE_PLACE_ON_SURFACE` is written `1060` and
 * `ECE_SETUP | ECE_UPDATEPATHGRAPH | ECE_CREATEPHYSICS` reads better than either, and an enum
 * constant without `=` has a value written nowhere at all: it is the previous constant's plus one.
 *
 * Folds integer literals, the arithmetic and bitwise operators, parentheses, and references to
 * other constants. Anything else, a call or a variable, gives null rather than a guess.
 */
object EnIntConstants {

    private const val MAX_DEPTH = 32

    /** The value of an `enumConstant` or of a `const` declarator, or null. */
    fun valueOf(declaration: PsiElement): Long? =
        RecursionManager.doPreventingRecursion(declaration, /* memoize = */ false) { compute(declaration, 0) }

    private fun compute(declaration: PsiElement, depth: Int): Long? {
        if (depth > MAX_DEPTH) return null
        return when (declaration.node?.elementType) {
            EnfusionTypes.ENUM_CONSTANT -> enumValue(declaration, depth)
            EnfusionTypes.VAR_DECLARATOR -> if (isConst(declaration)) evaluate(initializerOf(declaration), depth + 1) else null
            else -> null
        }
    }

    private fun enumValue(constant: PsiElement, depth: Int): Long? {
        val enum = constant.parent ?: return null
        // While an enum's values are being computed, a constant of the same enum named in an
        // initializer (`B = A | 2`) reads the values computed so far.
        inProgress.get()[enum]?.let { return it[constant] }
        return CachedValuesManager.getCachedValue(enum) {
            CachedValueProvider.Result.create(valuesOf(enum, depth), PsiModificationTracker.MODIFICATION_COUNT)
        }[constant]
    }

    /** Enums whose values this thread is computing, with the values so far. */
    private val inProgress = ThreadLocal.withInitial { HashMap<PsiElement, Map<PsiElement, Long>>() }

    /**
     * Every constant's value, in one pass: an explicit value, or one more than the constant before
     * it. The first constant of an `enum B : A` continues after `A`'s last one; of any other enum,
     * it is 0. One pass, cached per enum, because vanilla's longest enums run to hundreds of
     * implicit constants, and each depends on all of those before it.
     */
    private fun valuesOf(enum: PsiElement, depth: Int): Map<PsiElement, Long> {
        val values = LinkedHashMap<PsiElement, Long>()
        val active = inProgress.get()
        active[enum] = values
        try {
            var next: Long? = firstImplicitValue(enum, depth)
            for (constant in enum.children) {
                if (constant.node?.elementType !== EnfusionTypes.ENUM_CONSTANT) continue
                val initializer = initializerOf(constant)
                val value = if (initializer != null) evaluate(initializer, depth + 1) else next
                if (value != null) values[constant] = value
                next = value?.plus(1)
            }
        } finally {
            active.remove(enum)
        }
        return values
    }

    private fun firstImplicitValue(enum: PsiElement, depth: Int): Long? {
        val base = enum.node.findChildByType(EnfusionTypes.TYPE_REF)?.text?.trim() ?: return 0
        val baseLast = EnTypeInference.typesNamed(base, enum.project)
            .firstOrNull { it.node?.elementType === EnfusionTypes.ENUM_DECL && it !== enum }
            ?.children?.lastOrNull { it.node?.elementType === EnfusionTypes.ENUM_CONSTANT }
            ?: return 0
        return RecursionManager.doPreventingRecursion(baseLast, false) { compute(baseLast, depth + 1) }?.plus(1)
    }

    /** The folded value of an integer expression, or null. */
    fun evaluate(expr: PsiElement?, depth: Int = 0): Long? {
        if (expr == null || depth > MAX_DEPTH) return null
        val operands = significantChildren(expr)
        return when (expr.node?.elementType) {
            EnfusionTypes.LITERAL_EXPR -> parseInt(expr.text)
            EnfusionTypes.PAREN_EXPR, EnfusionTypes.OPERAND -> evaluate(operands.firstOrNull(), depth + 1)
            EnfusionTypes.UNARY_EXPR -> {
                val value = evaluate(operands.lastOrNull(), depth + 1) ?: return null
                when (expr.firstChild?.node?.elementType) {
                    EnfusionTypes.MINUS -> -value
                    EnfusionTypes.PLUS -> value
                    EnfusionTypes.TILDE -> value.inv()
                    else -> null
                }
            }
            EnfusionTypes.REF_EXPR, EnfusionTypes.MEMBER_EXPR -> {
                val reference = expr.reference as? PsiPolyVariantReference ?: return null
                reference.multiResolve(false).firstNotNullOfOrNull { result ->
                    result.element?.let { target ->
                        RecursionManager.doPreventingRecursion(target, false) { compute(target, depth + 1) }
                    }
                }
            }
            EnfusionTypes.BIT_OR_EXPR, EnfusionTypes.BIT_AND_EXPR, EnfusionTypes.BIT_XOR_EXPR,
            EnfusionTypes.ADD_EXPR, EnfusionTypes.MUL_EXPR, EnfusionTypes.SHIFT_EXPR,
            -> binary(expr, operands, depth)
            else -> null
        }
    }

    private fun binary(expr: PsiElement, operands: List<PsiElement>, depth: Int): Long? {
        if (operands.size != 2) return null
        val left = evaluate(operands[0], depth + 1) ?: return null
        val right = evaluate(operands[1], depth + 1) ?: return null
        val operator = expr.node.getChildren(null).firstOrNull {
            it.psi !is PsiWhiteSpace && it.psi !is PsiComment && it.psi !in operands
        }?.elementType
        return when (operator) {
            EnfusionTypes.PIPE -> left or right
            EnfusionTypes.AMP -> left and right
            EnfusionTypes.CARET -> left xor right
            EnfusionTypes.PLUS -> left + right
            EnfusionTypes.MINUS -> left - right
            EnfusionTypes.STAR -> left * right
            EnfusionTypes.SLASH -> if (right == 0L) null else left / right
            EnfusionTypes.PERCENT -> if (right == 0L) null else left % right
            // `<<` is a token; `>>` is two adjacent `>`, which only a shift expression holds.
            EnfusionTypes.LTLT -> if (right in 0..62) left shl right.toInt() else null
            EnfusionTypes.GT -> if (right in 0..62) left shr right.toInt() else null
            else -> null
        }
    }

    /** `12`, `0x1F`: the language's integer literals. A float gives null. */
    fun parseInt(text: String): Long? {
        val literal = text.trim()
        return if (literal.startsWith("0x") || literal.startsWith("0X")) {
            literal.substring(2).toLongOrNull(16)
        } else {
            literal.toLongOrNull()
        }
    }

    /** `const` sits on the enclosing declaration, among its modifiers. */
    private fun isConst(declarator: PsiElement): Boolean {
        val declaration = declarator.parent ?: return false
        return declaration.node.findChildByType(EnfusionTypes.CONST) != null
    }

    private fun initializerOf(declaration: PsiElement): PsiElement? {
        if (declaration.node.findChildByType(EnfusionTypes.EQ) == null) return null
        return significantChildren(declaration).lastOrNull()
    }

    private fun significantChildren(element: PsiElement): List<PsiElement> =
        element.children.filter { it !is PsiWhiteSpace && it !is PsiComment }
}
