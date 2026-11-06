package io.github.lemyst.enfusion.psi.impl

import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.EnfusionTypes

abstract class EnMethodDeclMixin(node: ASTNode) : EnNamedElementBase(node) {

    /**
     * The name is the IDENT immediately before the parameter list. Searching backwards from there
     * rather than forwards from the start is what keeps attribute and modifier identifiers — and
     * the type's own name — from being mistaken for the method name.
     */
    override fun getNameIdentifier(): PsiElement? {
        val paramList = node.findChildByType(EnfusionTypes.PARAM_LIST) ?: return null
        var child = paramList.treePrev
        while (child != null) {
            if (child.elementType === EnfusionTypes.IDENT) return child.psi
            child = child.treePrev
        }
        return null
    }

    fun isStatic(): Boolean = hasModifier(EnfusionTypes.STATIC)

    fun isOverride(): Boolean = hasModifier(EnfusionTypes.OVERRIDE)

    fun isProto(): Boolean = hasModifier(EnfusionTypes.PROTO)

    /** `void ~ClassName()`. Note the `void` return type — the `~` is the only marker. */
    fun isDestructor(): Boolean = node.findChildByType(EnfusionTypes.TILDE) != null

    /**
     * Constructors are written `void ClassName(...)`, syntactically identical to any other method,
     * so this can only be answered by comparing against the enclosing class.
     */
    fun isConstructor(): Boolean {
        if (isDestructor()) return false
        val owner = parentOfTypeClassDecl() ?: return false
        return owner.name != null && owner.name == name
    }

    fun getReturnTypeText(): String? = node.findChildByType(EnfusionTypes.TYPE_REF)?.text?.trim()

    fun getParameterListText(): String {
        val params = node.findChildByType(EnfusionTypes.PARAM_LIST)?.psi ?: return "()"
        return params.text.replace(Regex("\\s+"), " ")
    }

    private fun parentOfTypeClassDecl(): EnClassDeclMixin? {
        var element: PsiElement? = parent
        while (element != null) {
            if (element is EnClassDeclMixin) return element
            element = element.parent
        }
        return null
    }

    /**
     * The signature, then the class and file the method belongs to.
     *
     * Every popup that offers several methods reads this: the overridden-method gutter icon, the
     * Go to Declaration picker, Find Usages. Overrides share a signature by definition, and the
     * platform's own location for a library file is the library name, so without the owner and
     * the file every `modded class` layer of `OnEvent` read "OnEvent ((EventType eventTypeId,
     * Param params): void) Enforce Script Sources" and the choice was a guess.
     */
    override fun getPresentation(): ItemPresentation {
        val location = buildString {
            append(signatureText())
            val owner = parentOfTypeClassDecl()
            val file = fileLocation()
            if (owner?.name != null || file != null) append("  —  ")
            owner?.name?.let { append(if (owner.isModded()) "modded $it" else it) }
            if (owner?.name != null && file != null) append(" · ")
            file?.let { append(it) }
        }
        return presentation(icon(), location)
    }

    /** The signature alone, for the Structure view, where the class and file are already in view. */
    fun signaturePresentation(): ItemPresentation = presentation(icon(), signatureText())

    private fun icon() = when {
        isConstructor() || isDestructor() -> AllIcons.Nodes.ClassInitializer
        isStatic() -> AllIcons.Nodes.MethodReference
        isOverride() -> AllIcons.General.OverridingMethod
        else -> AllIcons.Nodes.Method
    }

    private fun signatureText(): String {
        val returnType = getReturnTypeText()
        return if (returnType != null) "${getParameterListText()}: $returnType" else getParameterListText()
    }
}
