package io.github.lemyst.enfusion.stringtable

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.roots.ProjectFileIndex
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
 * *Create stringtable row for 'STR_…'*, offered on an unresolved key. The row goes into one of the
 * mod's own stringtables, never a library's. [targets] decides which; when that leaves a real
 * choice, a short list is shown, best guesses first.
 */
class CreateStringTableRowFix(private val key: String) : LocalQuickFix {

    override fun getFamilyName(): String = "Create stringtable row"

    override fun getName(): String = "Create stringtable row for '$key'"

    override fun startInWriteAction(): Boolean = false

    /** Where the row may go: an existing stringtable, or a new one in an addon root. */
    sealed class Target(val label: String) {
        class Existing(val file: VirtualFile, label: String) : Target(label)
        class New(val root: VirtualFile, label: String) : Target(label)
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val from = descriptor.psiElement?.containingFile?.virtualFile ?: return
        val choices = targets(project, from)
        if (choices.size <= 1 || ApplicationManager.getApplication().isUnitTestMode) {
            choices.firstOrNull()?.let { addTo(project, it) }
            return
        }
        val step = object : BaseListPopupStep<Target>("Add '$key' to", choices) {
            override fun getTextFor(value: Target): String = value.label
            override fun onChosen(selectedValue: Target, finalChoice: Boolean): PopupStep<*>? =
                doFinalStep { addTo(project, selectedValue) }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor != null) popup.showInBestPositionFor(editor) else popup.showCenteredInCurrentWindow(project)
    }

    /**
     * The candidates, best first. A single one is used without asking.
     *
     * 1. A stringtable in the file's own addon (its nearest folder with a `config.cpp`).
     * 2. Otherwise the stringtable whose keys share the most leading `_` segments with [key], past
     *    `STR`. In a repository of several addons every stringtable meets the file only at the
     *    root, so folders say nothing, while `STR_AUTORUN_X` plainly belongs with `STR_AUTORUN_…`.
     * 3. Otherwise the project's only stringtable.
     * 4. Otherwise all of them by that score, then a new stringtable in the file's addon.
     */
    internal fun targets(project: Project, from: VirtualFile): List<Target> {
        val addon = addonRoot(from)
        val newTable = (addon ?: from.parent)?.let { Target.New(it, "New stringtable.csv in ${display(project, it)}") }
        if (DumbService.isDumb(project)) return listOfNotNull(newTable)
        val own = FilenameIndex.getVirtualFilesByName(StringTableIndex.FILE_NAME, true, ProjectScope.getContentScope(project))
            .filter { it.isWritable }
            .sortedBy { it.path }
        if (own.isEmpty()) return listOfNotNull(newTable)
        fun existing(file: VirtualFile) = Target.Existing(file, display(project, file))

        if (addon != null) {
            own.firstOrNull { addonRoot(it) == addon }?.let { return listOf(existing(it)) }
        }
        val scored = own.map { it to prefixScore(key, keysOf(it)) }.sortedByDescending { it.second }
        val best = scored.first().second
        if (best >= MIN_SHARED_SEGMENTS && scored.count { it.second == best } == 1) return listOf(existing(scored.first().first))
        if (own.size == 1) return listOf(existing(own.single()))
        return scored.map { existing(it.first) } + listOfNotNull(newTable)
    }

    private fun addTo(project: Project, target: Target) {
        val file = when (target) {
            is Target.Existing -> target.file
            is Target.New -> createTable(project, target.root) ?: return
        }
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
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
        OpenFileDescriptor(project, file, offset).navigate(true)
    }

    private fun keysOf(table: VirtualFile): List<String> {
        val text = FileDocumentManager.getInstance().getCachedDocument(table)?.text
            ?: runCatching { VfsUtil.loadText(table) }.getOrNull() ?: return emptyList()
        return StringTableCsv.parse(text).dataRows.map { it.key }
    }

    private fun display(project: Project, file: VirtualFile): String {
        val root = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        return root?.let { VfsUtil.getRelativePath(file, it) }?.ifEmpty { null } ?: file.presentableUrl
    }

    /** A new stringtable in [root], with the vanilla header when vanilla is indexed. */
    private fun createTable(project: Project, root: VirtualFile): VirtualFile? {
        root.findChild(StringTableIndex.FILE_NAME)?.let { return it }
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

    internal companion object {
        /** `STR` plus one more segment, as in `STR_AUTORUN_…`; `STR_` alone is shared by every key. */
        const val MIN_SHARED_SEGMENTS = 2

        /** The addon a file belongs to: its nearest folder holding a `config.cpp`. */
        fun addonRoot(file: VirtualFile): VirtualFile? {
            var dir = file.parent
            while (dir != null && dir.findChild("config.cpp") == null) dir = dir.parent
            return dir
        }

        /** The most leading `_`-separated segments [key] shares with any of [keys], ignoring case. */
        fun prefixScore(key: String, keys: Collection<String>): Int {
            val segments = key.uppercase().split('_')
            return keys.maxOfOrNull { other ->
                segments.zip(other.uppercase().split('_')).takeWhile { (a, b) -> a == b }.size
            } ?: 0
        }
    }
}
