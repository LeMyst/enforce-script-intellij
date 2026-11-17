package io.github.lemyst.enfusion.objfmt.preview

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.index.EnfusionObjectIndexes
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import io.github.lemyst.enfusion.texture.EnImageSetTextures
import io.github.lemyst.enfusion.texture.EnTexturePreviews
import java.util.concurrent.ConcurrentHashMap

/**
 * The frames the layout preview draws for styled widgets: every `*.styles` file the project sees
 * (vanilla's `P:\gui\looknfeel\dayzwidgets.styles`, and a mod's own), each read once per change,
 * and each piece's picture found through the imageset index and cut from its texture like any
 * `"set:… image:…"`.
 *
 * Needs a read action and smart mode.
 */
object LayoutStyles {

    /** The styles of every `.styles` file, the project's own winning over a library's of the same name. */
    fun sheet(project: Project): WidgetStyles.Sheet {
        if (DumbService.isDumb(project)) return WidgetStyles.Sheet.EMPTY
        val files = FilenameIndex.getAllFilesByExt(project, "styles", GlobalSearchScope.allScope(project))
        val content = ProjectFileIndex.getInstance(project)
        return files.sortedWith(compareBy<VirtualFile> { !content.isInContent(it) }.thenBy { it.path })
            .mapNotNull(::sheetOf)
            .fold(WidgetStyles.Sheet.EMPTY) { sheet, next -> sheet.plus(next) }
    }

    private class Read(val stamp: Long, val sheet: WidgetStyles.Sheet)

    private val read = ConcurrentHashMap<String, Read>()

    private fun sheetOf(file: VirtualFile): WidgetStyles.Sheet? {
        if (!file.isValid || file.isDirectory) return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        read[file.url]?.takeIf { it.stamp == stamp }?.let { return it.sheet }
        val text = document?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return null
        return WidgetStyles.parse(text).also { read[file.url] = Read(stamp, it) }
    }

    /**
     * The frames of [specs] that wear a style with pictures in its `Normal` state, tinted by their
     * `color`. A widget whose style is unknown, draws nothing, or whose pictures are all missing
     * is left out, and keeps its plain colour.
     */
    fun frames(project: Project, specs: Collection<WidgetSpec>, sheet: WidgetStyles.Sheet = sheet(project)): Map<WidgetSpec, LayoutRenderer.Frame> {
        if (DumbService.isDumb(project)) return emptyMap()
        val pictures = HashMap<Pair<String, String>, LayoutRenderer.Picture?>()
        val result = HashMap<WidgetSpec, LayoutRenderer.Frame>()
        for (spec in specs) {
            val style = sheet.styleOf(spec.type, spec.value("style")) ?: continue
            val names = style.frame()
            if (names.isEmpty()) continue
            val pieces = names.mapNotNull { (piece, image) ->
                val picture = pictures.getOrPut(style.imageSet to image) { picture(project, style.imageSet, image) } ?: return@mapNotNull null
                val tint = spec.color
                piece to if (tint == null) picture else LayoutRenderer.Picture(LayoutImages.tint(picture.image, tint), picture.width, picture.height)
            }.toMap()
            if (pieces.isNotEmpty()) result[spec] = LayoutRenderer.Frame(pieces)
        }
        return result
    }

    /** The picture [image] of the imageset named [set], at its own size. */
    private fun picture(project: Project, set: String, image: String): LayoutRenderer.Picture? {
        if (set.isBlank()) return null
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        for (file in EnfusionObjectIndexes.findFiles(EnfusionObjectIndexes.IMAGE_SETS, set, scope)) {
            val psi = psiManager.findFile(file) ?: continue
            val def = PsiTreeUtil.findChildrenOfType(psi, EnObjDeclMixin::class.java)
                .firstOrNull { it.getClassName() == EnfusionObjectIndexes.IMAGE_SET_DEF_CLASS && it.declaredName() == image }
                ?: continue
            val entry = EnImageSetTextures.entryFor(def) ?: continue
            val region = entry.region
            val side = maxOf(region.width, region.height).coerceIn(1, MAX_SIDE)
            val preview = EnTexturePreviews.ofRegion(entry.texture, region, side) as? EnTexturePreviews.Preview.Image ?: continue
            return LayoutRenderer.Picture(preview.image, region.width.toDouble(), region.height.toDouble())
        }
        return null
    }

    /** A frame's pieces are small; a centre picture larger than this is drawn from a smaller mip. */
    private const val MAX_SIDE = 1024
}
