package io.github.lemyst.enfusion.texture

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * `.edds`: binary, so the IDE never tries to open it as text, and opened by
 * [EnTextureEditorProvider] instead.
 *
 * Named for textures in general rather than for `.edds`, because `.paa` (#14) is meant to join it:
 * users treat both as one thing, and the reader tells them apart by content.
 */
object EnTextureFileType : FileType {

    /** An ID, persisted in settings: never rename it. */
    override fun getName(): String = "EnfusionTexture"

    override fun getDescription(): String = "Enforce Texture (DayZ .edds)"

    override fun getDisplayName(): String = "Enforce Texture"

    override fun getDefaultExtension(): String = "edds"

    override fun getIcon(): Icon = AllIcons.FileTypes.Image

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true

    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}
