package io.github.lemyst.enfusion.objfmt.preview

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjPropertyMixin
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjTree
import io.github.lemyst.enfusion.refs.EnStringLiterals
import io.github.lemyst.enfusion.texture.EnTextureMarkers
import java.awt.image.BufferedImage

/** Reading a `.layout` file's widget tree into [WidgetSpec]s. Needs a read action. */
object LayoutSpecs {

    fun of(file: PsiFile): List<WidgetSpec> = EnObjTree.objectChildrenOf(file).filter(::isWidget).map(::spec)

    private fun isWidget(decl: EnObjDeclMixin): Boolean = decl.getClassName()?.endsWith("WidgetClass") == true

    private fun spec(decl: EnObjDeclMixin): WidgetSpec {
        val properties = LinkedHashMap<String, List<String>>()
        for (property in PsiTreeUtil.getChildrenOfTypeAsList(decl, EnObjPropertyMixin::class.java)) {
            val key = property.getKey()?.lowercase() ?: continue
            properties.putIfAbsent(key, property.values().map { EnStringLiterals.content(it) ?: it.text })
        }
        return WidgetSpec(
            type = decl.getClassName() ?: "",
            name = decl.declaredName(),
            properties = properties,
            children = EnObjTree.objectChildrenOf(decl).filter(::isWidget).map(::spec),
            offset = decl.textRange.startOffset,
            endOffset = decl.textRange.endOffset,
        )
    }

    /**
     * The picture an `ImageWidgetClass` shows: its `image0`, as an imageset entry
     * (`"set:dayz_gui image:Icon"`) or a texture path, through the same resolution and cache as the
     * texture hover. Null when it does not resolve or does not decode.
     */
    fun image(file: PsiFile, spec: WidgetSpec, side: Int): BufferedImage? {
        if (spec.offset < 0) return null
        val decl = PsiTreeUtil.getParentOfType(file.findElementAt(spec.offset), EnObjDeclMixin::class.java, false) ?: return null
        val value = decl.property("image0")?.values()?.firstOrNull() ?: return null
        for (reference in value.references.reversed()) {
            val target: PsiElement = reference.resolve() ?: continue
            EnTextureMarkers.preview(target, side)?.let { return it.image }
        }
        return null
    }
}
