package io.github.lemyst.enfusion.tools.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.lemyst.enfusion.tools.DayZInstallation
import io.github.lemyst.enfusion.tools.SteamCmdDownload
import io.github.lemyst.enfusion.tools.detect.WorkshopPublish
import javax.swing.JComponent
import javax.swing.JList

/** The upload: what, as which account, to which item, and what the item's page says about it. */
class DayZWorkshopPublishEditor(private val project: Project) : SettingsEditor<DayZWorkshopPublishConfiguration>() {

    private val contentFolder = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Built Mod Folder"))
    }
    /** Where SteamCMD is: a setting of the machine, shown here and downloaded from here. */
    private val steamCmd = JBLabel()
    private val account = JBTextField()
    private val itemId = JBTextField()
    private val title = JBTextField()
    private val itemDescription = JBTextArea(4, 60).apply { lineWrap = true; wrapStyleWord = true }
    private val previewFile = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withExtensionFilter("Images", "png", "jpg", "jpeg", "gif").withTitle("Preview Image"))
    }
    private val changeNote = JBTextArea(5, 60).apply { lineWrap = true; wrapStyleWord = true }
    private val changelogFile = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Changelog"))
    }

    /** Null keeps the item's visibility as it is on the Workshop. */
    private val visibility = ComboBox(arrayOf<WorkshopPublish.Visibility?>(null) + WorkshopPublish.Visibility.entries).apply {
        renderer = object : SimpleListCellRenderer<WorkshopPublish.Visibility?>() {
            override fun customize(list: JList<out WorkshopPublish.Visibility?>, value: WorkshopPublish.Visibility?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.label ?: "Unchanged"
            }
        }
    }

    override fun createEditor(): JComponent = panel {
        row("Mod folder:") {
            cell(contentFolder).align(AlignX.FILL).comment("The built @MyMod folder, as DayZ Mod Build writes it. Add that configuration under Before launch to build first.")
        }
        row("SteamCMD:") {
            cell(steamCmd)
            button("Download SteamCMD…") {
                SteamCmdDownload.run(project, steamCmd)
                showSteamCmd()
            }
        }.rowComment(
            "Valve's command-line Steam client. Its folder belongs to this machine, not the project: " +
                "Settings | Languages & Frameworks | Enforce Script | DayZ Installation.",
        )
        row("Steam account:") {
            cell(account).align(AlignX.FILL)
                .comment("The account name only. SteamCMD asks for the password and the Steam Guard code in its own window the first time, and keeps the login; the IDE never sees them.")
        }
        row("Workshop item ID:") {
            cell(itemId).align(AlignX.FILL).comment("Empty to create a new item: its ID is filled in here after the first upload.")
        }
        row("Visibility:") { cell(visibility) }
        row("Title:") {
            cell(title).align(AlignX.FILL).comment("Needed for a new item. Empty keeps the item's title.")
        }
        row("Description:") {
            // In a scroll pane, which also gives the area its border.
            cell(JBScrollPane(itemDescription)).align(AlignX.FILL).comment("Empty keeps the item's description. Steam reads BBCode here.")
        }
        row("Preview image:") {
            cell(previewFile).align(AlignX.FILL).comment("Empty keeps the item's preview.")
        }
        row("Change note:") {
            cell(JBScrollPane(changeNote)).align(AlignX.FILL).comment("One entry of the item's Change Notes tab.")
        }
        row("Or from a changelog:") {
            cell(changelogFile).align(AlignX.FILL).comment("When the change note is empty: the first section of this file, such as a CHANGELOG.md's newest version.")
        }
    }

    private fun showSteamCmd() {
        steamCmd.text = DayZInstallation.getInstance().steamCmdExe()?.let { FileUtil.toSystemDependentName(it.toString()) } ?: "Not found"
    }

    override fun resetEditorFrom(configuration: DayZWorkshopPublishConfiguration) {
        val o = configuration.options
        contentFolder.text = o.contentFolder.orEmpty()
        showSteamCmd()
        account.text = o.account.orEmpty()
        itemId.text = o.itemId.orEmpty()
        title.text = o.title.orEmpty()
        itemDescription.text = o.itemDescription.orEmpty()
        visibility.selectedItem = WorkshopPublish.Visibility.ofCode(o.visibility)
        previewFile.text = o.previewFile.orEmpty()
        changeNote.text = o.changeNote.orEmpty()
        changelogFile.text = o.changelogFile.orEmpty()
    }

    override fun applyEditorTo(configuration: DayZWorkshopPublishConfiguration) {
        val o = configuration.options
        o.contentFolder = contentFolder.text.trim().ifEmpty { null }
        o.account = account.text.trim().ifEmpty { null }
        o.itemId = itemId.text.trim().ifEmpty { null }
        o.title = title.text.trim().ifEmpty { null }
        o.itemDescription = itemDescription.text.ifEmpty { null }
        o.visibility = (visibility.selectedItem as WorkshopPublish.Visibility?)?.code ?: -1
        o.previewFile = previewFile.text.trim().ifEmpty { null }
        o.changeNote = changeNote.text.ifEmpty { null }
        o.changelogFile = changelogFile.text.trim().ifEmpty { null }
    }
}
