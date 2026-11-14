package io.github.lemyst.enfusion.stringtable

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import io.github.lemyst.enfusion.index.StringTableIndex
import io.github.lemyst.enfusion.stringtable.format.StringTableCsv

/**
 * Structural problems in a `stringtable.csv`: an unclosed quote, a row whose cell count differs
 * from the header's, a duplicate key. Each of them silently shifts or hides translations, and the
 * game loads the file anyway.
 *
 * Registered for every language, because `.csv` may be plain text or the Database plugin's CSV
 * language depending on the IDE; the visitor is empty for any file not named `stringtable.csv`.
 */
class EnStringTableStructureInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!holder.file.name.equals(StringTableIndex.FILE_NAME, ignoreCase = true)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val csv = StringTableCsv.parse(file.text)
                for (problem in csv.problems()) {
                    val end = maxOf(problem.end, problem.start + 1).coerceAtMost(file.textLength)
                    if (problem.start >= end) continue
                    holder.registerProblem(file, problem.message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, TextRange(problem.start, end))
                }
            }
        }
    }
}

/**
 * *Create stringtable row for 'STR_…'*, offered on an unresolved key. The row goes into the mod's
 * own stringtable, never a library's: the one nearest the file using the key, or a new
 * `stringtable.csv` at the addon root, with the vanilla header when vanilla is indexed.
 */
class CreateStringTableRowFix(private val key: String) : LocalQuickFix {

    override fun getFamilyName(): String = "Create stringtable row"

    override fun getName(): String = "Create stringtable row for '$key'"

    override fun startInWriteAction(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val from = descriptor.psiElement?.containingFile?.virtualFile ?: return
        val target = existingTable(project, from) ?: createTable(project, from) ?: return
        val document = FileDocumentManager.getInstance().getDocument(target) ?: return
        val csv = StringTableCsv.parse(document.text)
        if (csv.rowOf(key) != null) return
        val edit = csv.appendRow(key, emptyMap())
        WriteCommandAction.runWriteCommandAction(project, name, null, {
            document.replaceString(edit.start, edit.end, edit.replacement)
        })
        // The caret goes inside the quotes of the new row's `original` cell.
        val row = StringTableCsv.parse(document.text).rowOf(key)
        val original = csv.columnIndex(StringTableCsv.ORIGINAL)
        val offset = row?.cells?.getOrNull(original)?.let { it.start + 1 } ?: row?.start ?: 0
        OpenFileDescriptor(project, target, offset).navigate(true)
    }

    /** The project's own stringtable closest to [from], by shared path. */
    private fun existingTable(project: Project, from: VirtualFile): VirtualFile? {
        if (DumbService.isDumb(project)) return null
        val own = FilenameIndex.getVirtualFilesByName(StringTableIndex.FILE_NAME, true, ProjectScope.getContentScope(project))
            .filter { it.isWritable }
        return own.maxByOrNull { table -> VfsUtil.getCommonAncestor(table, from)?.path?.length ?: -1 }
    }

    /** A new stringtable at the addon root of [from]: the nearest folder holding a `config.cpp`. */
    private fun createTable(project: Project, from: VirtualFile): VirtualFile? {
        var dir = from.parent
        while (dir != null && dir.findChild("config.cpp") == null) dir = dir.parent
        val root = dir ?: from.parent ?: return null
        val header = vanillaHeader(project) ?: StringTableCsv.headerLine(StringTableCsv.DEFAULT_COLUMNS)
        return WriteCommandAction.writeCommandAction(project).withName(name).compute<VirtualFile, RuntimeException> {
            val file = root.createChildData(this, StringTableIndex.FILE_NAME)
            VfsUtil.saveText(file, header + "\n")
            file
        }
    }

    /** The header row of an indexed stringtable outside the project, which is vanilla's. */
    private fun vanillaHeader(project: Project): String? {
        if (DumbService.isDumb(project)) return null
        val all = FilenameIndex.getVirtualFilesByName(StringTableIndex.FILE_NAME, false, GlobalSearchScope.allScope(project))
        val library = all.firstOrNull { !ProjectScope.getContentScope(project).contains(it) } ?: return null
        val text = runCatching { VfsUtil.loadText(library) }.getOrNull() ?: return null
        val header = StringTableCsv.parse(text).header ?: return null
        return text.substring(header.start, header.end).takeIf { it.isNotBlank() }
    }
}
