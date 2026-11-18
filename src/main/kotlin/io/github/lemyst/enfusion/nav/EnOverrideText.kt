package io.github.lemyst.enfusion.nav

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * The methods [EnOverrideMethodsHandler] offers: what the class could override and has not yet.
 */
object EnOverrideCandidates {

    /**
     * Nearest first: in a `modded class`, the other layers, then the superclass chain; in any
     * other class, the chain. One entry per signature, the nearest declaration of it, so a method
     * that four layers redeclare is offered once.
     *
     * Left out: static methods, which cannot be overridden; `proto` methods, bound to the engine;
     * `sealed` ones; constructors and destructors; and private methods of a superclass. A
     * `modded class` is the class it patches, so the private methods of its other layers stay.
     */
    fun of(cls: EnClassDeclMixin): List<EnMethodDeclMixin> {
        val own = ownSignatures(cls)
        val seen = HashSet<String>(own)
        val result = ArrayList<EnMethodDeclMixin>()
        for (member in EnTypeInference.allSuperMembers(cls, cls.project)) {
            val method = member as? EnMethodDeclMixin ?: continue
            if (!isOverridable(method, cls)) continue
            if (seen.add(EnOverrideText.signatureKey(method))) result += method
        }
        return result
    }

    private fun ownSignatures(cls: EnClassDeclMixin): Set<String> {
        val body = cls.node.findChildByType(EnfusionTypes.CLASS_BODY)?.psi ?: return emptySet()
        return PsiTreeUtil.getChildrenOfTypeAsList(body, EnMethodDeclMixin::class.java)
            .mapTo(HashSet()) { EnOverrideText.signatureKey(it) }
    }

    private fun isOverridable(method: EnMethodDeclMixin, cls: EnClassDeclMixin): Boolean {
        if (method.isStatic() || method.isProto() || method.isConstructor() || method.isDestructor()) return false
        if (EnOverrideText.hasContextual(method, "sealed")) return false
        if (method.node.findChildByType(EnfusionTypes.PRIVATE) != null) {
            val owner = PsiTreeUtil.getParentOfType(method, EnClassDeclMixin::class.java)
            return owner?.name == cls.name
        }
        return true
    }
}

/** The text of an override, and where it goes. */
object EnOverrideText {

    /** Where the generated members go: after a member, or straight after the body's `{`. */
    data class Placement(val offset: Int, val afterBrace: Boolean)

    /**
     * `override protected bool CanPutInCargo(EntityAI parent)` with a body calling `super`.
     *
     * Kept as declared: `protected` and `private`, every parameter's modifiers, type, array extent
     * and default value. On `P:\scripts`, where a base method gives a default, vanilla's overrides
     * repeat it more often than they drop it, and the compiler accepts both. `event` and the other
     * engine-binding modifiers are the base declaration's business and are not repeated; vanilla
     * writes `override void EOnFrame(...)` over `event protected void EOnFrame(...)`.
     */
    fun of(method: EnMethodDeclMixin, callSuper: Boolean): String {
        val access = buildList {
            if (method.node.findChildByType(EnfusionTypes.PROTECTED) != null) add("protected")
            if (method.node.findChildByType(EnfusionTypes.PRIVATE) != null) add("private")
        }
        val returnType = method.getReturnTypeText()?.let(::collapse) ?: "void"
        val name = method.name.orEmpty()
        val params = parameters(method)
        val header = (listOf("override") + access + returnType + "$name(${params.joinToString(", ") { it.text }})")
            .joinToString(" ")
        val call = "super.$name(${params.joinToString(", ") { it.name }})"
        val isVoid = EnTypeInference.baseTypeName(returnType) == "void"
        val body = when {
            callSuper && isVoid -> "$call;"
            callSuper -> "return $call;"
            isVoid -> ""
            else -> "return ${defaultValue(returnType)};"
        }
        return "$header\n{\n$body\n}"
    }

