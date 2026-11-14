package io.github.lemyst.enfusion.stringtable.format

/**
 * A `stringtable.csv` read as CSV, keeping where every cell sits in the text so that an edit can
 * replace one cell and leave the rest of the file byte for byte as it was.
 *
 * The format, as the vanilla `languagecore/stringtable.csv` has it: a header row
 * `"Language","original","english",...`, then one row per key. Cells are quoted, a quote inside a
 * cell is doubled, and a cell may hold line breaks. Every row ends with a comma, so each one has a
 * trailing empty cell with no column name; it is kept as it is and never shown.
 *
 * This package uses no IntelliJ API, like `texture/format`, so that it can be tested on strings.
 * Offsets are into the text it was parsed from: the editor document, whose line breaks are `\n`.
 */
class StringTableCsv private constructor(
    val text: String,
    val rows: List<CsvRow>,
    /** Problems found while reading, in text order. */
    val syntaxProblems: List<CsvProblem>,
) {
    /** The header row, or null when the file is empty. */
    val header: CsvRow? get() = rows.firstOrNull()

    /** The rows that declare keys: every row after the header but blank ones. */
    val dataRows: List<CsvRow> get() = rows.drop(1).filterNot { it.isBlank }

    /**
     * The named columns, in file order. A trailing unnamed column (the comma that ends every row)
     * is not one of them.
     */
    val columns: List<String>
        get() {
            val names = header?.cells?.map { it.value } ?: return emptyList()
            return names.dropLastWhile { it.isEmpty() }
        }

    /** The index of [name] among [columns], ignoring case, or -1. */
    fun columnIndex(name: String): Int = columns.indexOfFirst { it.equals(name, ignoreCase = true) }

    /** The data row declaring [key], or null. */
    fun rowOf(key: String): CsvRow? = dataRows.firstOrNull { it.key == key }

    /** Structural problems: syntax, cell counts that differ from the header, duplicate keys. */
    fun problems(): List<CsvProblem> {
        val result = ArrayList(syntaxProblems)
        val expected = header?.cells?.size ?: return result
        val seen = HashMap<String, CsvRow>()
        for (row in dataRows) {
            if (row.cells.size != expected) {
                result += CsvProblem(
                    row.start, row.end,
                    "Row has ${row.cells.size} cells, the header has $expected: translations after the difference are in the wrong language",
                )
            }
            val key = row.key
            if (key.isNotEmpty()) {
                val first = seen.putIfAbsent(key, row)
                if (first != null) {
                    val cell = row.cells[0]
                    result += CsvProblem(cell.start, cell.end, "Duplicate key '$key', first declared on line ${first.line + 1}")
                }
            }
        }
        return result.sortedBy { it.start }
    }

    // ---- Edits ------------------------------------------------------------------------------

    /**
     * The edit that sets the cell of [row] in [column] to [value]. A row too short to have that
     * cell is padded with empty cells first.
     */
    fun setCell(row: CsvRow, column: Int, value: String): TextEdit {
        val cell = row.cells.getOrNull(column)
        if (cell != null) {
            val replacement = if (value.isEmpty() && !cell.quoted) "" else quote(value)
            return TextEdit(cell.start, cell.end, replacement)
        }
        val missing = column - row.cells.size
        val padding = ",".repeat(missing)
        return TextEdit(row.end, row.end, "$padding,${quote(value)}")
    }

    /**
     * The edits that set a whole row's cells: [values] by column index, empty for the others. A new
     * row is appended after the last one, shaped like the header, trailing comma included.
     */
    fun appendRow(key: String, values: Map<Int, String>): TextEdit {
        val cellCount = header?.cells?.size ?: (columns.size)
        val named = columns.size
        val cells = (0 until cellCount).map { column ->
            when {
                column == 0 -> quote(key)
                column < named -> quote(values[column] ?: "")
                else -> ""
            }
        }
        val line = cells.joinToString(",")
        val last = rows.lastOrNull()
        return when {
            last == null -> TextEdit(0, 0, line + "\n")
            text.length > last.end -> {
                // The file ends with a line break, as it should: the new row goes after it.
                val at = if (last.isBlank) last.start else text.length
                val needsBreak = at > 0 && text[at - 1] != '\n'
                TextEdit(at, at, (if (needsBreak) "\n" else "") + line + "\n")
            }

            else -> TextEdit(text.length, text.length, "\n" + line)
        }
    }

    /**
     * The edits that add a column named [name] after the last named one, filling each data row's
     * new cell with [fill] (given the row). Returned last first, so that applying them in order
     * keeps every offset valid.
     */
    fun addColumn(name: String, fill: (CsvRow) -> String): List<TextEdit> {
        val header = header ?: return emptyList()
        val at = columns.size
        val edits = ArrayList<TextEdit>()
        edits += insertCell(header, at, quote(name))
        for (row in dataRows) edits += insertCell(row, at, quote(fill(row)))
        return edits.sortedByDescending { it.start }
    }

    /** The edits that remove the column at [column] from every row. Last first, as for [addColumn]. */
    fun removeColumn(column: Int): List<TextEdit> {
        require(column > 0) { "the key column cannot be removed" }
        val edits = ArrayList<TextEdit>()
        for (row in rows) {
            if (row.isBlank || column >= row.cells.size) continue
            val cell = row.cells[column]
            // Take the comma before the cell with it; the key column always precedes it.
            edits += TextEdit(row.cells[column - 1].end, cell.end, "")
        }
        return edits.sortedByDescending { it.start }
    }

    /** The edits that copy `original` into every empty cell of [column]. Last first. */
    fun fillFromOriginal(column: Int): List<TextEdit> {
        val original = columnIndex(ORIGINAL)
        if (original < 0 || column == original || column == 0) return emptyList()
        return dataRows
            .filter { it.value(column).isEmpty() && it.value(original).isNotEmpty() }
            .map { setCell(it, column, it.value(original)) }
            .sortedByDescending { it.start }
    }

    /**
     * What changing the `original` cell of [row] to [newOriginal] does to the row's other cells.
     * A cell still holding the old `original` was a copy, not a translation, and follows it; a
     * cell holding anything else is a translation that may now be stale.
     */
    fun originalChange(row: CsvRow, newOriginal: String): OriginalChange {
        val original = columnIndex(ORIGINAL)
        require(original > 0) { "no original column" }
        val old = row.value(original)
        val edits = ArrayList<TextEdit>()
        edits += setCell(row, original, newOriginal)
        var copies = 0
        var stale = 0
        if (old != newOriginal) {
            for (column in columns.indices) {
                if (column == 0 || column == original) continue
                val value = row.value(column)
                when {
                    value.isEmpty() -> Unit
                    value == old -> {
                        edits += setCell(row, column, newOriginal)
                        copies++
                    }

                    else -> stale++
                }
            }
        }
        return OriginalChange(edits.sortedByDescending { it.start }, copies, stale)
    }

    /**
     * Whether the cell of [row] in [column] still needs translating: empty, or a copy of
     * `original`. `english` is never a copy, since it normally equals `original`.
     */
    fun translationState(row: CsvRow, column: Int): CellState {
        if (column <= 0) return CellState.KEY
        val name = columns.getOrNull(column) ?: return CellState.TRANSLATED
        if (name.equals(ORIGINAL, ignoreCase = true)) return CellState.TRANSLATED
        val value = row.value(column)
        if (value.isEmpty()) return CellState.EMPTY
        if (name.equals(ENGLISH, ignoreCase = true)) return CellState.TRANSLATED
        val original = columnIndex(ORIGINAL)
        if (original > 0 && value == row.value(original)) return CellState.COPY_OF_ORIGINAL
        return CellState.TRANSLATED
    }

    /** How many data rows of [column] are translated, out of how many. */
    fun coverage(column: Int): Pair<Int, Int> {
        val rows = dataRows
        return rows.count { translationState(it, column) == CellState.TRANSLATED } to rows.size
    }

    private fun insertCell(row: CsvRow, at: Int, quoted: String): TextEdit {
        return if (at < row.cells.size) {
            val cell = row.cells[at]
            TextEdit(cell.start, cell.start, "$quoted,")
        } else {
            TextEdit(row.end, row.end, ",".repeat(at - row.cells.size) + ",$quoted")
        }
    }

    companion object {
        const val ORIGINAL = "original"
        const val ENGLISH = "english"
        const val KEY_HEADER = "Language"

        /** The column set of the vanilla stringtable, for a new file when no stringtable is known. */
        val DEFAULT_COLUMNS = listOf(
            "Language", "original", "english", "czech", "german", "russian", "polish", "hungarian",
            "italian", "spanish", "french", "chinese", "japanese", "portuguese", "chinesesimp",
        )

        /** A header line for [columns], with the trailing comma every vanilla row has. */
        fun headerLine(columns: List<String>): String = columns.joinToString(",") { quote(it) } + ","

        fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

        fun parse(text: String): StringTableCsv {
            val rows = ArrayList<CsvRow>()
            val problems = ArrayList<CsvProblem>()
            var at = 0
            var line = 0
            val n = text.length
            while (at < n) {
                val rowStart = at
                val rowLine = line
                val cells = ArrayList<CsvCell>()
                while (true) {
                    val cellStart = at
                    if (at < n && text[at] == '"') {
                        val value = StringBuilder()
                        at++
                        var closed = false
                        while (at < n) {
                            val c = text[at]
                            if (c == '"') {
                                if (at + 1 < n && text[at + 1] == '"') {
                                    value.append('"')
                                    at += 2
                                    continue
                                }
                                at++
                                closed = true
                                break
                            }
                            if (c == '\n') line++
                            value.append(c)
                            at++
                        }
                        if (!closed) {
                            problems += CsvProblem(cellStart, n, "Unclosed quote: the rest of the file is one cell")
                        } else {
                            // Text after a closing quote belongs to no cell; keep it, but say so.
                            val junkStart = at
                            while (at < n && text[at] != ',' && text[at] != '\n' && text[at] != '\r') at++
                            if (at > junkStart) {
                                problems += CsvProblem(junkStart, at, "Text after the closing quote of a cell")
                                value.append(text, junkStart, at)
                            }
                        }
                        cells += CsvCell(cellStart, at, value.toString(), quoted = true)
                    } else {
                        while (at < n && text[at] != ',' && text[at] != '\n' && text[at] != '\r') at++
                        val raw = text.substring(cellStart, at)
                        if (raw.contains('"')) problems += CsvProblem(cellStart, at, "Quote inside an unquoted cell")
                        cells += CsvCell(cellStart, at, raw, quoted = false)
                    }
                    if (at < n && text[at] == ',') {
                        at++
                        continue
                    }
                    break
                }
                val rowEnd = at
                // \r\n, \n, or a lone \r: one line break ends the row.
                if (at < n && text[at] == '\r') at++
                if (at < n && text[at] == '\n') at++
                line++
                rows += CsvRow(rowStart, rowEnd, rowLine, cells)
            }
            return StringTableCsv(text, rows, problems)
        }
    }
}

