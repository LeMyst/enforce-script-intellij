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
import com.intellij.ui.layout.not
import com.intellij.ui.layout.selected
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZMods
import javax.swing.JComponent

/**
 * A DayZ run configuration: what the server and the clients share (the executables, the mod list,
 * the port and the mission) at the top, then what only the clients use, then what only the server
 * uses, each section switched on by its own checkbox.
 */
class DayZRunConfigurationEditor(private val project: Project) : SettingsEditor<DayZRunConfiguration>() {

    private val startServer = JBCheckBox("Start a server")
    private val startClients = JBCheckBox("Start clients")
    private val diag = JBCheckBox("Use DayZ Diag (DayZDiag_x64.exe, with -server for the server)")
    private val modsFolder = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Mods Folder"))
    }
    private val modTable = DayZModTable(project, { modsFolder.text }) {
        followModList()
        fireEditorStateChanged()
    }
    private val playersTable = DayZPlayersTable { fireEditorStateChanged() }
    private val filePatching = JBCheckBox("-filePatching (load loose files from the work drive)")

    /** What the user ticked, kept while the project's addons force the box on. */
    private var filePatchingChoice = true
    private val port = JBTextField()
    private val mission = JBTextField()
    private val extra = JBTextField()

    private val profiles = JBTextField()
    private val window = JBCheckBox("-window")
    private val connect = JBTextField()
    private val password = JBPasswordField()
    private val steamEmulator = JBCheckBox("Write a Goldberg Steam emulator identity for each player")
    private val clientDelay = JBTextField()

    private val serverConfig = JBTextField()
    private val serverProfile = JBTextField()
    private val wipeStorage = JBCheckBox("Wipe the mission's storage_* folders before starting")

    private val stopRunning = JBCheckBox("Stop every running DayZ, DayZ Diag and DayZ Server")
    private val clearLogs = JBCheckBox("Clear the profiles' logs (.log, .rpt, .mdmp, .ADM)")

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
        row {
            cell(startServer)
            cell(startClients)
        }.rowComment("The server starts first; the clients join it on this machine.")
        row { cell(diag) }
        row("Mods folder:") {
            cell(modsFolder).align(AlignX.FILL)
                .comment("Where Add Mods… looks besides the game's !Workshop, and where bare @Mod names are, such as P:\\Mods.")
        }
        row {
            cell(modTable.component).align(Align.FILL)
                .comment(
                    "One list for the server and the clients, in load order. Unticked mods are kept but not passed; " +
                        "Server only mods go to the server's -serverMod= and to no client. Rescan adds the project's " +
                        "config.cpp folders: at launch they are packed into a generated mod, only those that changed. " +
                        "Loose PBOs are copied into a generated @ExtraPBOs mod.",
                )
        }.resizableRow()
        row { cell(filePatching) }
        row("Port:") {
            cell(port).columns(COLUMNS_TINY)
                .comment("The server's -port=, and the one the clients join; empty is ${DayZLaunchCommand.DEFAULT_PORT}.")
        }
        row("Mission:") {
            cell(mission).align(AlignX.FILL)
                .comment(
                    "-mission=: a DayZ Diag server's (the dedicated server reads it from its config), " +
                        "or the clients' to play offline when there is no server to join. Relative to the game's folder.",
                )
        }
        row("Extra arguments:") { cell(extra).align(AlignX.FILL).comment("Passed to every process.") }

        group("Clients") {
            row {
                cell(playersTable.component).align(AlignX.FILL)
                    .comment("One client per player, started one after the other. Empty: one client with no -name=.")
            }
            row("Profiles:") {
                cell(profiles).align(AlignX.FILL)
                    .comment(
                        "-profiles= of a player without one; empty is the game's default (%LOCALAPPDATA%\\DayZ). " +
                            "With several players, each gets a folder of its name under it.",
                    )
            }
            row { cell(window) }
            row("Connect to:") {
                cell(connect).columns(COLUMNS_MEDIUM)
                    .comment("-connect=, to join a server this run does not start. With Start a server, the clients join 127.0.0.1.")
            }.enabledIf(startServer.selected.not())
            row("Password:") {
                cell(password).columns(COLUMNS_MEDIUM)
                    .comment("-password=, when joining a server. Saved with the configuration in plain text.")
            }
            row { cell(steamEmulator) }
                .rowComment(
                    "Several clients on one Steam account kick each other off a server. This writes each player's name and " +
                        "Steam ID to %APPDATA%\\Goldberg SteamEmu Saves\\settings just before that client starts, 4 seconds " +
                        "after the previous one. Needs the emulator's steam_api64.dll in the game's folder, which you install.",
                )
            row("Start the clients:") {
                cell(clientDelay).columns(COLUMNS_TINY)
                label("seconds after the server")
            }.enabledIf(startServer.selected)
        }.enabledIf(startClients.selected)

        group("Server") {
            row("Server config:") { cell(serverConfig).align(AlignX.FILL).comment("-config=, such as serverDZ.cfg") }
            row("Profile:") {
                cell(serverProfile).align(AlignX.FILL)
                    .comment("-profiles=; empty uses the server profile from the DayZ Installation settings.")
            }
            row { cell(wipeStorage) }
                .rowComment("The persistence. The loot spawns afresh, so every run starts from the same world, more slowly.")
        }.enabledIf(startServer.selected)

        group("Before Launch") {
            row { cell(stopRunning) }
            row { cell(clearLogs) }.rowComment("In the server's profile and each client's.")
        }
    }

    override fun resetEditorFrom(configuration: DayZRunConfiguration) {
        val o = configuration.options
        o.migrate()
        startServer.isSelected = o.startServer
        startClients.isSelected = o.startClients
        diag.isSelected = o.diag
        modsFolder.text = o.modsFolder.orEmpty()
        modTable.mods = o.modRows()
        playersTable.players = o.playerRows()
        filePatchingChoice = o.filePatching
        followModList()
        port.text = o.port.orEmpty()
        mission.text = o.mission.orEmpty()
        extra.text = o.extraArguments.orEmpty()
        profiles.text = o.profiles.orEmpty()
        window.isSelected = o.window
        connect.text = o.connect.orEmpty()
        password.text = o.password.orEmpty()
        steamEmulator.isSelected = o.steamEmulator
        clientDelay.text = o.clientDelaySeconds ?: "5"
        serverConfig.text = o.serverConfig.orEmpty()
        serverProfile.text = o.serverProfile.orEmpty()
        wipeStorage.isSelected = o.wipeStorage
        stopRunning.isSelected = o.stopRunning
        clearLogs.isSelected = o.clearLogs
    }

    /**
     * Every field is written back, including those of a section switched off, so that switching it
     * on again finds them. File patching is saved as the user chose it; the mod list forces it at
     * launch.
     */
    override fun applyEditorTo(configuration: DayZRunConfiguration) {
        val o = configuration.options
        o.mode = null
        o.startServer = startServer.isSelected
        o.startClients = startClients.isSelected
        o.diag = diag.isSelected
        o.modsFolder = modsFolder.text.trim().ifEmpty { null }
        o.setModRows(modTable.mods)
        o.setPlayerRows(playersTable.players)
        o.filePatching = filePatchingChoice
        o.port = port.text.trim().ifEmpty { null }
        o.mission = mission.text.trim().ifEmpty { null }
        o.extraArguments = extra.text.trim().ifEmpty { null }
        o.profiles = profiles.text.trim().ifEmpty { null }
        o.window = window.isSelected
        o.connect = connect.text.trim().ifEmpty { null }
        o.password = String(password.password).ifEmpty { null }
        o.steamEmulator = steamEmulator.isSelected
        o.clientDelaySeconds = clientDelay.text.trim().ifEmpty { null }
        o.serverConfig = serverConfig.text.trim().ifEmpty { null }
        o.serverProfile = serverProfile.text.trim().ifEmpty { null }
        o.wipeStorage = wipeStorage.isSelected
        o.stopRunning = stopRunning.isSelected
        o.clearLogs = clearLogs.isSelected
    }
}
