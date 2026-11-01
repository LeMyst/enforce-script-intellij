package io.github.lemyst.enfusion.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorIntegerDescriptor
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/**
 * Maps a localization key to the line it is declared on, across every `stringtable.csv` in the
 * project and in the configured roots.
 *
 * The value is a **line number, not a character offset**. Offsets from [FileContent] are taken over
 * text whose line endings the platform has already normalised to `\n`, so on a CRLF file they drift
 * from the offsets an editor would use — by one character per preceding line. A line number
 * survives that normalisation untouched, and `OpenFileDescriptor` accepts one directly.
 *
 * No parser is involved. The format is regular, and rename is out of scope, so a language would buy
 * nothing that this does not.
 */
object StringTableIndex {

    val NAME: ID<String, Int> = ID.create("enfusion.stringtable")

    const val FILE_NAME = "stringtable.csv"

    /**
     * The first CSV field of a row, unquoted.
     *
     * Only the key column is read, and it is always a simple quoted token — so this deliberately
     * stops at the first comma outside the quotes rather than implementing CSV properly. Translation
     * columns routinely contain commas, quotes and semicolons, and none of that matters here.
     */
    fun keyOf(line: String): String? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith('"')) return null
        val end = trimmed.indexOf('"', startIndex = 1)
        if (end <= 1) return null
        return trimmed.substring(1, end)
    }

    /** The header row names the column set rather than declaring a key. */
    private fun isHeader(key: String): Boolean = key.equals("Language", ignoreCase = true)

    fun index(text: CharSequence): Map<String, Int> {
        val result = HashMap<String, Int>()
        var line = 0
        for (raw in text.lineSequence()) {
            val key = keyOf(raw)
            if (key != null && key.isNotEmpty() && !isHeader(key)) result.putIfAbsent(key, line)
            line++
        }
        return result
    }
}

class EnfusionStringTableIndex : FileBasedIndexExtension<String, Int>() {

    override fun getName(): ID<String, Int> = StringTableIndex.NAME

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<Int> = EnumeratorIntegerDescriptor.INSTANCE

    override fun dependsOnFileContent(): Boolean = true

    /** Bump on any change to what is indexed or to the meaning of the value. */
    override fun getVersion(): Int = 1

    /**
     * Matched by name, not by file type: `.csv` belongs to no plugin here, and a stringtable is
     * recognisable only by being called one. The engine loads exactly one per addon, at its root.
     */
    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> file.name.equals(StringTableIndex.FILE_NAME, ignoreCase = true) }

    override fun getIndexer(): DataIndexer<String, Int, FileContent> =
        DataIndexer { content -> StringTableIndex.index(content.contentAsText) }
}
