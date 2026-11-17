package io.github.lemyst.enfusion.hints

import com.intellij.codeInsight.hints.HintInfo
import com.intellij.codeInsight.hints.InlayInfo
import com.intellij.codeInsight.hints.InlayParameterHintsProvider
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.lang.EnfusionLanguage
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnCallTargets

/**
 * Parameter names before the literal arguments of a call: in
 * `CallLater(Refresh, 1000, false)`, is `false` *repeat*?
 *
 * Literals only, as Java does: a number, `true` / `false`, `null` or a string, or a negated
 * number. A name or a constant usually says what it is (`ECE_PLACE_ON_SURFACE`). Shown only when
 * every overload the call may mean has the same parameter name at that position, so a hint is
 * never a guess; and not for a one-parameter setter, whose name already says it.
 *
 * The exclude list (*Settings → Editor → Inlay Hints → Parameter names*) starts with the calls
 * whose arguments are obvious: printing, maths, container access.
 */
class EnParameterNameHintsProvider : InlayParameterHintsProvider {

    override fun getParameterHints(element: PsiElement): List<InlayInfo> {
        val call = EnCalls.ofArgList(element) ?: return emptyList()
        val arguments = EnCalls.arguments(element)
        if (arguments.none(EnCalls::isLiteral)) return emptyList()
        val methods = EnCalls.methodsOf(call, arguments.size)
        if (methods.isEmpty()) return emptyList()
        val names = methods.map(EnCalls::parameterNames)
        if (names.first().size == 1 && methods.first().name?.startsWith("Set") == true) return emptyList()

        val hints = ArrayList<InlayInfo>()
        for ((index, argument) in arguments.withIndex()) {
            if (!EnCalls.isLiteral(argument)) continue
            val name = names.first().getOrNull(index) ?: continue
            if (name.isEmpty() || names.any { it.getOrNull(index) != name }) continue
            hints += InlayInfo(name, argument.textRange.startOffset)
        }
        return hints
    }

    /** `Math.Clamp` with its parameter names, which the exclude list's patterns match. */
    override fun getHintInfo(element: PsiElement): HintInfo? {
        val call = EnCalls.ofArgList(element) ?: return null
        val method = EnCalls.methodsOf(call, EnCalls.arguments(element).size).firstOrNull() ?: return null
        val owner = PsiTreeUtil.getParentOfType(method, EnClassDeclMixin::class.java)?.name
        val name = listOfNotNull(owner, method.name).joinToString(".")
        return HintInfo.MethodInfo(name, EnCalls.parameterNames(method), EnfusionLanguage)
    }

    override fun getDefaultBlackList(): Set<String> = setOf(
        // Printing and errors: the one argument is the message.
        "Print", "PrintFormat", "Error", "Error2", "ErrorEx", "DPrint", "PrintString",
        // Maths: the parameter names (`a`, `b`, `min`, `max`) add nothing.
        "Math.*", "Vector", "*.Clamp",
        // Containers and strings: element access reads plainly.
        "*.Get", "*.Set", "*.Insert", "*.InsertAt", "*.Find", "*.Remove", "*.RemoveItem",
        "*.Contains", "*.IndexOf", "string.Format", "*.Substring", "*.Replace", "*.Split",
        // Construction of the `Param` holders: each argument is just the next value.
        "Param*.Param*",
    )

    override fun getSettingsPreview(): String =
        "class Preview\n{\n\tvoid Run(Object item)\n\t{\n\t\tGetGame().GetCallQueue(CALL_CATEGORY_SYSTEM).CallLater(Run, 1000, false, item);\n\t}\n}\n"

    override fun getDescription(): String =
        "Parameter names before number, boolean, null and string arguments of a call"
}

/** The call around an argument list, its arguments, and what it calls. */
internal object EnCalls {

    /** The call or `new` expression [element] is the argument list of. */
    fun ofArgList(element: PsiElement): PsiElement? {
        if (element.node?.elementType !== EnfusionTypes.ARG_LIST) return null
        val parent = element.parent ?: return null
        val type = parent.node?.elementType
        return parent.takeIf { type === EnfusionTypes.CALL_EXPR || type === EnfusionTypes.NEW_EXPR }
    }

    fun arguments(argList: PsiElement): List<PsiElement> =
        argList.children.filter { it !is PsiWhiteSpace && it !is PsiComment }

    /** `12`, `-1.5`, `true`, `null`, `"text"`. */
    fun isLiteral(argument: PsiElement): Boolean = when (argument.node?.elementType) {
        EnfusionTypes.LITERAL_EXPR -> true
        EnfusionTypes.UNARY_EXPR -> argument.firstChild?.node?.elementType === EnfusionTypes.MINUS &&
            argument.lastChild?.node?.elementType === EnfusionTypes.LITERAL_EXPR
        else -> false
    }

    /**
     * The overloads that can take [argumentCount] arguments. A call resolves through its callee's
     * reference, which is cached and already narrows by argument count; `new X(...)` has no
     * reference to its constructor and uses the parameter-info lookup.
     */
    fun methodsOf(call: PsiElement, argumentCount: Int): List<EnMethodDeclMixin> {
        val candidates = if (call.node.elementType === EnfusionTypes.NEW_EXPR) {
            val argList = call.node.findChildByType(EnfusionTypes.ARG_LIST)?.psi ?: return emptyList()
            EnCallTargets.overloadsOf(argList)
        } else {
            val callee = call.children.firstOrNull { it !is PsiWhiteSpace && it !is PsiComment } ?: return emptyList()
            val reference = callee.reference as? PsiPolyVariantReference ?: return emptyList()
            reference.multiResolve(false).mapNotNull { it.element as? EnMethodDeclMixin }
        }
        return candidates
            .filter { argumentCount in minParams(it)..parameterNames(it).size }
            .distinctBy { it.getParameterListText() }
    }

    fun parameterNames(method: EnMethodDeclMixin): List<String> = params(method).map { (it as? EnNamedElement)?.name.orEmpty() }

    private fun params(method: EnMethodDeclMixin): List<PsiElement> {
        val list = method.node.findChildByType(EnfusionTypes.PARAM_LIST)?.psi ?: return emptyList()
        return list.children.filter { it.node?.elementType === EnfusionTypes.PARAM }
    }

    private fun minParams(method: EnMethodDeclMixin): Int =
        params(method).count { it.node?.findChildByType(EnfusionTypes.EQ) == null }
}
