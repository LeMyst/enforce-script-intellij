package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.stringtable.format.CellState
import io.github.lemyst.enfusion.stringtable.format.StringTableCsv
import io.github.lemyst.enfusion.stringtable.format.StringTableKeys
import io.github.lemyst.enfusion.stringtable.format.TextEdit
import junit.framework.TestCase

/** The `stringtable.csv` model, on hand-written tables shaped like the vanilla one. */
class EnfusionStringTableCsvTest : TestCase() {

    private val table = """
        "Language","original","english","french","german",
        "STR_MOD_HELLO","Hello","Hello","Bonjour","Hello",
        "STR_MOD_QUOTE","Say ""hi"", then go; now","Say ""hi"", then go; now","","",
        "STR_MOD_MULTI","Line one
        line two","Line one
        line two","Ligne un
        ligne deux",,

    """.trimIndent()

    private fun apply(text: String, edits: List<TextEdit>): String {
        val out = StringBuilder(text)
        for (edit in edits.sortedByDescending { it.start }) out.replace(edit.start, edit.end, edit.replacement)
        return out.toString()
    }

    private fun apply(text: String, edit: TextEdit) = apply(text, listOf(edit))

    fun testParsesQuotesCommasAndLineBreaks() {
        val csv = StringTableCsv.parse(table)
        assertEquals(listOf("Language", "original", "english", "french", "german"), csv.columns)
        assertEquals(listOf("STR_MOD_HELLO", "STR_MOD_QUOTE", "STR_MOD_MULTI"), csv.dataRows.map { it.key })
        val quote = csv.rowOf("STR_MOD_QUOTE")!!
        assertEquals("Say \"hi\", then go; now", quote.value(1))
        assertEquals("", quote.value(3))
        val multi = csv.rowOf("STR_MOD_MULTI")!!
        assertEquals("Line one\nline two", multi.value(1))
        assertEquals("Ligne un\nligne deux", multi.value(3))
        assertEquals(3, multi.line)
        // Every row ends with a comma: one more cell than there are named columns.
        assertEquals(6, multi.cells.size)
        assertTrue(csv.problems().isEmpty())
    }

    fun testParsesCrlf() {
        val csv = StringTableCsv.parse(table.replace("\n", "\r\n"))
        assertEquals(3, csv.dataRows.size)
        assertEquals("Line one\r\nline two", csv.rowOf("STR_MOD_MULTI")!!.value(1))
        assertTrue(csv.problems().isEmpty())
    }

    fun testSetCellChangesOnlyThatCell() {
        val csv = StringTableCsv.parse(table)
        val row = csv.rowOf("STR_MOD_QUOTE")!!
        val edited = apply(table, csv.setCell(row, 3, "Dis \"salut\", puis pars"))
        val diff = table.lines().zip(edited.lines()).filter { it.first != it.second }
        assertEquals(1, diff.size)
        assertEquals("\"STR_MOD_QUOTE\",\"Say \"\"hi\"\", then go; now\",\"Say \"\"hi\"\", then go; now\",\"Dis \"\"salut\"\", puis pars\",\"\",", diff[0].second)
        assertEquals("Dis \"salut\", puis pars", StringTableCsv.parse(edited).rowOf("STR_MOD_QUOTE")!!.value(3))
    }

    fun testSetCellKeepsAnEmptyUnquotedCellUnquoted() {
        val csv = StringTableCsv.parse(table)
        val row = csv.rowOf("STR_MOD_MULTI")!!
        assertEquals("", apply(table, csv.setCell(row, 4, "")).let { StringTableCsv.parse(it).rowOf("STR_MOD_MULTI")!!.value(4) })
        assertEquals(table, apply(table, csv.setCell(row, 4, "")))
        val filled = apply(table, csv.setCell(row, 4, "Zeile"))
        assertTrue(filled, filled.contains("ligne deux\",\"Zeile\","))
    }

    fun testSetCellPadsAShortRow() {
        val text = "\"Language\",\"original\",\"english\",\"french\"\n\"STR_A\",\"A\"\n"
        val csv = StringTableCsv.parse(text)
        val edited = apply(text, csv.setCell(csv.rowOf("STR_A")!!, 3, "Ah"))
        assertEquals("\"STR_A\",\"A\",,\"Ah\"", edited.lines()[1])
    }

    fun testRoundTripIsExact() {
        for (text in listOf(table, table.replace("\n", "\r\n"), table.trimEnd())) {
            val csv = StringTableCsv.parse(text)
            var edited = text
            // Writing every cell back with its own value changes nothing but the quoting of
            // quoted cells, which is already canonical here.
            for (row in csv.dataRows.reversed()) {
                for (column in row.cells.indices.reversed()) {
                    if (row.cells[column].quoted) edited = apply(edited, csv.setCell(row, column, row.value(column)))
                }
            }
            assertEquals(text, edited)
        }
    }

    fun testAppendRowLooksLikeTheHeader() {
        val csv = StringTableCsv.parse(table)
        val edited = apply(table, csv.appendRow("STR_MOD_NEW", mapOf(1 to "New", 3 to "Nouveau")))
        assertTrue(edited.endsWith("\"STR_MOD_NEW\",\"New\",\"\",\"Nouveau\",\"\",\n"))
        val after = StringTableCsv.parse(edited)
        assertEquals("Nouveau", after.rowOf("STR_MOD_NEW")!!.value(3))
        assertTrue(after.problems().isEmpty())
    }

