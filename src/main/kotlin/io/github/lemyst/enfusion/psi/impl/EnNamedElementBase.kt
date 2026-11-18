package io.github.lemyst.enfusion.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.util.IncorrectOperationException
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.rename.EnIdentifierFactory
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.settings.EnfusionProjectSettings
import javax.swing.Icon

/**
 * Shared behaviour for every named declaration. Subclasses supply only [getNameIdentifier] and the
 * presentation, because that is genuinely all that differs between them.
 */
abstract class EnNamedElementBase(node: ASTNode) : ASTWrapperPsiElement(node), EnNamedElement {

    private companion object {
        /** Enough tail segments to disambiguate without swamping the popup. */
        const val PATH_SEGMENTS_SHOWN = 3
    }

    override fun getName(): String? = nameIdentifier?.text

    /**
     * Replaces the name identifier in place.
     *
     * Grafting a leaf parsed elsewhere, rather than editing the existing leaf's text, is what keeps
     * the tree consistent: a leaf's element type is fixed at parse time, so the new identifier has
     * to come from a real parse of the right language. [createNameLeaf] is the hook for the second
     * of those.
     */
    override fun setName(name: String): PsiElement {
        val identifier = nameIdentifier
            ?: throw IncorrectOperationException("This declaration has no name to change")
        identifier.replace(createNameLeaf(name))
        return this
    }

    /** Overridden by the object-format declarations, whose leaves come from the other parser. */
    protected open fun createNameLeaf(name: String): PsiElement =
        EnIdentifierFactory.createScriptIdentifier(project, name)

    /** Points navigation and Find Usages at the name rather than the whole declaration. */
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    /**
     * Enough of the containing file's path to tell two declarations of the same name apart.
     *
     * The Go to Declaration picker shows only the presentable text and the location string, and for
     * a library file the platform's default location is the library name — so every candidate for a
     * `modded class` looks identical ("Math / Enforce Script Sources" twice over). Showing the tail
     * of the path is what makes the choice meaningful.
     */
    fun fileLocation(): String? {
        val file = containingFile?.virtualFile ?: return null
        val path = file.path.replace('\\', '/')
        val origin = originOf(path)?.let { (name, root) ->
            addonUnder(file, root)?.takeUnless { it.equals(name, ignoreCase = true) }?.let { "$name/$it" } ?: name
        }
        val tail = path.split('/').filter { it.isNotEmpty() }
            .takeLast(PATH_SEGMENTS_SHOWN).joinToString("/")
        return if (origin != null) "$origin \u00b7 $tail" else tail
    }

    /**
     * The addon folder of [file] below [root], the mod: the outermost folder holding a
     * `config.cpp`, which is the PBO it is packed into. A mod of several addons repeats the same
     * paths in each, so its name alone does not tell them apart. Null when the mod is the addon.
     */
    private fun addonUnder(file: com.intellij.openapi.vfs.VirtualFile, root: String): String? {
        var addon: com.intellij.openapi.vfs.VirtualFile? = null
        var dir = file.parent
        while (dir != null) {
            val path = dir.path.replace('\\', '/').trimEnd('/')
            if (path.equals(root, ignoreCase = true) || !path.startsWith("$root/", ignoreCase = true)) break
            if (dir.findChild("config.cpp") != null) addon = dir
            dir = dir.parent
        }
        return addon?.name
    }

    /**
     * Which mod or source tree a file came from: its name, and the folder that name stands for.
     *
     * The path alone is not enough to tell candidates apart: mods mirror the vanilla directory
     * layout almost exactly, so vanilla, this mod, an `Extra/` sub-addon and Expansion all declare
     * their `PlayerBase` at `.../4_World/Entities/ManBase/PlayerBase.c`. The origin is the only
     * thing that actually differs.
     */
    private fun originOf(path: String): Pair<String, String>? {
        val basePath = project.basePath?.replace('\\', '/')?.trimEnd('/')
        if (basePath != null && path.startsWith("$basePath/", ignoreCase = true)) {
            return basePath.substringAfterLast('/') to basePath
        }
        for (root in EnfusionProjectSettings.getInstance(project).roots) {
            val normalised = root.replace('\\', '/').trimEnd('/')
            if (normalised.isNotEmpty() && path.startsWith("$normalised/", ignoreCase = true)) {
                val name = normalised.substringAfterLast('/')
                // `P:/scripts` would otherwise read as the unhelpful "scripts".
                return (if (name.equals("scripts", ignoreCase = true)) "vanilla" else name) to normalised
            }
        }
        return null
    }

    protected fun presentation(icon: Icon, location: String? = null): ItemPresentation =
        object : ItemPresentation {
            override fun getPresentableText(): String? = name
            override fun getLocationString(): String? = location
            override fun getIcon(unused: Boolean): Icon = icon
        }

    /** True when this declaration carries the given hard-keyword modifier token. */
    protected fun hasModifier(type: com.intellij.psi.tree.IElementType): Boolean =
        node.findChildByType(type) != null

    /** True when this declaration carries a contextual (IDENT-lexed) modifier such as `modded`. */
    protected fun hasContextualModifier(text: String): Boolean {
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType === EnfusionTypes.IDENT && child.text == text) return true
            // Stop at the first token that cannot be part of a modifier list.
            if (child.elementType === EnfusionTypes.LBRACE) return false
            child = child.treeNext
        }
        return false
    }
}
