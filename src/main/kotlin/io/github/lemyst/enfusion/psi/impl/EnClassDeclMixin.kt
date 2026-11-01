package io.github.lemyst.enfusion.psi.impl

import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.EnfusionTokenSets
import io.github.lemyst.enfusion.psi.EnfusionTypes

abstract class EnClassDeclMixin(node: ASTNode) : EnNamedElementBase(node) {

    /**
     * The name is the first token after `class`, and it is not always an IDENT: the engine declares
     * `class bool`, `class string`, `class int` and friends in 1_core/proto, so a primitive type
     * keyword is a legal class name.
     */
    override fun getNameIdentifier(): PsiElement? {
        val keyword = node.findChildByType(EnfusionTypes.CLASS_KW) ?: return null
        var child = keyword.treeNext
        while (child != null) {
            val type = child.elementType
            if (type === EnfusionTypes.IDENT || EnfusionTokenSets.PRIMITIVE_TYPES.contains(type)) {
                return child.psi
            }
            child = child.treeNext
        }
        return null
    }

    /** `modded class X` layers over a previously compiled `X` rather than declaring a new type. */
    fun isModded(): Boolean = hasContextualModifier("modded")

    fun isSealed(): Boolean = hasContextualModifier("sealed")

    /** The name after `extends` or `:` — both spellings occur, freely mixed within one file. */
    fun getSuperName(): String? {
        val clause = node.findChildByType(EnfusionTypes.SUPER_CLAUSE) ?: return null
        return clause.findChildByType(EnfusionTypes.TYPE_REF)?.text?.trim()
    }

    override fun getPresentation(): ItemPresentation {
        val icon = if (isModded()) AllIcons.Nodes.AbstractClass else AllIcons.Nodes.Class
        val superName = getSuperName()
        // The path matters more than the superclass here: when several layers of a `modded class`
        // are offered, the file is the only thing that distinguishes them.
        val location = buildString {
            when {
                isModded() -> append("modded")
                superName != null -> append(": ").append(superName)
            }
            fileLocation()?.let {
                if (isNotEmpty()) append("  —  ")
                append(it)
            }
        }
        return presentation(icon, location.ifEmpty { null })
    }
}
