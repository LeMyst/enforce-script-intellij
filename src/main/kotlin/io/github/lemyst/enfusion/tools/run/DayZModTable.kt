package io.github.lemyst.enfusion.tools.run

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import io.github.lemyst.enfusion.tools.AddonBuildSupport
import io.github.lemyst.enfusion.tools.DayZInstallation
import io.github.lemyst.enfusion.tools.WorkDrive
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZMod
import io.github.lemyst.enfusion.tools.detect.DayZModKind
import io.github.lemyst.enfusion.tools.detect.DayZMods
import io.github.lemyst.enfusion.tools.detect.WorkDriveLink
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * The mod list of a DayZ run configuration: a table in load order, with a checkbox per row so that
 * a mod can be turned off without losing it, and buttons to add mods from the mods folders, any
 * folder or loose PBOs, and to rescan the project's addons.
 */
class DayZModTable(
    private val project: Project,
    /** The configuration's *Mods folder*, as typed. */
    private val modsFolder: () -> String?,
    /**
     * For a mod's release build: only what goes into the mod, the project's addons and loose PBOs.
     * Other mods, *Server only* and the `@` folders a rescan finds are for running, not building.
     */
    private val forRelease: Boolean = false,
    private val onChange: () -> Unit,
) {
    /** A mutable row; [exists] is checked when the row is made, not on every paint. */
    private class Row(var path: String, var kind: DayZModKind, var enabled: Boolean, var serverOnly: Boolean) {
        val exists: Boolean = runCatching { File(path).exists() }.getOrDefault(false) || !path.contains(':')

        fun toMod() = DayZMod(path, kind, enabled, serverOnly)
    }

    private val enabledColumn = object : ColumnInfo<Row, Boolean>("On") {
        override fun valueOf(item: Row): Boolean = item.enabled
        override fun isCellEditable(item: Row): Boolean = true
        override fun setValue(item: Row, value: Boolean) {
            item.enabled = value
            onChange()
        }

        override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        override fun getWidth(table: JTable): Int = JBUI.scale(36)
    }

    private val pathColumn = object : ColumnInfo<Row, String>("Mod") {
        override fun valueOf(item: Row): String = item.path
        override fun getRenderer(item: Row): TableCellRenderer = PathRenderer
    }

    private val kindColumn = object : ColumnInfo<Row, String>("Kind") {
        override fun valueOf(item: Row): String = item.kind.title
        override fun getWidth(table: JTable): Int = JBUI.scale(120)
    }

    private val serverOnlyColumn = object : ColumnInfo<Row, Boolean>("Server only") {
        override fun valueOf(item: Row): Boolean = item.serverOnly
        override fun isCellEditable(item: Row): Boolean = true
        override fun setValue(item: Row, value: Boolean) {
            item.serverOnly = value
        }

        override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        override fun getWidth(table: JTable): Int = JBUI.scale(84)
    }

    private val model = ListTableModel<Row>(
        *listOfNotNull(enabledColumn, pathColumn, kindColumn, serverOnlyColumn.takeIf { !forRelease }).toTypedArray(),
    )

    private val table = TableView(model).apply {
        setShowGrid(false)
        visibleRowCount = 8
    }

    val component: JComponent = if (forRelease) {
        ToolbarDecorator.createDecorator(table)
            .setAddAction { rescan() }
            .setAddActionName("Rescan Project Addons")
            .addExtraAction(action("Add PBO Files…", "Third-party PBOs, copied into the mod's addons folder with their signatures", AllIcons.FileTypes.Archive) { addPbos() })
            .createPanel()
    } else {
        ToolbarDecorator.createDecorator(table)
            .setAddAction { addFromModsFolders() }
            .setAddActionName("Add Mods…")
            .addExtraAction(action("Add Folder…", "A mod folder anywhere", AllIcons.Nodes.Folder) { addFolder() })
            .addExtraAction(action("Add PBO Files…", "Loose PBOs, copied into a generated mod folder at launch", AllIcons.FileTypes.Archive) { addPbos() })
            .addExtraAction(action("Rescan Project Addons", "Add the project's config.cpp folders and @ folders, drop addons that are gone", AllIcons.Actions.Refresh) { rescan() })
            .createPanel()
    }

    init {
        model.addTableModelListener { onChange() }
    }

    var mods: List<DayZMod>
        get() = model.items.map { it.toMod() }
        set(value) {
            model.items = value.mapTo(ArrayList()) { Row(it.path, it.kind, it.enabled, it.serverOnly) }
        }

    private fun add(rows: List<DayZMod>) {
        val known = model.items.mapTo(HashSet()) { DayZMods.key(it.path) }
        for (mod in rows) if (known.add(DayZMods.key(mod.path))) model.addRow(Row(mod.path, mod.kind, mod.enabled, mod.serverOnly))
    }

    private fun addFromModsFolders() {
        // Not paths(project): on the EDT it must not start detection, which runs reg.exe.
        val installation = DayZInstallation.getInstance()
        val game = installation.configured.game ?: installation.lastDetected?.game
        val sources = listOfNotNull(
            modsFolder()?.trim()?.takeIf { it.isNotEmpty() }?.let { "Mods folder ($it)" to it },
            game?.resolve("!Workshop")?.let { "DayZ !Workshop" to it.toString() },
        )
        val listed = model.items.mapTo(HashSet()) { DayZMods.key(it.path) }
        val chosen = DayZModPickerDialog.choose(project, sources, listed) ?: return
        add(chosen.map { DayZMod(it, DayZModKind.MOD) })
    }

    private fun addFolder() {
        val chosen = FileChooser.chooseFiles(FileChooserDescriptorFactory.multiDirs().withTitle("Add Mod Folders"), project, null)
        add(chosen.map { DayZMod(FileUtil.toSystemDependentName(it.path), DayZModKind.MOD) })
    }

    private fun addPbos() {
        val descriptor = FileChooserDescriptorFactory.multiFiles().withExtensionFilter("pbo").withTitle("Add PBO Files")
        val chosen = FileChooser.chooseFiles(descriptor, project, null)
        add(chosen.map { DayZMod(FileUtil.toSystemDependentName(it.path), DayZModKind.PBO) })
    }

    /**
     * The project's addons (`config.cpp` folders, as *Build All PBOs* finds them) passed through the
     * work drive, since file patching reads from there, and its `@` folders. Listing the drive's
     * root and comparing folders is disk work, so it runs under a modal progress.
     */
    private fun rescan() {
        val found = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<Pair<List<String>, List<String>>, RuntimeException> { scanProject() },
            "Scanning the project's addons",
            true,
            project,
        )
        val (addons, rootMods) = found
        model.items = DayZMods.rescan(mods, addons, if (forRelease) emptyList() else rootMods) { File(it).exists() }
            .mapTo(ArrayList()) { Row(it.path, it.kind, it.enabled, it.serverOnly) }
    }

    private fun scanProject(): Pair<List<String>, List<String>> {
        // Not ReadAction.compute, deprecated from 2026.1: this runs off the EDT under a progress.
        val (roots, addons) = ReadAction.nonBlocking<Pair<List<Path>, List<Path>>> {
            ProjectRootManager.getInstance(project).contentRoots.map { it.toNioPath() } to
                AddonBuildSupport.allAddons(project).map { it.toNioPath() }
        }.executeSynchronously()
        val letter = WorkDrive.getInstance().letter(DayZInstallation.getInstance().paths(project))
        val drive = Path.of("$letter:\\")
        val entries = if (Files.isDirectory(drive)) {
            runCatching { Files.list(drive).use { s -> s.filter { Files.isDirectory(it) }.toList() } }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val sameFile = { a: Path, b: Path -> runCatching { Files.isSameFile(a, b) }.getOrDefault(false) }
        val links = roots.mapNotNull { WorkDriveLink.linkOf(it, drive, entries, sameFile) }
        // An addon with no link keeps its own path: the row shows it, and the configuration warns.
        val onDrive = addons.sorted().map { addon -> links.firstNotNullOfOrNull { WorkDriveLink.onDrive(addon, it) } ?: addon }
        return onDrive.map { it.toString() } to DayZLaunchCommand.modFolders(roots)
    }

    private fun action(text: String, description: String, icon: javax.swing.Icon, run: () -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    /** The folder or file name, its full path after it in grey; red when it is gone. */
    private object PathRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            @Suppress("UNCHECKED_CAST")
            val item = (table as? TableView<Row>)?.getRow(row) ?: return
            val path = item.path
            val name = path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/')
            append(name, if (item.exists) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.ERROR_ATTRIBUTES)
            if (name != path) append("  $path", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            toolTipText = if (item.exists) path else "$path does not exist"
        }
    }
}
