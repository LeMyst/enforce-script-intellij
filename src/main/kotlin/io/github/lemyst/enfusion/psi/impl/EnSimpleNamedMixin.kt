package io.github.lemyst.enfusion.psi.impl

import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.EnfusionTypes
import javax.swing.Icon

/**
 * Every declaration whose name is simply its first direct IDENT child: enums, enum constants,
 * typedefs, fields and locals, parameters, and `foreach` variables.
 *
 * Looking only at *direct* children is what keeps a type's own identifier out of the running —
 * in `typedef map<Item, vector> TItemsMap` and `foreach (EntityAI item : items)` the type is a
 * composite `typeRef` child, so its inner IDENT is never a candidate.
 */
abstract class EnSimpleNamedMixin(node: ASTNode) : EnNamedElementBase(node) {

    override fun getNameIdentifier(): PsiElement? =
        node.findChildByType(EnfusionTypes.IDENT)?.psi

    private fun isStatic(): Boolean = hasModifierOnSelfOrParent(EnfusionTypes.STATIC)

    private fun isConst(): Boolean = hasModifierOnSelfOrParent(EnfusionTypes.CONST)

    /**
     * `static` and `const` sit on the enclosing `varDecl`, not on the individual declarator, since
     * one declaration can introduce several names.
     */
    private fun hasModifierOnSelfOrParent(type: com.intellij.psi.tree.IElementType): Boolean {
        if (node.findChildByType(type) != null) return true
        val parentNode = node.treeParent ?: return false
        return parentNode.findChildByType(type) != null
    }

    private fun typeText(): String? {
        val own = node.findChildByType(EnfusionTypes.TYPE_REF)
        if (own != null) return own.text.trim()
        // A varDeclarator carries no type of its own; it comes from the enclosing declaration.
        return node.treeParent?.findChildByType(EnfusionTypes.TYPE_REF)?.text?.trim()
    }

    override fun getPresentation(): ItemPresentation {
        val type = node.elementType
        val icon: Icon = when (type) {
            EnfusionTypes.ENUM_DECL -> AllIcons.Nodes.Enum
            EnfusionTypes.ENUM_CONSTANT -> AllIcons.Nodes.Constant
            EnfusionTypes.TYPEDEF_DECL -> AllIcons.Nodes.Alias
            EnfusionTypes.PARAM -> AllIcons.Nodes.Parameter
            EnfusionTypes.FOREACH_VAR -> AllIcons.Nodes.Variable
            else -> when {
                isConst() -> AllIcons.Nodes.Constant
                isStatic() -> AllIcons.Nodes.Static
                else -> AllIcons.Nodes.Field
            }
        }
        return presentation(icon, typeText())
    }
}
