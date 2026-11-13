package io.github.lemyst.enfusion.texture

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.ui.scale.JBUIScale
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectTypes
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.Icon

/**
 * Texture thumbnails in the gutter: beside each `ImageSetDefClass` of an imageset, and beside every
 * line of script or layout naming a texture or an imageset image. Clicking one goes to the entry or
 * the texture.
 *
 * Slow markers only. Producing an icon can mean decoding an atlas mip, and the fast pass must never
 * wait on that; the slow pass runs in the background and is cancellable between elements. Decoded
 * mips are shared through [EnTexturePreviews], so the icons of one imageset decode its atlas once.
 *
 * Being a [LineMarkerProviderDescriptor] is what puts a checkbox for it under Settings | Editor |
 * General | Gutter Icons, so anyone who finds it noisy can turn it off.
 */
open class EnTextureLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = "Texture preview"

    override fun getIcon(): Icon = AllIcons.FileTypes.Image

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val document = elements.firstOrNull()?.containingFile
            ?.let { PsiDocumentManager.getInstance(it.project).getDocument(it) }
        // One icon per line. Configs write `hiddenSelectionsTextures[]={"…a.paa","…b.paa"}` on a
        // single line, and icons stacked side by side in the gutter are noise rather than a preview.
        val linesDone = HashSet<Int>()
        for (element in elements) {
            // Markers belong on leaves, or the platform warns and the icon flickers between passes.
            if (element.firstChild != null) continue
            ProgressManager.checkCanceled()
            val line = document?.getLineNumber(element.textRange.startOffset)
            if (line != null && line in linesDone) continue
            val marker = markerFor(element) ?: continue
            result.add(marker)
            if (line != null) linesDone += line
        }
    }

    private fun markerFor(leaf: PsiElement): LineMarkerInfo<PsiElement>? {
        val target = targetOf(leaf) ?: return null
        val shown = EnTextureMarkers.preview(target, ICON_SOURCE_SIDE) ?: return null
        val pointer = SmartPointerManager.createPointer(target)
        val tooltip = "<html>${shown.tooltip}</html>"
        return LineMarkerInfo(
            leaf,
            leaf.textRange,
            TextureIcon(shown.image),
            { tooltip },
            GutterIconNavigationHandler { _, _ -> (pointer.element as? Navigatable)?.navigate(true) },
            GutterIconRenderer.Alignment.LEFT,
        ) { "Texture preview" }
    }

    /** The texture file or imageset entry this leaf shows, if any. */
    private fun targetOf(leaf: PsiElement): PsiElement? {
        val parent = leaf.parent ?: return null

        // The class name of an `ImageSetDefClass` inside an imageset: the first token of its line.
        if (parent is EnObjDeclMixin && leaf.node.elementType == EnfusionObjectTypes.IDENT &&
            parent.node.findChildByType(EnfusionObjectTypes.IDENT) == leaf.node
        ) {
            return parent.takeIf { EnImageSetTextures.isImageDef(it) }
        }

        // A string literal anywhere, marked on its only token. The literal resolves exactly as it
        // does for hover, so the gutter and the popup never disagree on what a literal shows.
        if (parent.firstChild != leaf || parent.lastChild != leaf) return null
        return EnTextureDocs.targetInLiteral(parent)
    }

    /**
     * Paints a thumbnail into a 16-pixel square, centred and with its aspect ratio kept. The source
     * is twice that, so it stays sharp on a 200% display.
     */
    private class TextureIcon(private val image: BufferedImage) : Icon {
        override fun getIconWidth(): Int = JBUIScale.scale(ICON_SIDE)
        override fun getIconHeight(): Int = JBUIScale.scale(ICON_SIDE)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val side = iconWidth
            val scale = side.toDouble() / maxOf(image.width, image.height)
            val w = maxOf(1, Math.round(image.width * scale).toInt())
            val h = maxOf(1, Math.round(image.height * scale).toInt())
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.drawImage(image, x + (side - w) / 2, y + (side - h) / 2, w, h, null)
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        const val ICON_SIDE = 16
        const val ICON_SOURCE_SIDE = 32
    }
}

/**
 * The same icons in `config.cpp` and `mod.cpp`, under a checkbox of their own: an item config can
 * name a texture on most of its lines, and someone who finds that busy should be able to turn the
 * configs off without losing the icons in layouts and imagesets.
 */
class EnTextureConfigLineMarkerProvider : EnTextureLineMarkerProvider() {
    override fun getName(): String = "Texture preview in configs"
}

/** The preview and tooltip for a gutter icon, kept apart from the provider for tests. */
internal object EnTextureMarkers {

    class Shown(val image: BufferedImage, val tooltip: String)

    fun preview(target: PsiElement, side: Int): Shown? {
        if (target is PsiFile) {
            val file = target.virtualFile ?: return null
            val preview = EnTexturePreviews.ofTexture(file, side) as? EnTexturePreviews.Preview.Image ?: return null
            return Shown(preview.image, "${escape(file.name)}<br>${escape(preview.summary.describe())}")
        }
        val def = target as? EnObjDeclMixin ?: return null
        val entry = EnImageSetTextures.entryFor(def) ?: return null
        val preview = EnTexturePreviews.ofRegion(entry.texture, entry.region, side) as? EnTexturePreviews.Preview.Image
            ?: return null
        val r = entry.region
        return Shown(
            preview.image,
            "${escape(entry.name)}<br>${r.width} × ${r.height} in ${escape(entry.texture.name)}",
        )
    }

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
}