/** One cell: [start] to [end] covers its quotes; [value] is its text with the quoting undone. */
class CsvCell(val start: Int, val end: Int, val value: String, val quoted: Boolean)

/** One row: [start] to [end] excludes its line break; [line] is where it starts, from 0. */
class CsvRow(val start: Int, val end: Int, val line: Int, val cells: List<CsvCell>) {
    val key: String get() = cells.firstOrNull()?.value ?: ""
    val isBlank: Boolean get() = cells.size <= 1 && key.isBlank()
    fun value(column: Int): String = cells.getOrNull(column)?.value ?: ""
}

class CsvProblem(val start: Int, val end: Int, val message: String)

/** Replace [start] to [end] with [replacement]. */
data class TextEdit(val start: Int, val end: Int, val replacement: String)

/** The edits for an `original` change, how many copies followed it, and how many translations may be stale. */
class OriginalChange(val edits: List<TextEdit>, val copies: Int, val staleTranslations: Int)

enum class CellState { KEY, TRANSLATED, EMPTY, COPY_OF_ORIGINAL }

/** Rules for new keys. */
object StringTableKeys {

    private val VALID = Regex("[A-Za-z0-9_]+")

    /** Why [key] cannot be a new key, or null when it can. */
    fun problem(key: String): String? = when {
        key.isEmpty() -> "Enter a key"
        !VALID.matches(key) -> "Letters, digits and underscores only"
        !key.startsWith("STR_") -> "Script and layouts reach a key as \"#STR_…\": start it with STR_"
        else -> null
    }

    /**
     * The prefix to start a new key with: the one most keys share, up to an underscore
     * (`STR_MYMOD_` for `STR_MYMOD_TITLE`, `STR_MYMOD_HINT`), or `STR_` when there is none.
     */
    fun commonPrefix(keys: List<String>): String {
        val counts = HashMap<String, Int>()
        for (key in keys) {
            if (!key.startsWith("STR_")) continue
            val second = key.indexOf('_', startIndex = 4)
            if (second > 4) counts.merge(key.substring(0, second + 1), 1, Int::plus)
        }
        val best = counts.maxByOrNull { it.value } ?: return "STR_"
        return if (best.value >= 2) best.key else "STR_"
    }
}
