package io.github.lemyst.enfusion.hints

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionFile
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnCallTargets

/**
 * Parameter names before the literal arguments of a call: in
 * `CallLater(Refresh, 1000, false)`, is `false` *repeat*?
 *
 * A declarative provider, not the platform's `InlayParameterHintsProvider`: that one and every
 * class it takes (`InlayInfo`, `HintInfo`) are experimental API, which the Plugin Verifier
 * reports; the declarative API is not. The cost is the platform's editable exclude list, so the
 * list here is fixed ([EnParameterNameHints.EXCLUDED]).
 *
 * Literals only, as Java does: a number, `true` / `false`, `null` or a string, or a negated
 * number. A name or a constant usually says what it is (`ECE_PLACE_ON_SURFACE`). Shown only when
 * every overload the call may mean has the same parameter name at that position, so a hint is
 * never a guess; and not for a one-parameter setter, whose name already says it.
 */
class EnParameterNameHintsProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? =
        if (file is EnfusionFile) Collector else null

    private object Collector : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            for ((offset, name) in EnParameterNameHints.hintsFor(element)) {
                sink.addPresentation(InlineInlayPosition(offset, relatedToPrevious = false), hintFormat = HintFormat.default) {
                    text("$name:")
                }
            }
        }
    }
}

internal object EnParameterNameHints {

    /**
     * Calls whose arguments are obvious, as `Class.Method` patterns where `*` is any run of
     * characters: printing, maths, container access, and the `Param` holders, whose arguments
     * are just the next value.
     */
    val EXCLUDED = listOf(
        "Print", "PrintFormat", "Error", "Error2", "ErrorEx", "DPrint", "PrintString",
        "Math.*", "Vector", "*.Clamp",
        "*.Get", "*.Set", "*.Insert", "*.InsertAt", "*.Find", "*.Remove", "*.RemoveItem",
        "*.Contains", "*.IndexOf", "string.Format", "*.Substring", "*.Replace", "*.Split",
        "Param*.Param*",
    )

    private val excluded: List<Regex> = EXCLUDED.map { pattern ->
        Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })
    }

    /** `Class.Method`, or the bare name of a free function, as [EXCLUDED] matches it. */
    fun qualifiedName(method: EnMethodDeclMixin): String {
        val owner = PsiTreeUtil.getParentOfType(method, EnClassDeclMixin::class.java)?.name
        return listOfNotNull(owner, method.name).joinToString(".")
    }

    fun isExcluded(qualifiedName: String): Boolean = excluded.any { it.matches(qualifiedName) }

    /** The hints of the argument list [element]: each at a literal argument's start, with its name. */
    fun hintsFor(element: PsiElement): List<Pair<Int, String>> {
        val call = EnCalls.ofArgList(element) ?: return emptyList()
        val arguments = EnCalls.arguments(element)
        if (arguments.none(EnCalls::isLiteral)) return emptyList()
        val methods = EnCalls.methodsOf(call, arguments.size)
        if (methods.isEmpty() || methods.any { isExcluded(qualifiedName(it)) }) return emptyList()
        val names = methods.map(EnCalls::parameterNames)
        if (names.first().size == 1 && methods.first().name?.startsWith("Set") == true) return emptyList()

        val hints = ArrayList<Pair<Int, String>>()
        for ((index, argument) in arguments.withIndex()) {
            if (!EnCalls.isLiteral(argument)) continue
            val name = names.first().getOrNull(index) ?: continue
            if (name.isEmpty() || names.any { it.getOrNull(index) != name }) continue
            hints += argument.textRange.startOffset to name
        }
        return hints
    }
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
