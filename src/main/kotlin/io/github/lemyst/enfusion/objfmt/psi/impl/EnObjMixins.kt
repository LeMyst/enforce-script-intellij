package io.github.lemyst.enfusion.objfmt.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.ContributedReferenceHost
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.lang.EnfusionIcons
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectTypes
import io.github.lemyst.enfusion.psi.impl.EnNamedElementBase
import io.github.lemyst.enfusion.refs.EnStringLiterals
import io.github.lemyst.enfusion.rename.EnIdentifierFactory

/**
 * Walking the object tree, looking through the anonymous children block.
 *
 * A widget's children live in a bare `{ … }` inside its body rather than directly under it. That
 * block names nothing, so every consumer wants to see through it — otherwise the tree gains a
 * nameless level between each widget and its children.
 */
object EnObjTree {

    fun objectChildrenOf(parent: PsiElement): List<EnObjDeclMixin> {
        val result = ArrayList<EnObjDeclMixin>()
        for (child in parent.children) {
            when {
                child is EnObjDeclMixin -> result += child
                child.node?.elementType === EnfusionObjectTypes.CHILD_BLOCK ->
                    result += objectChildrenOf(child)
            }
        }
        return result
    }
}

/**
 * One object in a layout or imageset: `PanelWidgetClass RowRoot { … }`, `ImageSetDefClass Bleeding1
 * { … }`, or a bare container such as `Textures { … }`.
 *
 * The declaration's *name* is the instance name — the identifier a widget is looked up by from
 * script — not the class name. Container blocks have no instance name and so contribute nothing to
 * the index, which is correct: nothing refers to them.
 */
abstract class EnObjDeclMixin(node: ASTNode) : EnNamedElementBase(node) {

    /** `PanelWidgetClass`, `ImageSetDefClass`, `Textures` — always the first identifier. */
    fun getClassName(): String? = node.findChildByType(EnfusionObjectTypes.IDENT)?.text

    /**
     * The token after the class name, when there is one: an identifier (`PanelWidgetClass RowRoot`)
     * or a quoted resource GUID (`FrameWidgetClass "{00000000EE70746B}"`).
     *
     * `findChildByType` cannot express "the second one", so the children are walked directly.
     * Stopping at the brace matters: every property inside the body is also built from identifiers.
     */
    private fun instanceSlot(): ASTNode? {
        var child = node.firstChildNode
        var seenClassName = false
        while (child != null) {
            when (child.elementType) {
                EnfusionObjectTypes.LBRACE -> return null
                EnfusionObjectTypes.IDENT, EnfusionObjectTypes.STRING ->
                    if (seenClassName) return child else seenClassName = true
                else -> Unit
            }
            child = child.treeNext
        }
        return null
    }

    /**
     * True for the GUID form: the instance slot holds a resource GUID, and the name a script looks
     * the widget up by lives in a `Name` property instead.
     */
    fun isGuidNamed(): Boolean = instanceSlot()?.elementType == EnfusionObjectTypes.STRING

    /**
     * The instance-name identifier or, for the GUID form, the value of the `Name` property.
     *
     * For the GUID form that value is what `FindAnyWidget("…")` matches, so it must also be what
     * the index records, what navigation lands on and what rename rewrites. The GUID is never a
     * name: nothing in script refers to it.
     */
    override fun getNameIdentifier(): PsiElement? {
        val slot = instanceSlot() ?: return null
        if (slot.elementType == EnfusionObjectTypes.IDENT) return slot.psi
        return nameProperty()?.values()?.firstOrNull()
    }

    /** Unquoted: the GUID form's identifier is a quoted value, and callers want the bare name. */
    override fun getName(): String? =
        if (isGuidNamed()) nameProperty()?.firstValueText() else super.getName()

    private fun nameProperty(): EnObjPropertyMixin? = property("Name")

    /**
     * A directly-owned property, by key.
     *
     * Scoped to direct children on purpose: an `ImageSetClass` and each of its `ImageSetDefClass`
     * children all carry a `Name`, and reading through the whole subtree would attribute the first
     * image's name to the set itself.
     */
    fun property(key: String): EnObjPropertyMixin? =
        PsiTreeUtil.getChildrenOfTypeAsList(this, EnObjPropertyMixin::class.java)
            .firstOrNull { key.equals(it.getKey(), ignoreCase = true) }

    /** The first value of a directly-owned property, by key; see [property]. */
    fun propertyValue(key: String): String? = property(key)?.firstValueText()

    /**
     * The declared `Name`, falling back to the instance name.
     *
     * Imagesets are addressed by the `Name` property — `"set:battleroyale_gui …"` names the set's
     * `Name`, not the file `dayzbr_gui.imageset` — so it has to win where the two differ.
     */
    fun declaredName(): String? = propertyValue("Name") ?: name

    override fun createNameLeaf(name: String): PsiElement =
        if (isGuidNamed()) EnIdentifierFactory.createObjectNameValue(project, name)
        else EnIdentifierFactory.createObjectIdentifier(project, name)

    override fun getPresentation(): ItemPresentation =
        presentation(EnfusionIcons.FILE, listOfNotNull(getClassName(), fileLocation()).joinToString("  "))
}

/** One `key value*` line. */
abstract class EnObjPropertyMixin(node: ASTNode) : ASTWrapperPsiElement(node) {

    /** The key, unquoted — `"Size To Content V" 1` is as real a property as `visible 1`. */
    fun getKey(): String? {
        val first = node.firstChildNode ?: return null
        return when (first.elementType) {
            EnfusionObjectTypes.IDENT -> first.text
            EnfusionObjectTypes.STRING -> EnStringLiterals.content(first.psi)
            else -> null
        }
    }

    fun values(): List<EnObjValueMixin> =
        PsiTreeUtil.getChildrenOfTypeAsList(this, EnObjValueMixin::class.java)

    /** The first value with its quotes stripped, which is what every caller here wants. */
    fun firstValueText(): String? {
        val value = values().firstOrNull() ?: return null
        return EnStringLiterals.content(value) ?: value.text
    }
}

/**
 * A single value on a property line, and — when quoted — a reference host.
 *
 * Layouts carry the same two kinds of navigable string that script does: `text "#STR_BR_WIN"` names
 * a stringtable row, `image0 "set:dayz_gui image:DayZLogo"` names an imageset entry. Sharing the
 * providers with the script side needs only this one interface.
 */
abstract class EnObjValueMixin(node: ASTNode) : ASTWrapperPsiElement(node), ContributedReferenceHost {

    override fun getReferences(): Array<PsiReference> =
        ReferenceProvidersRegistry.getReferencesFromProviders(this)

    override fun getReference(): PsiReference? = references.firstOrNull()
}
