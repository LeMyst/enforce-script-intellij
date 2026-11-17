package io.github.lemyst.enfusion.objfmt.preview

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.lemyst.enfusion.index.StringTables
import io.github.lemyst.enfusion.stringtable.format.CsvRow
import io.github.lemyst.enfusion.stringtable.format.StringTableCsv
import java.util.concurrent.ConcurrentHashMap

/**
 * The translations behind a layout's `"#key"` texts, so that the preview can show them in a chosen
 * language, as the game does. The stringtables declaring the keys are found through
 * [StringTables], in the project and its roots, the vanilla one included, and the case of a key
 * does not matter, as in the game: `dialog_input_password` writes
 * `#STR_TextInputDialogRoot_WindowLabel0`, and the stringtable `str_textinputdialogroot_windowlabel0`.
 * Each stringtable is parsed once per change: the vanilla one has 7,000 rows, and the preview
 * rebuilds as the layout is typed.
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
        /** The rows by lower-case key, the first of a key winning, built on the first lookup that needs it. */
        val byKey: Map<String, CsvRow> by lazy {
            HashMap<String, CsvRow>().also { map -> csv.dataRows.forEach { map.putIfAbsent(it.key.lowercase(), it) } }
        }
    }

    private val parsed = ConcurrentHashMap<String, Parsed>()

    /** The rows of [keys] (without their `#`). Needs a read action; nothing while indexing. */
    fun of(project: Project, keys: Set<String>): Table {
        if (keys.isEmpty() || DumbService.isDumb(project)) return Table.EMPTY
        val texts = HashMap<String, Map<String, String>>()
        val languages = LinkedHashSet<String>()
        for (key in keys) {
            for (file in StringTables.filesOf(project, key)) {
                val parsed = parsedOf(file) ?: continue
                val row = parsed.byKey[key.lowercase()] ?: continue
                // Column 0 is the key ("Language" in the header), not a language.
                val columns = parsed.csv.columns.drop(1)
                languages += columns.map { it.lowercase() }
                texts.putIfAbsent(key, columns.withIndex().associate { (i, name) -> name.lowercase() to row.value(i + 1) })
            }
        }
        return Table(texts, languages.toList())
    }

    /** The stringtable as the editor has it, unsaved changes included, parsed again only when it changes. */
    private fun parsedOf(file: VirtualFile): Parsed? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val stamp = document.modificationStamp
        parsed[file.url]?.takeIf { it.stamp == stamp }?.let { return it }
        return Parsed(stamp, StringTableCsv.parse(document.text)).also { parsed[file.url] = it }
    }
}
