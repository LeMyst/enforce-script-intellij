package io.github.lemyst.enfusion.texture

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.index.EnfusionObjectIndexes
import io.github.lemyst.enfusion.objfmt.EnfusionObjectLanguage
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
 * `Pos` and `Size` count in pixels of the set's base texture, the lowest `mpix`, and a sharper
 * texture such as `@2x` scales them by its size against the base: that is what lets one set name a
 * 1x and a 2x texture. `RefSize` normally repeats the base texture's size and is used only when the
 * base cannot be found. They do disagree once in vanilla: `dayz_crosshairs` says `RefSize 1024
 * 1024` over a 512² texture, and its images sit in that texture's own pixels (`imperfect`, `Pos 55
 * 4` `Size 128 256`, is exactly the arc drawn there), so the texture is what to trust.
 *
 * Hover and the gutter show the sharpest texture that resolves: it has the most detail.
 */
object EnImageSetTextures {

    class Entry(val name: String, val texture: VirtualFile, val region: EnTexturePreviews.Region)

    /** True for the `ImageSetDefClass` declarations that [entryFor] can place. */
    fun isImageDef(element: PsiElement): Boolean =
        element is EnObjDeclMixin && element.getClassName() == EnfusionObjectIndexes.IMAGE_SET_DEF_CLASS

    fun entryFor(def: EnObjDeclMixin): Entry? {
        if (!isImageDef(def)) return null
        val set = setOf(def) ?: return null
        val textures = texturesOf(set) ?: return null
        val texture = textures.sharpest ?: return null
        val region = regionOf(def, textures.space) ?: return null
        return Entry(def.declaredName() ?: "image", texture, region)
    }

    /** An image of some imageset, as it lies on one of the textures its set names. */
    class Placed(val def: EnObjDeclMixin, val name: String, val setName: String, val region: EnTexturePreviews.Region)

    /**
     * Every image that some imageset places on [texture]: what the viewer outlines over an atlas.
     *
     * Unlike [entryFor], every texture a set names counts, not only the sharpest: the 1x and the 2x
     * atlas of a set both carry its images. Built for all textures at once and memoised per
     * project, since an imageset names its textures and not the other way round. Only layout and
     * imageset edits invalidate it, plus the VFS and the roots, which the path lookup reads.
     */
    fun entriesOn(project: Project, texture: VirtualFile): List<Placed> {
        if (DumbService.isDumb(project)) return emptyList()
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                placedByTexture(project),
                PsiModificationTracker.getInstance(project).forLanguage(EnfusionObjectLanguage),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                ProjectRootManager.getInstance(project),
            )
        }[texture].orEmpty()
    }

    private fun placedByTexture(project: Project): Map<VirtualFile, List<Placed>> {
        val psiManager = PsiManager.getInstance(project)
        val result = HashMap<VirtualFile, MutableList<Placed>>()
        for (vf in FilenameIndex.getAllFilesByExt(project, "imageset", GlobalSearchScope.allScope(project))) {
            ProgressManager.checkCanceled()
            val file = psiManager.findFile(vf) ?: continue
            val decls = PsiTreeUtil.findChildrenOfType(file, EnObjDeclMixin::class.java)
            for (set in decls.filter { it.getClassName() == EnfusionObjectIndexes.IMAGE_SET_CLASS }) {
                val textures = texturesOf(set) ?: continue
                if (textures.all.isEmpty()) continue
                val setName = set.declaredName() ?: vf.nameWithoutExtension
                val placed = PsiTreeUtil.findChildrenOfType(set, EnObjDeclMixin::class.java).filter(::isImageDef).mapNotNull { def ->
                    regionOf(def, textures.space)?.let { Placed(def, def.declaredName() ?: "image", setName, it) }
                }
                for (texture in textures.all) result.getOrPut(texture) { ArrayList() } += placed
            }
        }
        return result
    }

    private fun regionOf(def: EnObjDeclMixin, space: Pair<Int, Int>?): EnTexturePreviews.Region? {
        val pos = numbers(def, "Pos") ?: return null
        val size = numbers(def, "Size") ?: return null
        return EnTexturePreviews.Region(pos.first, pos.second, size.first, size.second, space?.first, space?.second)
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
     * A set's textures and the space its images count in.
     *
     * @property all every file any of the set's paths resolves to, in `mpix` order.
     * @property sharpest the highest `mpix` that resolves.
     * @property space the base texture's size, or `RefSize` when the base cannot be read.
     */
    private class SetTextures(val all: List<VirtualFile>, val sharpest: VirtualFile?, val space: Pair<Int, Int>?)

    /**
     * Memoised per set: every image of the set asks for it, and an imageset can hold hundreds.
     * The path lookup reads the file name index, so the value also depends on the VFS and on the
     * library roots, not only on this PSI; and on the base texture itself, whose size it reads.
     */
    private fun texturesOf(set: EnObjDeclMixin): SetTextures? {
        if (DumbService.isDumb(set.project)) return null
        return CachedValuesManager.getCachedValue(set) {
            val project = set.project
            val byMpix = PsiTreeUtil.findChildrenOfType(set, EnObjDeclMixin::class.java)
                .filter { it.getClassName() == "ImageSetTextureClass" }
                .mapNotNull { tex ->
                    val path = tex.propertyValue("path") ?: return@mapNotNull null
                    (tex.propertyValue("mpix")?.toIntOrNull() ?: 0) to EnAssetPaths.findBySuffix(project, path)
                }
                .sortedBy { it.first }
                .map { it.second }
            // Only the lowest `mpix` itself is the base. Were it missing, the next one up is a 2x
            // texture, and taking its size would halve every region.
            val base = byMpix.firstOrNull()?.firstOrNull()
            val space = base?.let(EnTexturePreviews::dimensions) ?: numbers(set, "RefSize")
            val dependencies = listOfNotNull(
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                ProjectRootManager.getInstance(project),
                base,
            )
            CachedValueProvider.Result.create(
                SetTextures(byMpix.flatten().distinct(), byMpix.lastOrNull { it.isNotEmpty() }?.first(), space),
                *dependencies.toTypedArray(),
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
