package io.github.lemyst.enfusion.texture

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * `.edds` and `.paa`: binary, so the IDE never tries to open them as text, and opened by
 * [EnTextureEditorProvider] instead.
 *
 * Claiming `.paa` by extension is safe in a way `.c` is not: nothing else uses it. One type serves
 * both formats because users treat them as one thing (a texture), and the reader tells them apart
 * by content anyway.
 */
object EnTextureFileType : FileType {

    /** An ID, persisted in settings: never rename it. */
    override fun getName(): String = "EnfusionTexture"

    override fun getDescription(): String = "Enforce Texture (DayZ .edds / .paa)"

    override fun getDisplayName(): String = "Enforce Texture"

    override fun getDefaultExtension(): String = "edds"

    override fun getIcon(): Icon = AllIcons.FileTypes.Image

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true

    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}