    fun testAppendRowWithoutFinalLineBreak() {
        val text = table.trimEnd()
        val edited = apply(text, StringTableCsv.parse(text).appendRow("STR_MOD_NEW", emptyMap()))
        assertTrue(edited.contains("ligne deux\",,\n\"STR_MOD_NEW\""))
        assertEquals(4, StringTableCsv.parse(edited).dataRows.size)
    }

    fun testAddAndRemoveColumn() {
        val csv = StringTableCsv.parse(table)
        val added = apply(table, csv.addColumn("spanish") { it.value(1) })
        val after = StringTableCsv.parse(added)
        assertEquals(listOf("Language", "original", "english", "french", "german", "spanish"), after.columns)
        assertEquals("Hello", after.rowOf("STR_MOD_HELLO")!!.value(5))
        assertEquals("Line one\nline two", after.rowOf("STR_MOD_MULTI")!!.value(5))
        assertTrue(after.problems().isEmpty())
        // The trailing comma stays the last thing on each row.
        assertTrue(added.lines()[0].endsWith("\"german\",\"spanish\","))

        val removed = apply(added, after.removeColumn(after.columnIndex("spanish")))
        assertEquals(table, removed)
    }

    fun testFillFromOriginalFillsOnlyEmptyCells() {
        val csv = StringTableCsv.parse(table)
        val french = csv.columnIndex("french")
        val filled = StringTableCsv.parse(apply(table, csv.fillFromOriginal(french)))
        assertEquals("Bonjour", filled.rowOf("STR_MOD_HELLO")!!.value(french))
        assertEquals("Say \"hi\", then go; now", filled.rowOf("STR_MOD_QUOTE")!!.value(french))
        assertEquals("Ligne un\nligne deux", filled.rowOf("STR_MOD_MULTI")!!.value(french))
    }

    fun testChangingOriginalUpdatesCopiesNotTranslations() {
        val csv = StringTableCsv.parse(table)
        val change = csv.originalChange(csv.rowOf("STR_MOD_HELLO")!!, "Hi")
        assertEquals(2, change.copies) // english and german held "Hello"
        assertEquals(1, change.staleTranslations) // french holds a real translation
        val row = StringTableCsv.parse(apply(table, change.edits)).rowOf("STR_MOD_HELLO")!!
        assertEquals(listOf("STR_MOD_HELLO", "Hi", "Hi", "Bonjour", "Hi", ""), row.cells.map { it.value })
    }

    fun testChangingOriginalToTheSameValueTouchesNothingElse() {
        val csv = StringTableCsv.parse(table)
        val change = csv.originalChange(csv.rowOf("STR_MOD_HELLO")!!, "Hello")
        assertEquals(0, change.copies)
        assertEquals(table, apply(table, change.edits))
    }

    fun testTranslationStates() {
        val csv = StringTableCsv.parse(table)
        val hello = csv.rowOf("STR_MOD_HELLO")!!
        assertEquals(CellState.TRANSLATED, csv.translationState(hello, csv.columnIndex("english")))
        assertEquals(CellState.TRANSLATED, csv.translationState(hello, csv.columnIndex("french")))
        assertEquals(CellState.COPY_OF_ORIGINAL, csv.translationState(hello, csv.columnIndex("german")))
        val quote = csv.rowOf("STR_MOD_QUOTE")!!
        assertEquals(CellState.EMPTY, csv.translationState(quote, csv.columnIndex("french")))
        assertEquals(0 to 3, csv.coverage(csv.columnIndex("german")))
        assertEquals(2 to 3, csv.coverage(csv.columnIndex("french")))
    }

    fun testProblems() {
        val text = """
            "Language","original","english",
            "STR_A","A","A",
            "STR_A","Again","Again",
            "STR_B","B","B","shifted",
            "STR_C","C" and more,"C",
            "STR_D","unclosed,
        """.trimIndent()
        val messages = StringTableCsv.parse(text).problems().map { it.message }
        assertTrue(messages.toString(), messages.any { it.startsWith("Duplicate key 'STR_A', first declared on line 2") })
        assertTrue(messages.toString(), messages.any { it.startsWith("Row has 5 cells, the header has 4") })
        assertTrue(messages.toString(), messages.any { it == "Text after the closing quote of a cell" })
        assertTrue(messages.toString(), messages.any { it.startsWith("Unclosed quote") })
    }

    fun testBlankLinesAreNotRows() {
        val csv = StringTableCsv.parse("\"Language\",\"original\",\n\n\"STR_A\",\"A\",\n\n")
        assertEquals(listOf("STR_A"), csv.dataRows.map { it.key })
        assertTrue(csv.problems().isEmpty())
    }

    fun testKeyRules() {
        assertNull(StringTableKeys.problem("STR_MOD_NEW"))
        assertNotNull(StringTableKeys.problem(""))
        assertNotNull(StringTableKeys.problem("STR MOD"))
        assertNotNull(StringTableKeys.problem("MOD_NEW"))
        assertEquals("STR_MOD_", StringTableKeys.commonPrefix(listOf("STR_MOD_A", "STR_MOD_B", "STR_OTHER_C")))
        assertEquals("STR_", StringTableKeys.commonPrefix(listOf("STR_MOD_A")))
        assertEquals("STR_", StringTableKeys.commonPrefix(emptyList()))
    }
}
