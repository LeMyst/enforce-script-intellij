package io.github.lemyst.enfusion.stringtable

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.lemyst.enfusion.stringtable.format.CellState
import io.github.lemyst.enfusion.stringtable.format.CsvRow
import io.github.lemyst.enfusion.stringtable.format.StringTableCsv
import io.github.lemyst.enfusion.stringtable.format.TextEdit
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableColumn

/**
 * The *Table* tab of a `stringtable.csv`: one row per key and one column per language.
 *
 * The table is a view of the editor document, never a copy. Every edit goes through the document
 * in a write command, so undo, the text tab and version control all see the same change, and an
 * edit in the text tab shows here the next time the table is looked at.
 *
 * The key and `original` columns stay on the left while the languages scroll: they are a second
 * table in the scroll pane's row header, sharing the model and the row selection.
 */
class StringTableEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val document: Document = FileDocumentManager.getInstance().getDocument(file)
        ?: error("stringtable.csv without a document")
    private var csv: StringTableCsv = StringTableCsv.parse(document.text)
    private var stale = false

    /** The first problem of each row, by the row's start offset: looked up for every key cell painted. */
    private var problemsByRow: Map<Int, String> = emptyMap()

    /** Keys whose `original` changed in this session while they held real translations. */
    private val changedOriginals = HashSet<String>()

    private val model = Model()
    private val fixed = JBTable(model)
    private val languages = JBTable(model)
    private val filter = SearchTextField(false)
    private val missingIn = JComboBox<String>()
    private val status = JBLabel()
    private val root = JPanel(BorderLayout())

    /**
     * Only the project's own stringtables are edited here. The vanilla one on `P:` is an ordinary
     * writable file, but it is a library, and an edit to it would change the game's extracted data.
     */
    private val writable: Boolean
        get() = file.isWritable && ProjectFileIndex.getInstance(project).isInContent(file)

    init {
        for (table in listOf(fixed, languages)) {
            table.autoCreateColumnsFromModel = false
            table.setDefaultRenderer(Any::class.java, Renderer())
            table.autoResizeMode = JTable.AUTO_RESIZE_OFF
            table.tableHeader.reorderingAllowed = false
            table.putClientProperty("terminateEditOnFocusLost", true)
        }
        languages.selectionModel = fixed.selectionModel
        fixed.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        languages.emptyText.text = "No rows match"

        val scroll = JBScrollPane(languages, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        scroll.setRowHeaderView(fixed)
        scroll.setCorner(ScrollPaneConstants.UPPER_LEFT_CORNER, fixed.tableHeader)

        filter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = model.refilter()
        })
        missingIn.addActionListener { model.refilter() }

        val actions = DefaultActionGroup(
            AddKeyAction(), AddLanguageAction(), RemoveLanguageAction(), FillFromOriginalAction(),
            com.intellij.openapi.actionSystem.Separator.getInstance(),
            UpdateCopiesToggle(), ShowInTextAction(),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("EnfusionStringTable", actions, true)
        toolbar.targetComponent = root

        val north = JPanel(BorderLayout(JBUI.scale(8), 0))
        north.border = JBUI.Borders.empty(2, 4)
        north.add(toolbar.component, BorderLayout.WEST)
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
        right.add(JBLabel("Show:"))
        right.add(missingIn)
        right.add(filter)
        north.add(right, BorderLayout.EAST)
        status.border = JBUI.Borders.empty(2, 8)

        root.add(north, BorderLayout.NORTH)
        root.add(scroll, BorderLayout.CENTER)
        root.add(status, BorderLayout.SOUTH)

        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                stale = true
                // While the table is on screen, an undo or a text-tab edit should show at once.
                if (root.isShowing) ApplicationManager.getApplication().invokeLater({ refresh() }, { project.isDisposed })
            }
        }, this)

        updateProblems()
        rebuild()
    }

    // ---- Model -------------------------------------------------------------------------------

    private inner class Model : AbstractTableModel() {
        var visible: List<CsvRow> = emptyList()

        fun refilter() {
            val text = filter.text.trim()
            // Entry 0 of the combo is "All rows"; entry n is the nth language column.
            val language = languageColumns().getOrNull(missingIn.selectedIndex - 1)
            visible = csv.dataRows.filter { row ->
                (text.isEmpty() || row.cells.any { it.value.contains(text, ignoreCase = true) }) &&
                    (language == null || csv.translationState(row, language).let { it == CellState.EMPTY || it == CellState.COPY_OF_ORIGINAL })
            }
            fireTableDataChanged()
            updateStatus()
        }

        override fun getRowCount(): Int = visible.size
        override fun getColumnCount(): Int = csv.columns.size
        override fun getColumnName(column: Int): String = csv.columns.getOrElse(column) { "" }
        override fun getValueAt(row: Int, column: Int): Any = visible.getOrNull(row)?.value(column) ?: ""
        override fun isCellEditable(row: Int, column: Int): Boolean = writable

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val target = visible.getOrNull(row) ?: return
            editCell(target, column, value?.toString() ?: "")
        }
    }

    /** Translation columns: everything after the key and `original`. */
    private fun languageColumns(): List<Int> {
        val original = csv.columnIndex(StringTableCsv.ORIGINAL)
        return csv.columns.indices.filter { it != 0 && it != original }
    }

    private fun refresh() {
        if (!stale) return
        val before = csv.columns
        csv = StringTableCsv.parse(document.text)
        stale = false
        updateProblems()
        if (csv.columns != before) rebuild() else {
            model.refilter()
            updateHeaders()
        }
    }

    /** Re-creates the columns, for a new file or after a language was added or removed. */
    private fun rebuild() {
        val selected = missingIn.selectedItem
        missingIn.removeAllItems()
        missingIn.addItem("All rows")
        for (column in languageColumns()) missingIn.addItem("Missing in ${csv.columns[column]}")
        if (selected != null) missingIn.selectedItem = selected

        for (table in listOf(fixed, languages)) {
            while (table.columnModel.columnCount > 0) table.columnModel.removeColumn(table.columnModel.getColumn(0))
        }
        val frozen = setOf(0, csv.columnIndex(StringTableCsv.ORIGINAL)).filter { it >= 0 }
        for (index in csv.columns.indices) {
            val column = TableColumn(index)
            column.preferredWidth = JBUI.scale(if (index == 0) 220 else 260)
            (if (index in frozen) fixed else languages).addColumn(column)
        }
        fixed.preferredScrollableViewportSize = Dimension(fixed.columnModel.totalColumnWidth, 0)
        model.fireTableStructureChanged()
        model.refilter()
        updateHeaders()
    }

    /** Puts each language's coverage in its header: `french 412/430`. */
    private fun updateHeaders() {
        for (table in listOf(fixed, languages)) {
            for (i in 0 until table.columnModel.columnCount) {
                val column = table.columnModel.getColumn(i)
                val index = column.modelIndex
                val name = csv.columns.getOrElse(index) { "" }
                column.headerValue = if (index in languageColumns()) {
                    val (done, total) = csv.coverage(index)
                    "$name $done/$total"
                } else {
                    name
                }
            }
            table.tableHeader.repaint()
        }
    }

    private fun updateProblems() {
        val byRow = HashMap<Int, String>()
        val rows = csv.rows
        var r = 0
        for (problem in csv.problems()) {
            while (r + 1 < rows.size && rows[r + 1].start <= problem.start) r++
            byRow.putIfAbsent(rows[r].start, problem.message)
        }
        problemsByRow = byRow
    }

    private fun updateStatus() {
        val problems = problemsByRow
        val shown = if (model.visible.size == csv.dataRows.size) "${csv.dataRows.size} keys" else "${model.visible.size} of ${csv.dataRows.size} keys"
        val parts = mutableListOf(shown, "${languageColumns().size} languages")
        if (problems.isNotEmpty()) parts += "${problems.size} ${plural(problems.size, "row", "rows")} with problems: see the text tab"
        if (!writable) parts += "read-only"
        status.text = parts.joinToString(" · ")
    }

    // ---- Edits -------------------------------------------------------------------------------

    private fun editCell(row: CsvRow, column: Int, value: String) {
        refresh()
        val current = csv.rows.firstOrNull { it.start == row.start } ?: return
        if (current.value(column) == value) return
        val original = csv.columnIndex(StringTableCsv.ORIGINAL)
        if (column == original && original > 0 && updateCopies) {
            val change = csv.originalChange(current, value)
            apply("Edit Stringtable Original", change.edits)
            if (change.staleTranslations > 0) changedOriginals += current.key
            if (change.copies > 0 || change.staleTranslations > 0) {
                val stale = if (change.staleTranslations > 0) "; ${change.staleTranslations} translations may be stale" else ""
                notify("Updated ${change.copies} ${plural(change.copies, "copy", "copies")} of original$stale.")
            }
        } else {
            apply("Edit Stringtable Cell", listOf(csv.setCell(current, column, value)))
        }
    }

    private fun apply(name: String, edits: List<TextEdit>) {
        if (edits.isEmpty()) return
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(file)).hasReadonlyFiles()) return
        WriteCommandAction.runWriteCommandAction(project, name, null, {
            for (edit in edits.sortedByDescending { it.start }) document.replaceString(edit.start, edit.end, edit.replacement)
        })
        refresh()
    }

    private fun notify(message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Enforce Script")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun selectedRows(): List<CsvRow> = languages.selectedRows.map { index -> model.visible[index] }

    private fun select(key: String) {
        val index = model.visible.indexOfFirst { it.key == key }
        if (index < 0) return
        languages.selectionModel.setSelectionInterval(index, index)
        languages.scrollRectToVisible(languages.getCellRect(index, 0, true))
        fixed.scrollRectToVisible(fixed.getCellRect(index, 0, true))
    }

    private var updateCopies: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(UPDATE_COPIES_KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(UPDATE_COPIES_KEY, value, true)

    // ---- Actions -----------------------------------------------------------------------------

    private abstract inner class EditAction(text: String, description: String, icon: javax.swing.Icon) :
        DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = writable && csv.header != null
        }
    }

    private inner class AddKeyAction : EditAction("Add Key…", "Add a row for a new key", com.intellij.icons.AllIcons.General.Add) {
        override fun actionPerformed(e: AnActionEvent) {
            refresh()
            val dialog = AddKeyDialog(project, csv)
            if (!dialog.showAndGet()) return
            val values = dialog.values()
            apply("Add Stringtable Key", listOf(csv.appendRow(dialog.key, values)))
            filter.text = ""
            missingIn.selectedIndex = 0
            select(dialog.key)
        }
    }

    private inner class AddLanguageAction : EditAction("Add Language…", "Add a language column to every row", com.intellij.icons.AllIcons.Actions.AddList) {
        override fun actionPerformed(e: AnActionEvent) {
            refresh()
            val answer = Messages.showInputDialogWithCheckBox(
                "Name of the new language column, as the game spells it (\"french\", \"chinesesimp\"):",
                "Add Language",
                "Fill with a copy of original",
                false,
                true,
                null,
                "",
                object : com.intellij.openapi.ui.InputValidator {
                    override fun checkInput(input: String?): Boolean {
                        val name = input?.trim() ?: return false
                        return name.isNotEmpty() && !name.contains('"') && !name.contains(',') && csv.columnIndex(name) < 0
                    }

                    override fun canClose(input: String?): Boolean = checkInput(input)
                },
            )
            val name = answer.first?.trim() ?: return
            val copy = answer.second
            val original = csv.columnIndex(StringTableCsv.ORIGINAL)
            apply("Add Stringtable Language", csv.addColumn(name) { if (copy && original > 0) it.value(original) else "" })
        }
    }

    private inner class RemoveLanguageAction : EditAction("Remove Language…", "Remove a language column from every row", com.intellij.icons.AllIcons.General.Remove) {
        override fun actionPerformed(e: AnActionEvent) {
            refresh()
            chooseLanguage("Remove Language") { column ->
                val name = csv.columns[column]
                val ok = Messages.showOkCancelDialog(
                    project,
                    "Remove the \"$name\" column and its ${csv.coverage(column).first} translations from every row?",
                    "Remove Language",
                    "Remove",
                    Messages.getCancelButton(),
                    Messages.getWarningIcon(),
                ) == Messages.OK
                if (ok) apply("Remove Stringtable Language", csv.removeColumn(column))
            }
        }
    }

    private inner class FillFromOriginalAction : EditAction("Fill from Original…", "Copy original into every empty cell of a language", com.intellij.icons.AllIcons.Actions.Copy) {
        override fun actionPerformed(e: AnActionEvent) {
            refresh()
            chooseLanguage("Fill from Original") { column ->
                val edits = csv.fillFromOriginal(column)
                if (edits.isEmpty()) {
                    notify("No empty ${csv.columns[column]} cells to fill.")
                } else {
                    apply("Fill Stringtable Language from Original", edits)
                    notify("Filled ${edits.size} empty ${csv.columns[column]} ${plural(edits.size, "cell", "cells")} with original.")
                }
            }
        }
    }

    private inner class UpdateCopiesToggle : DumbAwareToggleAction(
        "Update Copies of Original",
        "When original changes, update the cells of its row that still hold the old original",
        com.intellij.icons.AllIcons.Actions.SyncPanels,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = updateCopies
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            updateCopies = state
        }
    }

    private inner class ShowInTextAction : DumbAwareAction("Show in Text", "Open the selected row in the text tab", com.intellij.icons.AllIcons.Actions.EditSource) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = languages.selectedRow >= 0
        }

        override fun actionPerformed(e: AnActionEvent) {
            val row = selectedRows().firstOrNull() ?: return
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, row.start), true)
        }
    }

    private fun chooseLanguage(title: String, then: (Int) -> Unit) {
        val columns = languageColumns()
        if (columns.isEmpty()) return
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(columns.map { csv.columns[it] })
            .setTitle(title)
            .setItemChosenCallback { name -> csv.columnIndex(name).takeIf { it > 0 }?.let(then) }
            .createPopup()
            .showInCenterOf(root)
    }

    // ---- Rendering ---------------------------------------------------------------------------

    private inner class Renderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val text = value?.toString()?.replace("\n", " ⏎ ") ?: ""
            super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column)
            if (!isSelected) foreground = table.foreground
            val csvRow = model.visible.getOrNull(row)
            val modelColumn = table.columnModel.getColumn(column).modelIndex
            toolTipText = null
            if (!isSelected) background = table.background
            if (csvRow != null) {
                val state = csv.translationState(csvRow, modelColumn)
                when {
                    state == CellState.EMPTY -> {
                        if (!isSelected) background = EMPTY_BACKGROUND
                        toolTipText = "Empty: not translated"
                    }

                    state == CellState.COPY_OF_ORIGINAL -> {
                        if (!isSelected) background = COPY_BACKGROUND
                        toolTipText = "Same as original, probably not translated"
                    }

                    state == CellState.TRANSLATED && csvRow.key in changedOriginals && modelColumn in languageColumns() -> {
                        if (!isSelected) background = STALE_BACKGROUND
                        toolTipText = "Original changed since this was translated"
                    }
                }
                if (modelColumn == 0) {
                    val problem = problemsByRow[csvRow.start]
                    if (problem != null) {
                        foreground = JBColor.RED
                        toolTipText = problem
                    }
                }
            }
            return this
        }
    }

    // ---- FileEditor --------------------------------------------------------------------------

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = languages
    override fun getName(): String = "Table"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    /** Coming from the text tab, select the row the caret is on. */
    override fun selectNotify() {
        refresh()
        val caret = FileEditorManager.getInstance(project).getEditors(file)
            .filterIsInstance<TextEditor>().firstOrNull()?.editor?.caretModel?.offset ?: return
        val row = csv.dataRows.firstOrNull { caret >= it.start && caret <= it.end } ?: return
        if (model.visible.none { it.start == row.start }) {
            filter.text = ""
            missingIn.selectedIndex = 0
        }
        select(row.key)
    }

    override fun dispose() = Unit

    private companion object {
        const val UPDATE_COPIES_KEY = "enfusion.stringtable.updateCopies"
        val EMPTY_BACKGROUND = JBColor(0xFDE7E9, 0x5A3034)
        val COPY_BACKGROUND = JBColor(0xFFF4CE, 0x51492C)
        val STALE_BACKGROUND = JBColor(0xE6F0FF, 0x2E3B4F)

        fun plural(n: Int, one: String, many: String) = if (n == 1) one else many
    }
}
