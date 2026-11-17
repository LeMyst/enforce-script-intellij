package io.github.lemyst.enfusion.objfmt.preview

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjPropertyMixin
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjTree
import io.github.lemyst.enfusion.refs.EnAssetPaths
import io.github.lemyst.enfusion.refs.EnStringLiterals
import io.github.lemyst.enfusion.texture.EnTextureMarkers
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap

/** Reading a `.layout` file's widget tree into [WidgetSpec]s. Needs a read action. */
object LayoutSpecs {

    fun of(file: PsiFile): List<WidgetSpec> {
        val fonts = HashMap<String, FontFiles.Metrics?>()
        val font = { value: String ->
            val key = value.trim().lowercase()
            if (key in fonts) fonts[key] else fontOf(file.project, value).also { fonts[key] = it }
        }
        return EnObjTree.objectChildrenOf(file).filter(::isWidget).map { spec(it, font) }
    }

    private fun isWidget(decl: EnObjDeclMixin): Boolean = decl.getClassName()?.endsWith("WidgetClass") == true

    private fun spec(decl: EnObjDeclMixin, font: (String) -> FontFiles.Metrics?): WidgetSpec {
        val properties = LinkedHashMap<String, List<String>>()
        for (property in PsiTreeUtil.getChildrenOfTypeAsList(decl, EnObjPropertyMixin::class.java)) {
            val key = property.getKey()?.lowercase() ?: continue
            properties.putIfAbsent(key, property.values().map { EnStringLiterals.content(it) ?: it.text })
        }
        return WidgetSpec(
            type = decl.getClassName() ?: "",
            name = decl.declaredName(),
            properties = properties,
            children = EnObjTree.objectChildrenOf(decl).filter(::isWidget).map { spec(it, font) },
            offset = decl.textRange.startOffset,
            endOffset = decl.textRange.endOffset,
            font = properties["font"]?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(font),
        )
    }

    /**
     * The metrics of a widget's `font` (`"gui/fonts/sdf_MetronBook24"`), read from its `.fnt`, found
     * like any asset path (vanilla's on the work drive); when no file is found, the size its name
     * gives ([FontFiles.sizeInName]). A file is read again only once it changes.
     */
    private fun fontOf(project: Project, value: String): FontFiles.Metrics? {
        val byName = FontFiles.sizeInName(value)?.let { FontFiles.Metrics(it, null) }
        if (DumbService.isDumb(project)) return byName
        val path = value.trim().let { if (it.endsWith(".fnt", ignoreCase = true)) it else "$it.fnt" }
        val file = EnAssetPaths.findBySuffix(project, path).firstOrNull() ?: return byName
        fontsRead[file.url]?.takeIf { it.stamp == file.modificationStamp }?.let { return it.metrics }
        val metrics = runCatching { FontFiles.read(file.contentsToByteArray()) }.getOrNull()
        fontsRead[file.url] = FontRead(file.modificationStamp, metrics)
        return metrics
    }

    private class FontRead(val stamp: Long, val metrics: FontFiles.Metrics?)

    private val fontsRead = ConcurrentHashMap<String, FontRead>()

    /** Whether [spec] names a picture [image] can look for. */
    fun hasImage(spec: WidgetSpec): Boolean =
        !spec.value("image0").isNullOrBlank() || !spec.value("imagetexture").isNullOrBlank()

    /**
     * The picture an `ImageWidgetClass` shows, through the same resolution and cache as the texture
     * hover: its `image0`, as an imageset entry (`"set:dayz_gui image:Icon"`) or a texture path, else
     * its `imageTexture`. Null when it does not resolve or does not decode. The picture comes back
     * tinted, masked and flipped as the widget says ([LayoutImages]), never the cached original.
     */
    fun image(file: PsiFile, spec: WidgetSpec, side: Int): BufferedImage? {
        val picture = picture(file, spec, side) ?: return null
        val mask = spec.value("mask")?.let { texture(file, it, side) }
        return LayoutImages.finish(picture, spec, mask)
    }

    private fun picture(file: PsiFile, spec: WidgetSpec, side: Int): BufferedImage? {
        if (spec.offset < 0) return null
        val decl = PsiTreeUtil.getParentOfType(file.findElementAt(spec.offset), EnObjDeclMixin::class.java, false) ?: return null
        decl.property("image0")?.values()?.firstOrNull()?.let { value ->
            for (reference in value.references.reversed()) {
                val target: PsiElement = reference.resolve() ?: continue
                EnTextureMarkers.preview(target, side)?.let { return it.image }
            }
        }
        return spec.value("imagetexture")?.let { texture(file, it, side) }
    }

    /**
     * An `imageTexture` value. It is a resource reference, `"{E6B3EF816E067499}Gui/textures/x.edds"`:
     * the braces hold the resource's ID, which only Workbench's database maps, and the path after
     * them is found like any asset path, by its longest matching suffix.
     */
    private fun texture(file: PsiFile, value: String, side: Int): BufferedImage? {
        val path = value.substringAfter('}').trim().takeIf { it.isNotEmpty() } ?: return null
        val psiManager = PsiManager.getInstance(file.project)
        for (candidate in EnAssetPaths.findBySuffix(file.project, path)) {
            val target = psiManager.findFile(candidate) ?: continue
            EnTextureMarkers.preview(target, side)?.let { return it.image }
        }
        return null
    }
}
