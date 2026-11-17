package io.github.lemyst.enfusion.tools.run

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import javax.swing.DefaultListModel
import javax.swing.JComponent

/**
 * A mod's release build: where the mod goes, what goes into `addons\` (the project's addons and
 * third-party PBOs), how it is signed, the keys for `keys\`, and the files copied beside them.
 */
class DayZModBuildEditor(private val project: Project) : SettingsEditor<DayZModBuildConfiguration>() {

    private val output = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Mod Folder"))
    }
    private val modTable = DayZModTable(project, { null }, forRelease = true) { fireEditorStateChanged() }
    private val signKey = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withExtensionFilter("biprivatekey").withTitle("Signing Key"))
    }
    private val modCpp = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("mod.cpp"))
    }

    private val keysModel = DefaultListModel<String>()
    private val keysList = JBList(keysModel)

    /** A file copied into the mod: its source, and where it goes in the mod's folder. */
    private class Extra(var source: String, var target: String)

    private val extrasModel = ListTableModel<Extra>(
        object : ColumnInfo<Extra, String>("File") {
            override fun valueOf(item: Extra): String = item.source
        },
        object : ColumnInfo<Extra, String>("In the mod as") {
            override fun valueOf(item: Extra): String = item.target
            override fun isCellEditable(item: Extra): Boolean = true
            override fun setValue(item: Extra, value: String) {
                item.target = value
                fireEditorStateChanged()
            }
        },
    )

    private fun keysComponent(): JComponent = ToolbarDecorator.createDecorator(keysList)
        .setAddAction {
            val descriptor = FileChooserDescriptorFactory.multiFiles().withExtensionFilter("bikey").withTitle("Third-Party Keys")
            for (file in FileChooser.chooseFiles(descriptor, project, null)) {
                val path = FileUtil.toSystemDependentName(file.path)
                if (!keysModel.contains(path)) keysModel.addElement(path)
            }
            fireEditorStateChanged()
        }
        .setRemoveAction {
            keysList.selectedIndices.sortedDescending().forEach { keysModel.remove(it) }
            fireEditorStateChanged()
        }
        .disableUpDownActions()
        .createPanel()

    /**
     * An edit in progress is committed when the table loses focus: pressing *Run* or *OK* while a
     * cell is still being edited would otherwise apply the value from before the edit.
     */
    private val extrasTable = TableView(extrasModel).apply {
        visibleRowCount = 4
        putClientProperty("terminateEditOnFocusLost", true)
    }

    private fun extrasComponent(): JComponent = ToolbarDecorator.createDecorator(extrasTable)
        .setAddAction {
            val descriptor = FileChooserDescriptorFactory.multiFiles().withTitle("Files to Copy into the Mod")
            for (file in FileChooser.chooseFiles(descriptor, project, null)) {
                extrasModel.addRow(Extra(FileUtil.toSystemDependentName(file.path), file.name))
            }
            fireEditorStateChanged()
        }
        .setRemoveAction {
            extrasTable.selectedRows.sortedDescending().forEach { extrasModel.removeRow(it) }
            fireEditorStateChanged()
        }
        .disableUpDownActions()
        .createPanel()

    private val lowerCase = JBCheckBox("Lower-case PBO names (scripts_client.pbo)").apply {
        addItemListener { fireEditorStateChanged() }
    }

    override fun createEditor(): JComponent = panel {
        row("Mod folder:") {
            cell(output).align(AlignX.FILL).comment("The @MyMod folder to build, such as P:\\Mods\\@MyMod. Its addons and keys folders are managed by the build.")
        }
        row {
            cell(modTable.component).align(Align.FILL)
                .comment(
                    "What goes into addons\\: the project's addons, built by Addon Builder (binarised) and signed, " +
                        "only those that changed since the last build; and third-party PBOs, copied with their signatures.",
                )
        }.resizableRow()
        row {
            cell(lowerCase).comment("The project's PBOs and their signatures only; third-party PBOs keep their names.")
        }
        row("Signing key:") {
            cell(signKey).align(AlignX.FILL)
                .comment("A .biprivatekey; the .bikey of the same name next to it goes into keys\\. Each PBO is signed with DSSignFile.")
        }
        row("Third-party keys:") {
            cell(keysComponent()).align(AlignX.FILL).comment("The .bikey files of the third-party PBOs, copied into keys\\.")
        }
        row("mod.cpp:") {
            cell(modCpp).align(AlignX.FILL).comment("Copied into the mod's folder.")
        }
        row("Extra files:") {
            cell(extrasComponent()).align(AlignX.FILL).comment("Copied into the mod's folder, such as LICENSE or README.md.")
        }
    }

    override fun resetEditorFrom(configuration: DayZModBuildConfiguration) {
        val o = configuration.options
        output.text = o.output.orEmpty()
        modTable.mods = o.modList.mapNotNull { it.toMod() }
        signKey.text = o.signKey.orEmpty()
        modCpp.text = o.modCpp ?: defaultModCpp().orEmpty()
        keysModel.clear()
        o.keys.forEach { keysModel.addElement(it) }
        extrasModel.items = o.extras.mapTo(ArrayList()) { Extra(it.source.orEmpty(), it.target.orEmpty()) }
        lowerCase.isSelected = o.lowerCaseNames
    }

    override fun applyEditorTo(configuration: DayZModBuildConfiguration) {
        if (extrasTable.isEditing) extrasTable.cellEditor?.stopCellEditing()
        val o = configuration.options
        o.output = output.text.trim().ifEmpty { null }
        o.modList.clear()
        o.modList.addAll(modTable.mods.map { DayZModState.of(it) })
        o.signKey = signKey.text.trim().ifEmpty { null }
        o.modCpp = modCpp.text.trim().ifEmpty { null }
        o.keys.clear()
        o.keys.addAll((0 until keysModel.size()).map { keysModel.get(it) })
        o.extras.clear()
        o.extras.addAll(
            extrasModel.items.filter { it.source.isNotBlank() }.map { e ->
                DayZExtraState().also {
                    it.source = e.source
                    it.target = e.target.trim().ifEmpty { null }
                }
            },
        )
        o.lowerCaseNames = lowerCase.isSelected
    }

    /** A new configuration starts with the `mod.cpp` at the project's root, when there is one. */
    private fun defaultModCpp(): String? =
        ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { it.findChild("mod.cpp") }
            ?.let { FileUtil.toSystemDependentName(it.path) }
}
