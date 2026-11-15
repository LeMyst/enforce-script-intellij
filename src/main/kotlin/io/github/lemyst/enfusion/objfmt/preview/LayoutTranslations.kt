package io.github.lemyst.enfusion.objfmt.preview

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import io.github.lemyst.enfusion.index.StringTableIndex
import io.github.lemyst.enfusion.stringtable.format.StringTableCsv
import java.util.concurrent.ConcurrentHashMap

/**
 * The translations behind a layout's `"#key"` texts, so that the preview can show them in a chosen
 * language, as the game does. The keys are found through [StringTableIndex], in the project and its
 * roots (the vanilla stringtable included), and each stringtable is parsed once per change: the
 * vanilla one has 7,300 rows, and the preview rebuilds as the layout is typed.
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

    private class Parsed(val stamp: Long, val csv: StringTableCsv)

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
        return Table(texts, languages.toList())
    }

    /** The stringtable as the editor has it, unsaved changes included, parsed again only when it changes. */
    private fun csvOf(file: VirtualFile): StringTableCsv? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val stamp = document.modificationStamp
        parsed[file.url]?.takeIf { it.stamp == stamp }?.let { return it.csv }
        val csv = StringTableCsv.parse(document.text)
        parsed[file.url] = Parsed(stamp, csv)
        return csv
    }
}
