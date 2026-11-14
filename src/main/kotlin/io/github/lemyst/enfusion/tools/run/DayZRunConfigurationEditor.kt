package io.github.lemyst.enfusion.tools.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.COLUMNS_TINY
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZLaunchMode
import javax.swing.JComponent

/**
 * The fields of a DayZ run configuration of one [mode]. Each mode has its own entry under *DayZ*
 * in the *+* menu, so a field the mode does not use is left out rather than shown disabled.
 */
class DayZRunConfigurationEditor(
    private val project: Project,
    private val mode: DayZLaunchMode,
) : SettingsEditor<DayZRunConfiguration>() {

    private val diag = JBCheckBox(
        if (mode == DayZLaunchMode.SERVER) "Use DayZ Diag (DayZDiag_x64.exe -server)" else "Use DayZ Diag (DayZDiag_x64.exe)",
    )
    private val modsFolder = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Mods Folder"))
    }
    private val mods = JBTextField()
    private val serverMods = JBTextField()
    private val profiles = JBTextField()
    private val playerName = JBTextField()
    private val filePatching = JBCheckBox("-filePatching (load loose files from the work drive)")
    private val window = JBCheckBox("-window")
    private val connect = JBTextField()
    private val port = JBTextField()
    private val password = JBPasswordField()
    private val serverConfig = JBTextField()
    private val mission = JBTextField()
    private val extra = JBTextField()
    private val stopRunning = JBCheckBox("Stop every running DayZ, DayZ Diag and DayZ Server")
    private val clearLogs = JBCheckBox("Clear the profile's logs (.log, .rpt, .mdmp, .ADM)")
    private val wipeStorage = JBCheckBox("Wipe the mission's storage_* folders")

    override fun createEditor(): JComponent = panel {
        if (mode != DayZLaunchMode.BULDOZER) row { cell(diag) }
        row("Mods folder:") {
            cell(modsFolder).align(AlignX.FILL)
                .comment("Where bare @Mod names below are, such as P:\\Mods. Empty: beside the game's executable.")
        }
        row("Mods:") {
            // Two cells share this row, so the field must be the resizable column to fill it.
            cell(mods).resizableColumn().align(AlignX.FILL).comment("-mod= folders, separated by ;")
            link("Use the project's @mods") {
                val roots = ProjectRootManager.getInstance(project).contentRoots.map { it.toNioPath() }
                mods.text = DayZLaunchCommand.modFolders(roots).joinToString(";")
            }
        }
        if (mode == DayZLaunchMode.SERVER) {
            row("Server mods:") { cell(serverMods).align(AlignX.FILL).comment("-serverMod=") }
        }
        row("Profiles:") { cell(profiles).align(AlignX.FILL).comment(profilesComment()) }
        if (mode == DayZLaunchMode.CLIENT) {
            row("Player name:") { cell(playerName).columns(COLUMNS_MEDIUM).comment("-name=") }
        }
        row { cell(filePatching) }
        when (mode) {
            DayZLaunchMode.CLIENT -> {
                row { cell(window) }
                row("Connect to:") { cell(connect).columns(COLUMNS_MEDIUM).comment("-connect=, such as 127.0.0.1, to join a server at start") }
                row("Port:") { cell(port).columns(COLUMNS_TINY).comment("-port=") }
                row("Password:") {
                    cell(password).columns(COLUMNS_MEDIUM)
                        .comment("-password=, when joining a server. Saved with the configuration in plain text.")
                }
                row("Mission:") {
                    cell(mission).align(AlignX.FILL)
                        .comment("-mission=, to play offline (leave Connect to empty). Relative to the game's folder.")
                }
            }

            DayZLaunchMode.SERVER -> {
                row("Port:") { cell(port).columns(COLUMNS_TINY).comment("-port=") }
                row("Server config:") { cell(serverConfig).align(AlignX.FILL).comment("-config=, such as serverDZ.cfg") }
                row("Mission:") {
                    cell(mission).align(AlignX.FILL)
                        .comment("-mission=, DayZ Diag only: the dedicated server takes its mission from the server config.")
                }.enabledIf(diag.selected)
            }

            DayZLaunchMode.BULDOZER -> {}
        }
        row("Extra arguments:") { cell(extra).align(AlignX.FILL) }
        group("Before Launch") {
            row { cell(stopRunning) }
                .rowComment("Including a server another configuration started.")
            row { cell(clearLogs) }
                .rowComment("In the -profiles= folder, or the game's default profile for a client.")
            if (mode == DayZLaunchMode.SERVER) {
                row { cell(wipeStorage) }
                    .rowComment("The persistence. The loot spawns afresh, so every run starts from the same world, more slowly.")
            }
        }
    }

    private fun profilesComment(): String = when (mode) {
        DayZLaunchMode.CLIENT -> "-profiles=; empty uses the game's default (%LOCALAPPDATA%\\DayZ)"
        DayZLaunchMode.SERVER -> "-profiles=; empty uses the server profile from the DayZ Installation settings"
        DayZLaunchMode.BULDOZER -> "-profiles=; empty uses P:\\Buldozer, as Object Builder does"
    }

    override fun resetEditorFrom(configuration: DayZRunConfiguration) {
        val o = configuration.options
        diag.isSelected = o.diag
        modsFolder.text = o.modsFolder.orEmpty()
        mods.text = o.mods.orEmpty()
        serverMods.text = o.serverMods.orEmpty()
        profiles.text = o.profiles.orEmpty()
        playerName.text = o.playerName.orEmpty()
        filePatching.isSelected = o.filePatching
        window.isSelected = o.window
        connect.text = o.connect.orEmpty()
        port.text = o.port.orEmpty()
        password.text = o.password.orEmpty()
        serverConfig.text = o.serverConfig.orEmpty()
        mission.text = o.mission.orEmpty()
        extra.text = o.extraArguments.orEmpty()
        stopRunning.isSelected = o.stopRunning
        clearLogs.isSelected = o.clearLogs
        wipeStorage.isSelected = o.wipeStorage
    }

    /**
     * Every field is written back, including the ones this mode does not show: they keep what they
     * were reset from, so nothing saved is lost, and [DayZLaunchCommand] ignores what the mode
     * does not use.
     */
    override fun applyEditorTo(configuration: DayZRunConfiguration) {
        val o = configuration.options
        o.mode = mode.name
        o.diag = diag.isSelected
        o.modsFolder = modsFolder.text.trim().ifEmpty { null }
        o.mods = mods.text.trim().ifEmpty { null }
        o.serverMods = serverMods.text.trim().ifEmpty { null }
        o.profiles = profiles.text.trim().ifEmpty { null }
        o.playerName = playerName.text.trim().ifEmpty { null }
        o.filePatching = filePatching.isSelected
        o.window = window.isSelected
        o.connect = connect.text.trim().ifEmpty { null }
        o.port = port.text.trim().ifEmpty { null }
        o.password = String(password.password).ifEmpty { null }
        o.serverConfig = serverConfig.text.trim().ifEmpty { null }
        o.mission = mission.text.trim().ifEmpty { null }
        o.extraArguments = extra.text.trim().ifEmpty { null }
        o.stopRunning = stopRunning.isSelected
        o.clearLogs = clearLogs.isSelected
        o.wipeStorage = wipeStorage.isSelected
    }
}
