package io.github.lemyst.enfusion.objfmt.preview

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import io.github.lemyst.enfusion.index.StringTableIndex
import io.github.lemyst.enfusion.roots.EnfusionLibraryRootsProvider
import io.github.lemyst.enfusion.stringtable.format.CsvRow
import io.github.lemyst.enfusion.stringtable.format.StringTableCsv
import java.util.concurrent.ConcurrentHashMap

/**
 * The translations behind a layout's `"#key"` texts, so that the preview can show them in a chosen
 * language, as the game does. The keys are found through [StringTableIndex], in the project and its
 * roots, and each stringtable is parsed once per change: the vanilla one has 7,300 rows, and the
 * preview rebuilds as the layout is typed.
 *
 * The vanilla stringtable is not in the index. It is 5 MB, and the platform indexes no file over
 * its IntelliSense limit (2.5 MB by default), so the keys the index does not know are also looked
 * up in the `stringtable.csv` at the top of each root, where `P:\languagecore` keeps it. There
 * the case of a key does not matter, as in the game: `dialog_input_password` writes
 * `#STR_TextInputDialogRoot_WindowLabel0`, and the stringtable `str_textinputdialogroot_windowlabel0`.
 */
object LayoutTranslations {

    /** Each key's texts by language (lower case), and the languages found, in header order. */
    class Table(val texts: Map<String, Map<String, String>>, val languages: List<String>) {

        /**
         * What [text] shows in [language]: a `#key` becomes its translation, else its `original`,
         * else stays the key. Null [language] shows keys, as the preview did before languages.
         */
        fun show(text: String, language: String?): String {
            if (language == null || !text.startsWith('#')) return text
            val row = texts[text.substring(1)] ?: return text
            return row[language].takeUnless { it.isNullOrBlank() } ?: row["original"].takeUnless { it.isNullOrBlank() } ?: text
        }

        companion object {
            val EMPTY = Table(emptyMap(), emptyList())
        }
    }

    private class Parsed(val stamp: Long, val csv: StringTableCsv) {
        /** The rows by lower-case key, built on the first lookup that needs it. */
        val byKey: Map<String, CsvRow> by lazy { csv.dataRows.associateBy { it.key.lowercase() } }
    }

    private val parsed = ConcurrentHashMap<String, Parsed>()

    /** The rows of [keys] (without their `#`). Needs a read action; nothing while indexing. */
    fun of(project: Project, keys: Set<String>): Table {
        if (keys.isEmpty() || DumbService.isDumb(project)) return Table.EMPTY
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.allScope(project)
        val texts = HashMap<String, Map<String, String>>()
        val languages = LinkedHashSet<String>()
        for (key in keys) {
            for (file in index.getContainingFiles(StringTableIndex.NAME, key, scope)) {
                val csv = csvOf(file) ?: continue
                val row = csv.rowOf(key) ?: continue
                // Column 0 is the key ("Language" in the header), not a language.
                val columns = csv.columns.drop(1)
                languages += columns.map { it.lowercase() }
                texts.putIfAbsent(key, columns.withIndex().associate { (i, name) -> name.lowercase() to row.value(i + 1) })
            }
        }
        val missing = keys.filterNot { it in texts }
        if (missing.isNotEmpty()) {
            for (file in topLevelTables(project, scope)) {
                val parsed = parsedOf(file) ?: continue
                val columns = parsed.csv.columns.drop(1)
                var used = false
                for (key in missing) {
                    if (key in texts) continue
                    val row = parsed.byKey[key.lowercase()] ?: continue
                    used = true
                    texts[key] = columns.withIndex().associate { (i, name) -> name.lowercase() to row.value(i + 1) }
                }
                if (used) languages += columns.map { it.lowercase() }
            }
        }
        return Table(texts, languages.toList())
    }

    /** The `stringtable.csv` directly under each content and library root. */
    private fun topLevelTables(project: Project, scope: GlobalSearchScope): List<VirtualFile> {
        val roots = ProjectRootManager.getInstance(project).contentRoots.toList() +
            EnfusionLibraryRootsProvider().getAdditionalProjectLibraries(project).flatMap { it.sourceRoots }
        return roots.mapNotNull { root -> root.findChild(StringTableIndex.FILE_NAME)?.takeIf { !it.isDirectory && scope.contains(it) } }.distinct()
    }

    /** The stringtable as the editor has it, unsaved changes included, parsed again only when it changes. */
    private fun csvOf(file: VirtualFile): StringTableCsv? = parsedOf(file)?.csv

    private fun parsedOf(file: VirtualFile): Parsed? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val stamp = document.modificationStamp
        parsed[file.url]?.takeIf { it.stamp == stamp }?.let { return it }
        return Parsed(stamp, StringTableCsv.parse(document.text)).also { parsed[file.url] = it }
    }
}
