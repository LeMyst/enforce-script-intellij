package io.github.lemyst.enfusion.pbo

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * `.pbo`: binary, so the IDE never tries to open one as text, and opened by [EnPboEditorProvider].
 * Claiming it by extension is safe in a way `.c` is not: nothing else uses it.
 */
object EnPboFileType : FileType {

    /** An ID, persisted in settings: never rename it. */
    override fun getName(): String = "EnfusionPbo"

    override fun getDescription(): String = "Enforce PBO archive (DayZ .pbo)"

    override fun getDisplayName(): String = "Enforce PBO"

    override fun getDefaultExtension(): String = "pbo"

    override fun getIcon(): Icon = AllIcons.FileTypes.Archive

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true

    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}
