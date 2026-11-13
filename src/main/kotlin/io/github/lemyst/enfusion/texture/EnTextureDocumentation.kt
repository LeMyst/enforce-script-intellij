package io.github.lemyst.enfusion.texture

import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.ContributedReferenceHost
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import io.github.lemyst.enfusion.refs.EnAssetPathReference
import io.github.lemyst.enfusion.refs.EnImageSetReference
import java.awt.Image

/**
 * Quick documentation for textures: hovering `"…/loading_1.edds"`, `picture="…/Mod_Logo.paa"` or
 * `"set:dayz_gui image:DayZLogo"` shows the image itself. For an imageset entry the preview is cut
 * out of the atlas, so it shows that one icon rather than the whole sheet.
 *
 * Two ways in. When a reference in the literal resolves to exactly one element, the platform hands
 * over that element: the texture file or the `ImageSetDefClass`. When it resolves to several, or
 * the platform cannot pick, it hands over the literal itself, and the references are resolved here
 * instead. The first texture or imageset entry any of them reaches is shown.
 */
class EnTextureDocumentationTargetProvider : PsiDocumentationTargetProvider {

    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? {
        if (EnTextureDocs.handles(element)) return EnTextureDocumentationTarget(element)
        return EnTextureDocs.targetInLiteral(element)?.let(::EnTextureDocumentationTarget)
    }
}

private class EnTextureDocumentationTarget(private val element: PsiElement) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(element)
        return Pointer { pointer.element?.let(::EnTextureDocumentationTarget) }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(EnTextureDocs.title(element)).icon(AllIcons.FileTypes.Image).presentation()

    override val navigatable: Navigatable?
        get() = element as? Navigatable

    override fun computeDocumentationHint(): String? = EnTextureDocs.title(element)

    override fun computeDocumentation(): DocumentationResult? {
        val rendered = EnTextureDocs.render(element) ?: return null
        return DocumentationResult.documentation(rendered.html).images(rendered.images)
    }
}

/** The HTML and its images, split from the target so that tests can read them. */
internal object EnTextureDocs {

    /** Device pixels. Large enough to judge an icon, small enough not to crowd the popup. */
    const val PREVIEW_SIDE = 192

    /**
     * The popup parses every `src` as an absolute [java.net.URL] and looks the URL's external form
     * up in [DocumentationResult]'s images; a bare name fails to parse and shows a broken image.
     * `https` because the popup refuses `file:` and similar schemes, and `.invalid` because that
     * domain is reserved: should the lookup ever miss, there is nothing on the network to fetch.
     */
    const val IMAGE_KEY = "https://enfusion-texture.invalid/preview.png"

    class Rendered(val html: String, val images: Map<String, Image>)

    fun handles(element: PsiElement): Boolean = textureFile(element) != null || EnImageSetTextures.isImageDef(element)

    /**
     * The texture or imageset entry a string literal names, resolved here rather than by the
     * platform. Only the two reference kinds that can lead to pixels are tried, so a literal full of
     * widget names or stringtable keys costs nothing.
     */
    fun targetInLiteral(literal: PsiElement): PsiElement? {
        if (literal !is ContributedReferenceHost) return null
        for (reference in literal.references) {
            if (reference !is EnAssetPathReference && reference !is EnImageSetReference) continue
            val results = (reference as PsiPolyVariantReference).multiResolve(false)
            for (result in results) {
                val target = result.element ?: continue
                if (handles(target)) return target
            }
        }
        return null
    }

    fun title(element: PsiElement): String = when {
        element is PsiFile -> element.name
        element is EnObjDeclMixin -> element.declaredName() ?: "image"
        else -> "texture"
    }

    fun render(element: PsiElement): Rendered? {
        val file = textureFile(element)
        val rows = ArrayList<Pair<String, String>>()
        val preview = if (file != null) {
            rows += "Path" to file.path
            EnTexturePreviews.ofTexture(file, PREVIEW_SIDE)
        } else {
            val def = element as? EnObjDeclMixin ?: return null
            val entry = EnImageSetTextures.entryFor(def) ?: return null
            val r = entry.region
            rows += "Region" to "${r.width} × ${r.height} at ${r.x}, ${r.y}"
            rows += "Texture" to entry.texture.path
            EnTexturePreviews.ofRegion(entry.texture, r, PREVIEW_SIDE)
        }

        val html = StringBuilder()
        html.append(DocumentationMarkup.DEFINITION_START)
            .append(StringUtil.escapeXmlEntities(title(element)))
            .append(DocumentationMarkup.DEFINITION_END)
        html.append(DocumentationMarkup.CONTENT_START)
        val images = when (preview) {
            is EnTexturePreviews.Preview.Image -> {
                html.append("<img src=\"").append(IMAGE_KEY).append("\" width=\"").append(preview.image.width)
                    .append("\" height=\"").append(preview.image.height).append("\">")
                rows.add(0, "Texture size" to preview.summary.describe())
                mapOf(IMAGE_KEY to preview.image)
            }

            is EnTexturePreviews.Preview.Failure -> {
                html.append("<i>").append(StringUtil.escapeXmlEntities(preview.message)).append("</i>")
                emptyMap()
            }
        }
        html.append(DocumentationMarkup.CONTENT_END)
        html.append(DocumentationMarkup.SECTIONS_START)
        for ((label, value) in rows) {
            html.append(DocumentationMarkup.SECTION_HEADER_START).append(StringUtil.escapeXmlEntities(label))
                .append(":").append(DocumentationMarkup.SECTION_SEPARATOR)
                .append(StringUtil.escapeXmlEntities(value)).append(DocumentationMarkup.SECTION_END)
        }
        html.append(DocumentationMarkup.SECTIONS_END)
        return Rendered(html.toString(), images)
    }

    private fun textureFile(element: PsiElement) =
        (element as? PsiFile)?.virtualFile?.takeIf { FileTypeRegistry.getInstance().isFileOfType(it, EnTextureFileType) }
}
