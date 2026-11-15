package io.github.lemyst.enfusion.tools.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.COLUMNS_TINY
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import io.github.lemyst.enfusion.tools.detect.DayZLaunchMode
import io.github.lemyst.enfusion.tools.detect.DayZMods
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
    private val modTable = DayZModTable(project, mode, { modsFolder.text }) {
        followModList()
        fireEditorStateChanged()
    }
    private val playersTable = DayZPlayersTable { fireEditorStateChanged() }
    private val profiles = JBTextField()
    private val filePatching = JBCheckBox("-filePatching (load loose files from the work drive)")

    /** What the user ticked, kept while the project's addons force the box on. */
    private var filePatchingChoice = true
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
    private val steamEmulator = JBCheckBox("Write a Goldberg Steam emulator identity for each player")
    private val waitSeconds = JBTextField()

    init {
        filePatching.addActionListener { if (filePatching.isEnabled) filePatchingChoice = filePatching.isSelected }
    }

    /**
     * The project's addons run with file patching, so that the game can read the loose files on the
     * work drive over their packed copies: a ticked one turns it on and locks it.
     */
    private fun followModList() {
        val forced = DayZMods.needsFilePatching(modTable.mods)
        filePatching.isEnabled = !forced
        filePatching.isSelected = forced || filePatchingChoice
        filePatching.toolTipText = if (forced) "On for the project's addons in the mod list" else null
    }

    override fun createEditor(): JComponent = panel {
        if (mode != DayZLaunchMode.BULDOZER) row { cell(diag) }
        row("Mods folder:") {
            cell(modsFolder).align(AlignX.FILL)
                .comment("Where Add Mods… looks besides the game's !Workshop, and where bare @Mod names are, such as P:\\Mods.")
        }
        row {
            cell(modTable.component).align(Align.FILL)
                .comment(
                    "In load order. Unticked mods are kept but not passed. Rescan adds the project's config.cpp folders: " +
                        "at launch they are packed into a generated mod, only those that changed, and run with -filePatching. " +
                        "Loose PBOs are copied into a generated @ExtraPBOs mod." +
                        if (mode == DayZLaunchMode.SERVER) " Server only mods go to -serverMod=." else "",
                )
        }.resizableRow()
        row("Profiles:") { cell(profiles).align(AlignX.FILL).comment(profilesComment()) }
        if (mode == DayZLaunchMode.CLIENT) {
            row {
                cell(playersTable.component).align(AlignX.FILL)
                    .comment("One client per player, started one after the other. Empty: one client with no -name=.")
            }
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
                .rowComment("In the -profiles= folder of the server or of each client, or the game's default profile for a client.")
            if (mode == DayZLaunchMode.SERVER) {
                row { cell(wipeStorage) }
                    .rowComment("The persistence. The loot spawns afresh, so every run starts from the same world, more slowly.")
            }
            if (mode == DayZLaunchMode.CLIENT) {
                row { cell(steamEmulator) }
                    .rowComment(
                        "Several clients on one Steam account kick each other off a server. This writes each player's name and Steam ID " +
                            "to %APPDATA%\\Goldberg SteamEmu Saves\\settings just before that client starts, 4 seconds after the previous one. " +
                            "Needs the emulator's steam_api64.dll in the game's folder, which you install.",
                    )
                row("Wait before starting:") {
                    cell(waitSeconds).columns(COLUMNS_TINY)
                        .comment("Seconds, so that a server started with these clients in a Compound is up first.")
                }
            }
        }
    }

    private fun profilesComment(): String = when (mode) {
        DayZLaunchMode.CLIENT ->
            "-profiles=; empty uses the game's default (%LOCALAPPDATA%\\DayZ). With several players, each gets a folder of its name under it."
        DayZLaunchMode.SERVER -> "-profiles=; empty uses the server profile from the DayZ Installation settings"
        DayZLaunchMode.BULDOZER -> "-profiles=; empty uses P:\\Buldozer, as Object Builder does"
    }

    override fun resetEditorFrom(configuration: DayZRunConfiguration) {
        val o = configuration.options
        diag.isSelected = o.diag
        modsFolder.text = o.modsFolder.orEmpty()
        modTable.mods = o.modRows()
        playersTable.players = o.playerRows()
        profiles.text = o.profiles.orEmpty()
        filePatchingChoice = o.filePatching
        followModList()
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
        steamEmulator.isSelected = o.steamEmulator
        waitSeconds.text = o.waitSeconds.orEmpty()
    }

    /**
     * Every field is written back, including the ones this mode does not show: they keep what they
     * were reset from, so nothing saved is lost, and the command line ignores what the mode does not
     * use. File patching is saved as the user chose it; the mod list forces it at launch.
     */
    override fun applyEditorTo(configuration: DayZRunConfiguration) {
        val o = configuration.options
        o.mode = mode.name
        o.diag = diag.isSelected
        o.modsFolder = modsFolder.text.trim().ifEmpty { null }
        o.setModRows(modTable.mods)
        if (mode == DayZLaunchMode.CLIENT) o.setPlayerRows(playersTable.players)
        o.profiles = profiles.text.trim().ifEmpty { null }
        o.filePatching = filePatchingChoice
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
        o.steamEmulator = steamEmulator.isSelected
        o.waitSeconds = waitSeconds.text.trim().ifEmpty { null }
    }
}
