package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.stringtable.format.StringTableCsv
import io.github.lemyst.enfusion.stringtable.format.TextEdit
import junit.framework.TestCase
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * Every `stringtable.csv` under the stringtable roots parses, and the table editor's edits leave it
 * byte for byte as it was when they are undone: writing each cell back with its own value, and
 * adding then removing a language column.
 *
 * Structural problems are printed, not failed: a mod's stringtable may really be malformed, and
 * that is what the inspection is for. The vanilla one must have none.
 *
 * The text is read as the editor document holds it, with line breaks normalised to `\n`.
 *
 * Skips when no root is present.
 */
class EnfusionStringTableCorpusTest : TestCase() {

    fun testStringTablesRoundTrip() {
        val roots = System.getProperty("enfusion.stringtable.roots").orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && File(it).isDirectory }
        if (roots.isEmpty()) {
            log("no stringtable roots present, skipping")
            return
        }
        val files = roots.flatMap { root -> stringTablesUnder(Paths.get(root)) }
        log("checking ${files.size} stringtables under ${roots.joinToString(", ")}")
        val failures = mutableListOf<String>()
        for (path in files) {
            val text = Files.readString(path).replace("\r\n", "\n").replace('\r', '\n')
            val t0 = System.nanoTime()
            val csv = StringTableCsv.parse(text)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val problems = csv.problems()
            log("  $path: ${csv.dataRows.size} keys, ${csv.columns.size} columns, ${problems.size} problems, parsed in $ms ms")
            problems.take(5).forEach { log("    line ${text.substring(0, it.start).count { c -> c == '\n' } + 1}: ${it.message}") }
            if (path.startsWith(VANILLA) && problems.isNotEmpty()) {
                failures += "$path: the vanilla stringtable has ${problems.size} problems"
            }

            val rewritten = apply(text, csv.dataRows.flatMap { row ->
                row.cells.indices.filter { row.cells[it].quoted && canonical(text, row.cells[it]) }.map { csv.setCell(row, it, row.value(it)) }
            })
            if (rewritten != text) failures += "$path: writing every cell back changed the file"

            if (csv.header != null && csv.columns.isNotEmpty()) {
                val added = apply(text, csv.addColumn("corpus_test") { it.value(1) })
                val reparsed = StringTableCsv.parse(added)
                if (reparsed.columnIndex("corpus_test") < 0) failures += "$path: the added column is missing"
                val removed = apply(added, reparsed.removeColumn(reparsed.columnIndex("corpus_test")))
                if (removed != text) failures += "$path: adding and removing a column changed the file"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /** A quoted cell as the editor would write it back: some files quote differently, and that is not a failure. */
    private fun canonical(text: String, cell: io.github.lemyst.enfusion.stringtable.format.CsvCell): Boolean =
        text.substring(cell.start, cell.end) == StringTableCsv.quote(cell.value)

    private fun apply(text: String, edits: List<TextEdit>): String {
        val out = StringBuilder(text)
        for (edit in edits.sortedByDescending { it.start }) out.replace(edit.start, edit.end, edit.replacement)
        return out.toString()
    }

    private fun stringTablesUnder(root: Path): List<Path> =
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().equals("stringtable.csv", ignoreCase = true) }
                .toList()
        }

    private fun log(message: String) = println("[stringtable] $message")

    private companion object {
        /** Where DayZ Tools extracts the vanilla stringtable. */
        val VANILLA: Path = Paths.get("P:/languagecore")
    }
}
