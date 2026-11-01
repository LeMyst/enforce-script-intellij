package io.github.lemyst.enfusion.index

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import io.github.lemyst.enfusion.objfmt.EnfusionObjectFileType
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectFile
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin

/**
 * Name indexes over `.layout` and `.imageset`, the targets the string-literal references resolve to.
 *
 * Three indexes rather than one, because the three names live in different places and must not be
 * confused: a widget is addressed by its *instance* name, an imageset by the value of its `Name`
 * property, and an image by the `Name` of an `ImageSetDefClass`. Keying them together would let
 * `FindAnyWidget("Bleeding1")` resolve to an icon.
 */
object EnfusionObjectIndexes {

    /** Widget instance names, from `.layout` files only. */
    val WIDGETS: ID<String, Void> = ID.create("enfusion.obj.widget")

    /** The `Name` of an `ImageSetClass` — `battleroyale_gui`, not `dayzbr_gui.imageset`. */
    val IMAGE_SETS: ID<String, Void> = ID.create("enfusion.obj.imageset")

    /** The `Name` of each `ImageSetDefClass`. */
    val IMAGES: ID<String, Void> = ID.create("enfusion.obj.image")

    const val IMAGE_SET_CLASS = "ImageSetClass"
    const val IMAGE_SET_DEF_CLASS = "ImageSetDefClass"

    fun declarations(file: PsiFile): Collection<EnObjDeclMixin> =
        PsiTreeUtil.findChildrenOfType(file, EnObjDeclMixin::class.java)

    fun findFiles(id: ID<String, Void>, name: String, scope: com.intellij.psi.search.GlobalSearchScope) =
        FileBasedIndex.getInstance().getContainingFiles(id, name, scope)
}

abstract class EnfusionObjectIndexBase : ScalarIndexExtension<String>() {

    /** The names this file contributes, if any. */
    protected abstract fun names(file: EnfusionObjectFile): Sequence<String>

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        DefaultFileTypeSpecificInputFilter(EnfusionObjectFileType)

    override fun dependsOnFileContent(): Boolean = true

    /**
     * Bump on any change to what is indexed or how names are derived. Version 2 indexes GUID-form
     * widgets (`FrameWidgetClass "{…}" {`) under their `Name` property.
     */
    override fun getVersion(): Int = 2

    override fun getIndexer(): DataIndexer<String, Void, FileContent> =
        DataIndexer { content ->
            val file = content.psiFile as? EnfusionObjectFile ?: return@DataIndexer emptyMap()
            val result = HashMap<String, Void?>()
            for (name in names(file)) {
                if (name.isNotEmpty()) result[name] = null
            }
            result
        }
}

class EnfusionWidgetNameIndex : EnfusionObjectIndexBase() {

    override fun getName(): ID<String, Void> = EnfusionObjectIndexes.WIDGETS

    override fun names(file: EnfusionObjectFile): Sequence<String> {
        if (file.isImageSet()) return emptySequence()
        return EnfusionObjectIndexes.declarations(file).asSequence().mapNotNull { it.name }
    }
}

class EnfusionImageSetNameIndex : EnfusionObjectIndexBase() {

    override fun getName(): ID<String, Void> = EnfusionObjectIndexes.IMAGE_SETS

    override fun names(file: EnfusionObjectFile): Sequence<String> {
        if (!file.isImageSet()) return emptySequence()
        return EnfusionObjectIndexes.declarations(file).asSequence()
            .filter { it.getClassName() == EnfusionObjectIndexes.IMAGE_SET_CLASS }
            .mapNotNull { it.declaredName() }
    }
}

class EnfusionImageNameIndex : EnfusionObjectIndexBase() {

    override fun getName(): ID<String, Void> = EnfusionObjectIndexes.IMAGES

    override fun names(file: EnfusionObjectFile): Sequence<String> {
        if (!file.isImageSet()) return emptySequence()
        return EnfusionObjectIndexes.declarations(file).asSequence()
            .filter { it.getClassName() == EnfusionObjectIndexes.IMAGE_SET_DEF_CLASS }
            .mapNotNull { it.declaredName() }
    }
}
