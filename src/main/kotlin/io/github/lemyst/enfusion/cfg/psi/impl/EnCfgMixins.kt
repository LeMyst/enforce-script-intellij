package io.github.lemyst.enfusion.cfg.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.ContributedReferenceHost
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes
import io.github.lemyst.enfusion.psi.impl.EnNamedElementBase
import io.github.lemyst.enfusion.refs.EnStringLiterals
import io.github.lemyst.enfusion.rename.EnIdentifierFactory

/**
 * One config class — either `class Name : Parent { … }` or the bare `class Name;` forward
 * declaration, which appears in 226 of the 377 files surveyed.
 *
 * Both shapes share this mixin because both introduce the name. A forward declaration exists
 * precisely so a later `class X : Name` has something to bind to, so treating it as a lesser thing
 * would break the link it was written for.
 */
abstract class EnCfgClassMixin(node: ASTNode) : EnNamedElementBase(node) {

    override fun getNameIdentifier(): PsiElement? =
        node.findChildByType(EnfusionConfigTypes.IDENT)?.psi

    /** The name after `:`, or null — most config classes state no parent. */
    fun getSuperName(): String? = superIdentifier()?.text

    /** The identifier itself, which is what the super reference attaches to. */
    fun superIdentifier(): PsiElement? {
        val clause = node.findChildByType(EnfusionConfigTypes.SUPER_CLAUSE) ?: return null
        return clause.findChildByType(EnfusionConfigTypes.IDENT)?.psi
    }

    /** True for `class Name;` — a declaration with no body. */
    fun isForwardDeclaration(): Boolean =
        node.elementType === EnfusionConfigTypes.FORWARD_DECL

    /** The class that lexically encloses this one, or null at file scope. */
    fun enclosingClass(): EnCfgClassMixin? =
        PsiTreeUtil.getParentOfType(this, EnCfgClassMixin::class.java)

    /**
     * The value of a directly-owned property, by key.
     *
     * Direct children only, for the same reason as the object format's equivalent: nested classes
     * carry properties of the same names, and reading through the subtree would attribute a child's
     * `name` to its parent.
     */
    fun propertyValue(key: String): EnCfgPropertyMixin? =
        PsiTreeUtil.getChildrenOfTypeAsList(this, EnCfgPropertyMixin::class.java)
            .firstOrNull { key.equals(it.getKey(), ignoreCase = true) }

    override fun createNameLeaf(name: String): PsiElement =
        EnIdentifierFactory.createConfigIdentifier(project, name)

    override fun getPresentation(): ItemPresentation {
        val icon = if (isForwardDeclaration()) AllIcons.Nodes.AbstractClass else AllIcons.Nodes.Class
        val superName = getSuperName()
        val location = buildString {
            if (superName != null) append(": ").append(superName)
            if (isForwardDeclaration()) {
                if (isNotEmpty()) append("  ")
                append("(forward declaration)")
            }
            fileLocation()?.let {
                if (isNotEmpty()) append("  —  ")
                append(it)
            }
        }
        return presentation(icon, location.ifEmpty { null })
    }
}

/** One `key = value;` or `key[] += {…};` line. */
abstract class EnCfgPropertyMixin(node: ASTNode) : ASTWrapperPsiElement(node) {

    fun getKey(): String? = node.findChildByType(EnfusionConfigTypes.IDENT)?.text

    /** True for `key[] = …`, which is how every list-valued property is written. */
    fun isArray(): Boolean = node.findChildByType(EnfusionConfigTypes.LBRACKET) != null

    /** True for `+=`, which appends to an inherited array instead of replacing it. */
    fun isAppend(): Boolean = node.findChildByType(EnfusionConfigTypes.PLUS_EQ) != null

    /** True for `-=`, the mirror image: remove these entries from what was inherited. */
    fun isRemove(): Boolean = node.findChildByType(EnfusionConfigTypes.MINUS_EQ) != null

    fun value(): EnCfgValueMixin? =
        PsiTreeUtil.getChildrenOfTypeAsList(this, EnCfgValueMixin::class.java).firstOrNull()

    /**
     * The strings this property holds, unquoted — one for a scalar, all of them for an array.
     *
     * The array case looks one level in rather than through the whole subtree, so a table of tables
     * yields nothing rather than a flattened muddle.
     */
    fun stringValues(): List<String> {
        val value = value() ?: return emptyList()
        val elements = if (value.isArray()) value.elements() else listOf(value)
        return elements.mapNotNull { EnStringLiterals.content(it) }
    }
}

/**
 * A single value: a string, a number, an identifier, or a `{…}` array.
 *
 * As a [ContributedReferenceHost] it carries the same references the `.layout` values do — vanilla
 * configs are full of `displayName="#STR_CfgShoulder0"` and
 * `ghostIcon="set:dayz_inventory image:shoulderleft"` — so the stringtable and imageset providers
 * transplant here unchanged, which is most of what makes this language cheap.
 */
abstract class EnCfgValueMixin(node: ASTNode) : ASTWrapperPsiElement(node), ContributedReferenceHost {

    fun isArray(): Boolean = node.findChildByType(EnfusionConfigTypes.ARRAY_VALUE) != null

    /** The values inside a `{…}`, one level deep. Empty when this value is a scalar. */
    fun elements(): List<EnCfgValueMixin> {
        val array = node.findChildByType(EnfusionConfigTypes.ARRAY_VALUE)?.psi ?: return emptyList()
        return PsiTreeUtil.getChildrenOfTypeAsList(array, EnCfgValueMixin::class.java)
    }

    /** The property this value belongs to, looking through any enclosing arrays. */
    fun owningProperty(): EnCfgPropertyMixin? =
        PsiTreeUtil.getParentOfType(this, EnCfgPropertyMixin::class.java)

    override fun getReferences(): Array<PsiReference> =
        ReferenceProvidersRegistry.getReferencesFromProviders(this)

    override fun getReference(): PsiReference? = references.firstOrNull()
}
