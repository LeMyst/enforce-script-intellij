package io.github.lemyst.enfusion

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import io.github.lemyst.enfusion.inspections.EnUnresolvedStringTableKeyInspection
import io.github.lemyst.enfusion.stringtable.CreateStringTableRowFix
import io.github.lemyst.enfusion.stringtable.EnStringTableStructureInspection
import io.github.lemyst.enfusion.stringtable.StringTableEditor
import io.github.lemyst.enfusion.stringtable.StringTableEditorProvider
import io.github.lemyst.enfusion.stringtable.format.StringTableCsv

/** The stringtable table editor, its structure inspection and the create-row quick fix. */
class EnfusionStringTableEditorTest : BasePlatformTestCase() {

    private val table = """
        "Language","original","english","french",
        "STR_MOD_HELLO","Hello","Hello","Bonjour",
        "STR_MOD_BYE","Bye","Bye","Bye",

    """.trimIndent()

    fun testStructureProblemsAreReported() {
        myFixture.enableInspections(EnStringTableStructureInspection())
        myFixture.configureByText(
            "stringtable.csv",
            "\"Language\",\"original\",\"english\",\n\"STR_A\",\"A\",\"A\",\"extra\",\n\"STR_A\",\"B\",\"B\",\n",
        )
        val reports = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }.map { it.description }
        assertTrue(reports.toString(), reports.any { it.startsWith("Row has 5 cells") })
        assertTrue(reports.toString(), reports.any { it.startsWith("Duplicate key 'STR_A'") })
    }

    fun testWellFormedStringTableIsQuiet() {
        myFixture.enableInspections(EnStringTableStructureInspection())
        myFixture.configureByText("stringtable.csv", table)
        assertEmpty(myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING })
    }

    fun testOtherCsvFilesAreIgnored() {
        myFixture.enableInspections(EnStringTableStructureInspection())
        myFixture.configureByText("data.csv", "a,\"b\nc,d\n")
        assertEmpty(myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING })
    }

    fun testQuickFixAddsTheRowToTheModStringTable() {
        myFixture.enableInspections(EnUnresolvedStringTableKeyInspection())
        myFixture.addFileToProject("MyMod/stringtable.csv", table)
        myFixture.configureByText("hud.es", "class Hud { void Show() { SetText(\"#STR_MOD_NE<caret>W\"); } }")
        myFixture.launchAction(myFixture.findSingleIntention("Create stringtable row for 'STR_MOD_NEW'"))
        val text = csvText("MyMod/stringtable.csv")
        assertTrue(text, text.endsWith("\"STR_MOD_NEW\",\"\",\"\",\"\",\n"))
        assertTrue(StringTableCsv.parse(text).problems().isEmpty())
    }

    /** No stringtable in the project yet: one is created at the addon root, the folder with config.cpp. */
    fun testQuickFixCreatesTheStringTable() {
        myFixture.addFileToProject("MyMod/config.cpp", "class CfgPatches {};\n")
        val script = myFixture.addFileToProject("MyMod/Scripts/hud.es", "class Hud { string m = \"#STR_MOD_NEW\"; }")
        val literal = script.findElementAt(script.text.indexOf("STR_MOD_NEW"))!!
        val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(
            literal, "missing", CreateStringTableRowFix("STR_MOD_NEW"), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, true,
        )
        CreateStringTableRowFix("STR_MOD_NEW").applyFix(project, descriptor)
        val text = csvText("MyMod/stringtable.csv")
        val csv = StringTableCsv.parse(text)
        assertEquals(StringTableCsv.DEFAULT_COLUMNS, csv.columns)
        assertEquals(listOf("STR_MOD_NEW"), csv.dataRows.map { it.key })
        assertTrue(csv.problems().isEmpty())
    }

    fun testProviderAcceptsOnlyStringTables() {
        val provider = StringTableEditorProvider()
        assertTrue(provider.accept(project, myFixture.addFileToProject("a/stringtable.csv", table).virtualFile))
        assertFalse(provider.accept(project, myFixture.addFileToProject("a/other.csv", table).virtualFile))
    }

    fun testEditingACellEditsTheDocumentAndUndoes() {
        val file = myFixture.addFileToProject("MyMod/stringtable.csv", table).virtualFile
        val editor = openTable(file)
        try {
            val tables = UIUtil.findComponentsOfType(editor.component, JBTable::class.java)
            val model = tables.first().model
            assertEquals(2, model.rowCount)
            assertEquals("Bonjour", model.getValueAt(0, 3))
            model.setValueAt("Salut", 0, 3)
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            assertEquals(table.replace("\"Bonjour\"", "\"Salut\""), document.text)

            val textEditor = FileEditorManager.getInstance(project).openFile(file, true).filterIsInstance<TextEditor>().first()
            UndoManager.getInstance(project).undo(textEditor)
            assertEquals(table, document.text)
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testEditingOriginalUpdatesItsCopies() {
        val file = myFixture.addFileToProject("MyMod/stringtable.csv", table).virtualFile
        val editor = openTable(file)
        try {
            val tables = UIUtil.findComponentsOfType(editor.component, JBTable::class.java)
            val model = tables.first().model
            // STR_MOD_BYE: english and french still hold "Bye", copies of original.
            model.setValueAt("Goodbye", 1, 1)
            val row = StringTableCsv.parse(FileDocumentManager.getInstance().getDocument(file)!!.text).rowOf("STR_MOD_BYE")!!
            assertEquals(listOf("STR_MOD_BYE", "Goodbye", "Goodbye", "Goodbye", ""), row.cells.map { it.value })
            // STR_MOD_HELLO: french is a real translation and stays.
            model.setValueAt("Hi", 0, 1)
            val hello = StringTableCsv.parse(FileDocumentManager.getInstance().getDocument(file)!!.text).rowOf("STR_MOD_HELLO")!!
            assertEquals(listOf("STR_MOD_HELLO", "Hi", "Hi", "Bonjour", ""), hello.cells.map { it.value })
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testShowOnlyRowsMissingALanguage() {
        val file = myFixture.addFileToProject("MyMod/stringtable.csv", table).virtualFile
        val editor = openTable(file)
        try {
            val model = UIUtil.findComponentsOfType(editor.component, JBTable::class.java).first().model
            @Suppress("UNCHECKED_CAST")
            val show = UIUtil.findComponentsOfType(editor.component, javax.swing.JComboBox::class.java).single() as javax.swing.JComboBox<String>
            assertEquals(listOf("All rows", "Missing in english", "Missing in french"), (0 until show.itemCount).map { show.getItemAt(it) })
            show.selectedItem = "Missing in french"
            // STR_MOD_HELLO has "Bonjour"; STR_MOD_BYE's french is still a copy of original.
            assertEquals(1, model.rowCount)
            assertEquals("STR_MOD_BYE", model.getValueAt(0, 0))
            show.selectedItem = "Missing in english"
            assertEquals(0, model.rowCount)
            show.selectedIndex = 0
            assertEquals(2, model.rowCount)
        } finally {
            Disposer.dispose(editor)
        }
    }

    /** A repository of several addons, each with its own `config.cpp`, as mods split into PBOs are. */
    private fun addons() {
        myFixture.addFileToProject("Repo/GUI/config.cpp", "class CfgPatches {};\n")
        myFixture.addFileToProject("Repo/Map/config.cpp", "class CfgPatches {};\n")
        myFixture.addFileToProject("Repo/Map/stringtable.csv", table.replace("STR_MOD_", "STR_MAP_"))
        myFixture.addFileToProject("Repo/AutoRun/config.cpp", "class CfgPatches {};\n")
        myFixture.addFileToProject("Repo/AutoRun/stringtable.csv", table.replace("STR_MOD_", "STR_AUTORUN_"))
    }

    private fun targets(key: String, from: String): List<String> =
        CreateStringTableRowFix(key).targets(project, myFixture.findFileInTempDir(from)!!).map { it.label }

    /**
     * GUI has no stringtable, so every table is offered and the user picks: the key prefix only
     * ranks them, since mods keep strings in a shared language addon as often as per addon.
     */
    fun testQuickFixRanksTablesByKeyPrefix() {
        addons()
        myFixture.addFileToProject("Repo/GUI/layouts/menu.es", "")
        assertEquals(
            listOf("Repo/AutoRun/stringtable.csv", "Repo/Map/stringtable.csv", "New stringtable.csv in Repo/GUI"),
            targets("STR_AUTORUN_NEW", "Repo/GUI/layouts/menu.es"),
        )
        assertEquals(
            listOf("Repo/Map/stringtable.csv", "Repo/AutoRun/stringtable.csv", "New stringtable.csv in Repo/GUI"),
            targets("str_map_new", "Repo/GUI/layouts/menu.es"),
        )
    }

    fun testQuickFixPrefersTheFilesOwnAddon() {
        addons()
        myFixture.addFileToProject("Repo/Map/Scripts/marker.es", "")
        assertEquals(listOf("Repo/Map/stringtable.csv"), targets("STR_AUTORUN_NEW", "Repo/Map/Scripts/marker.es"))
    }

    /** No telling prefix: the tables in path order, then a new one. */
    fun testQuickFixOffersEveryTableInPathOrderWithoutAPrefix() {
        addons()
        myFixture.addFileToProject("Repo/GUI/layouts/menu.es", "")
        assertEquals(
            listOf("Repo/AutoRun/stringtable.csv", "Repo/Map/stringtable.csv", "New stringtable.csv in Repo/GUI"),
            targets("STR_OTHER_NEW", "Repo/GUI/layouts/menu.es"),
        )
    }

    /** The editor once its background project-content check has landed, as it is a moment after opening. */
    private fun openTable(file: com.intellij.openapi.vfs.VirtualFile): StringTableEditor {
        val editor = StringTableEditor(project, file)
        com.intellij.openapi.application.impl.NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return editor
    }

    private fun csvText(path: String): String {
        val file = myFixture.findFileInTempDir(path) ?: error("$path was not created")
        return FileDocumentManager.getInstance().getDocument(file)!!.text
    }
}
