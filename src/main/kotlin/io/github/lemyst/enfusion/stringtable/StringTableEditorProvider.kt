package io.github.lemyst.enfusion.stringtable

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.lemyst.enfusion.index.StringTableIndex

/**
 * Adds a *Table* tab after the text editor of every `stringtable.csv`. The text stays the default
 * tab: it is what diffs, search and the error highlighting work on, and the table is one click away.
 */
class StringTableEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        !file.isDirectory && file.name.equals(StringTableIndex.FILE_NAME, ignoreCase = true) &&
            file.length <= MAX_BYTES

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = StringTableEditor(project, file)

    override fun getEditorTypeId(): String = "enfusion-stringtable-table"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR

    private companion object {
        /** The vanilla stringtable is about 1.5 MB; past 32 MB the file is not a stringtable anyone edits. */
        const val MAX_BYTES = 32L * 1024 * 1024
    }
}