    /** `CanPutInCargo(EntityAI parent): bool`, for the chooser. */
    fun chooserText(method: EnMethodDeclMixin): String {
        val returnType = method.getReturnTypeText()?.let(::collapse) ?: "void"
        return "${method.name}(${parameters(method).joinToString(", ") { it.text }}): $returnType"
    }

    /**
     * Name and parameter types: what makes two declarations the same method. Overloads differ in
     * it, so `Send(int)` stays offered after `Send(int, string)` is overridden.
     */
    fun signatureKey(method: EnMethodDeclMixin): String =
        "${method.name}(${parameters(method).joinToString(",") { it.type }})"

    fun placement(cls: EnClassDeclMixin, caret: Int): Placement? {
        val body = cls.node.findChildByType(EnfusionTypes.CLASS_BODY) ?: return null
        val brace = body.findChildByType(EnfusionTypes.LBRACE) ?: return null
        var anchor: ASTNode? = null
        var child = body.firstChildNode
        while (child != null) {
            val type = child.elementType
            val skipped = child.psi is PsiWhiteSpace || child.psi is PsiComment ||
                type === EnfusionTypes.LBRACE || type === EnfusionTypes.RBRACE
            if (!skipped) {
                if (child.startOffset < caret) anchor = child else break
            }
            child = child.treeNext
        }
        if (anchor == null) return Placement(brace.textRange.endOffset, afterBrace = true)
        return Placement(lineEndAfterTrailingComment(anchor), afterBrace = false)
    }

    /** `int m_Count; //!< text`: the comment stays on its member's line. */
    private fun lineEndAfterTrailingComment(anchor: ASTNode): Int {
        val text = anchor.psi.containingFile.viewProvider.contents
        var offset = anchor.textRange.endOffset
        while (offset < text.length && (text[offset] == ' ' || text[offset] == '\t')) offset++
        if (offset + 1 < text.length && text[offset] == '/' && text[offset + 1] == '/') {
            while (offset < text.length && text[offset] != '\n' && text[offset] != '\r') offset++
            return offset
        }
        return anchor.textRange.endOffset
    }

    fun hasContextual(method: EnMethodDeclMixin, modifier: String): Boolean {
        var child = method.node.firstChildNode
        while (child != null && child.elementType !== EnfusionTypes.TYPE_REF) {
            if (child.elementType === EnfusionTypes.IDENT && child.text == modifier) return true
            child = child.treeNext
        }
        return false
    }

    private class Parameter(val text: String, val name: String, val type: String)

    private fun parameters(method: EnMethodDeclMixin): List<Parameter> {
        val list = method.node.findChildByType(EnfusionTypes.PARAM_LIST)?.psi ?: return emptyList()
        return list.children.filter { it.node.elementType === EnfusionTypes.PARAM }.map { param ->
            val name = (param as? EnNamedElement)?.name.orEmpty()
            val typeRef = param.node.findChildByType(EnfusionTypes.TYPE_REF)?.text?.let(::collapse).orEmpty()
            Parameter(collapse(textWithoutComments(param.node)), name, typeRef)
        }
    }

    private fun textWithoutComments(node: ASTNode): String {
        val out = StringBuilder()
        fun walk(n: ASTNode) {
            if (n.psi is PsiComment) return
            if (n.psi is PsiWhiteSpace) {
                out.append(' ')
                return
            }
            val first = n.firstChildNode
            if (first == null) out.append(n.text)
            var c = first
            while (c != null) {
                walk(c)
                c = c.treeNext
            }
        }
        walk(node)
        return out.toString()
    }

    private fun collapse(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** What a method that does not call `super` returns, so the generated code compiles. */
    private fun defaultValue(returnType: String): String = when (EnTypeInference.baseTypeName(returnType)) {
        "bool" -> "false"
        "int", "float" -> "0"
        "string" -> "\"\""
        "vector" -> "vector.Zero"
        else -> "null"
    }
}
