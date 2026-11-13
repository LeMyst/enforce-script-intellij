package io.github.lemyst.enfusion.texture

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.index.EnfusionObjectIndexes
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import io.github.lemyst.enfusion.refs.EnAssetPaths

/**
 * Where an imageset entry's pixels are: which texture, and which rectangle of it.
 *
 * ```
 * ImageSetClass {
 *  Name "dayz_gui"
 *  RefSize 1024 1024
 *  Textures {
 *   ImageSetTextureClass { mpix 0  path "{534691EE0479871C}Gui/imagesets/dayz_gui.edds" }
 *   ImageSetTextureClass { mpix 1  path "{C139E49FD0ECAF9E}Gui/imagesets/dayz_gui@2x.edds" }
 *  }
 *  Images { ImageSetDefClass DayZLogo { Pos 0 0  Size 256 128 } }
 * }
 * ```
 *
 * `Pos` and `Size` are in `RefSize` units, which is what lets one set name a 1x and a 2x texture.
 * The highest `mpix` that resolves is used: it has the most detail, and the rectangle scales to it.
 */
object EnImageSetTextures {

    class Entry(val name: String, val texture: VirtualFile, val region: EnTexturePreviews.Region)

    /** True for the `ImageSetDefClass` declarations that [entryFor] can place. */
    fun isImageDef(element: PsiElement): Boolean =
        element is EnObjDeclMixin && element.getClassName() == EnfusionObjectIndexes.IMAGE_SET_DEF_CLASS

    fun entryFor(def: EnObjDeclMixin): Entry? {
        if (!isImageDef(def)) return null
        val pos = numbers(def, "Pos") ?: return null
        val size = numbers(def, "Size") ?: return null
        val set = setOf(def) ?: return null
        val texture = textureOf(set) ?: return null
        val ref = numbers(set, "RefSize")
        return Entry(
            def.declaredName() ?: "image",
            texture,
            EnTexturePreviews.Region(pos.first, pos.second, size.first, size.second, ref?.first, ref?.second),
        )
    }

    /** The `ImageSetClass` enclosing a definition: its outermost object, past `Images { }`. */
    private fun setOf(def: EnObjDeclMixin): EnObjDeclMixin? {
        var current: PsiElement? = def.parent
        var found: EnObjDeclMixin? = null
        while (current != null && current !is PsiFile) {
            if (current is EnObjDeclMixin && current.getClassName() == EnfusionObjectIndexes.IMAGE_SET_CLASS) found = current
            current = current.parent
        }
        return found
    }

    /**
     * Memoised per set: every image of the set asks for it, and an imageset can hold hundreds.
     * The path lookup reads the file name index, so the value also depends on the VFS and on the
     * library roots, not only on this PSI.
     */
    private fun textureOf(set: EnObjDeclMixin): VirtualFile? {
        if (DumbService.isDumb(set.project)) return null
        return CachedValuesManager.getCachedValue(set) {
            val project = set.project
            val candidates = PsiTreeUtil.findChildrenOfType(set, EnObjDeclMixin::class.java)
                .filter { it.getClassName() == "ImageSetTextureClass" }
                .mapNotNull { tex ->
                    val path = tex.propertyValue("path") ?: return@mapNotNull null
                    val mpix = tex.propertyValue("mpix")?.toIntOrNull() ?: 0
                    mpix to path
                }
                .sortedByDescending { it.first }
            val file = candidates.firstNotNullOfOrNull { (_, path) ->
                EnAssetPaths.findBySuffix(project, path).firstOrNull()
            }
            CachedValueProvider.Result.create(
                file,
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                ProjectRootManager.getInstance(project),
            )
        }
    }

    private fun numbers(decl: EnObjDeclMixin, key: String): Pair<Int, Int>? {
        val values = decl.property(key)?.values() ?: return null
        if (values.size < 2) return null
        val a = values[0].text.toDoubleOrNull()?.toInt() ?: return null
        val b = values[1].text.toDoubleOrNull()?.toInt() ?: return null
        return a to b
    }
}
