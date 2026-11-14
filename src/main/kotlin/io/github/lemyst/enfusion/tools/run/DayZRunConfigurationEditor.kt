package io.github.lemyst.enfusion.tools.run

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZLaunchMode
import javax.swing.JComponent
import javax.swing.JList

/** The fields of a DayZ run configuration; the ones a mode does not use are disabled. */
class DayZRunConfigurationEditor(private val project: Project) : SettingsEditor<DayZRunConfiguration>() {

    private val mode = ComboBox(DayZLaunchMode.entries.toTypedArray())
    private val diag = JBCheckBox("Use DayZ Diag (DayZDiag_x64.exe)")
    private val mods = JBTextField()
    private val serverMods = JBTextField()
    private val profiles = JBTextField()
    private val filePatching = JBCheckBox("-filePatching (load loose files from the work drive)")
    private val window = JBCheckBox("-window")
    private val connect = JBTextField()
    private val port = JBTextField()
    private val serverConfig = JBTextField()
    private val mission = JBTextField()
    private val extra = JBTextField()

    init {
        mode.renderer = object : SimpleListCellRenderer<DayZLaunchMode>() {
            override fun customize(list: JList<out DayZLaunchMode>, value: DayZLaunchMode?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.title ?: ""
            }
        }
        mode.addActionListener { updateEnabled() }
        diag.addActionListener { updateEnabled() }
    }

    override fun createEditor(): JComponent = panel {
        row("Mode:") { cell(mode) }
        row { cell(diag) }
        row("Mods:") {
            cell(mods).align(AlignX.FILL).comment("-mod= folders, separated by ;")
            link("Use the project's @mods") {
                val roots = ProjectRootManager.getInstance(project).contentRoots.map { it.toNioPath() }
                mods.text = DayZLaunchCommand.modFolders(roots).joinToString(";")
            }
        }
        row("Server mods:") { cell(serverMods).align(AlignX.FILL).comment("-serverMod=, server only") }
        row("Profiles:") { cell(profiles).align(AlignX.FILL).comment("-profiles=; empty uses the game's default, or the server profile from the DayZ Installation settings") }
        row { cell(filePatching) }
        row { cell(window) }
        row("Connect to:") { cell(connect).comment("Client: -connect=, such as 127.0.0.1") }
        row("Port:") { cell(port).comment("-port=") }
        row("Server config:") { cell(serverConfig).align(AlignX.FILL).comment("Server: -config=, such as serverDZ.cfg") }
        row("Mission:") { cell(mission).align(AlignX.FILL).comment("Diag server: -mission=") }
        row("Extra arguments:") { cell(extra).align(AlignX.FILL) }
    }

    private fun updateEnabled() {
        val selected = mode.selectedItem as? DayZLaunchMode ?: DayZLaunchMode.CLIENT
        val server = selected == DayZLaunchMode.SERVER
        serverMods.isEnabled = server
        serverConfig.isEnabled = server
        mission.isEnabled = server && diag.isSelected
        connect.isEnabled = selected == DayZLaunchMode.CLIENT
        port.isEnabled = selected != DayZLaunchMode.BULDOZER
        window.isEnabled = selected == DayZLaunchMode.CLIENT
        diag.isEnabled = selected != DayZLaunchMode.BULDOZER
    }

    override fun resetEditorFrom(configuration: DayZRunConfiguration) {
        val o = configuration.options
        mode.selectedItem = runCatching { DayZLaunchMode.valueOf(o.mode ?: "") }.getOrDefault(DayZLaunchMode.CLIENT)
        diag.isSelected = o.diag
        mods.text = o.mods.orEmpty()
        serverMods.text = o.serverMods.orEmpty()
        profiles.text = o.profiles.orEmpty()
        filePatching.isSelected = o.filePatching
        window.isSelected = o.window
        connect.text = o.connect.orEmpty()
        port.text = o.port.orEmpty()
        serverConfig.text = o.serverConfig.orEmpty()
        mission.text = o.mission.orEmpty()
        extra.text = o.extraArguments.orEmpty()
        updateEnabled()
    }

    override fun applyEditorTo(configuration: DayZRunConfiguration) {
        val o = configuration.options
        o.mode = (mode.selectedItem as? DayZLaunchMode ?: DayZLaunchMode.CLIENT).name
        o.diag = diag.isSelected
        o.mods = mods.text.trim().ifEmpty { null }
        o.serverMods = serverMods.text.trim().ifEmpty { null }
        o.profiles = profiles.text.trim().ifEmpty { null }
        o.filePatching = filePatching.isSelected
        o.window = window.isSelected
        o.connect = connect.text.trim().ifEmpty { null }
        o.port = port.text.trim().ifEmpty { null }
        o.serverConfig = serverConfig.text.trim().ifEmpty { null }
        o.mission = mission.text.trim().ifEmpty { null }
        o.extraArguments = extra.text.trim().ifEmpty { null }
    }
}
