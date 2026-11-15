package io.github.lemyst.enfusion.tools

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.lemyst.enfusion.tools.detect.DayZToolsCatalog
import java.nio.file.Files

/** The main-toolbar buttons' icons, drawn for the plugin: no Bohemia or DayZ logo. */
object DayZToolbarIcons {
    @JvmField
    val BUILD_PBOS = IconLoader.getIcon("/icons/buildPbos.svg", DayZToolbarIcons::class.java)

    @JvmField
    val WORK_DRIVE = IconLoader.getIcon("/icons/workDrive.svg", DayZToolbarIcons::class.java)

    @JvmField
    val WORKBENCH = IconLoader.getIcon("/icons/workbench.svg", DayZToolbarIcons::class.java)
}

/**
 * The DayZ buttons of the main toolbar (the navigation bar's toolbar in the classic UI). Shown on
 * Windows, while the setting is on, in projects with an addon, so that other projects keep a clean
 * toolbar. Hiding the group hides every button in it.
 */
class DayZToolbarGroup : DefaultActionGroup(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows && project != null &&
            DayZInstallation.getInstance().state.mainToolbar && hasAddon(project)
    }

    /**
     * Whether the project holds a `config.cpp`, as *Build All PBOs* finds them. Toolbars update
     * several times a second, so the walk is cached until the project's roots or the file tree change.
     */
    private fun hasAddon(project: Project): Boolean =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                AddonBuildSupport.allAddons(project).isNotEmpty(),
                ProjectRootManager.getInstance(project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }
}

/** Pressed while the work drive is mounted; a click mounts or unmounts it. */
class ToggleWorkDriveAction : ToggleAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        val status = WorkDrive.getInstance().status
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows
        e.presentation.text = when {
            status == null -> "Work Drive"
            status.mounted -> "Unmount Work Drive (${status.letter}: → ${status.source})"
            else -> "Mount Work Drive on ${status.letter}:"
        }
    }

    /** From the last check: a toolbar update must not start `subst`. */
    override fun isSelected(e: AnActionEvent): Boolean = WorkDrive.getInstance().status?.mounted == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) WorkDrive.getInstance().mount(e.project) else WorkDrive.getInstance().unmount(e.project)
    }
}

/**
 * Starts DayZ Tools' Workbench, with no project: Workbench does not yet load a mod's `.gproj`
 * reliably from its command line, so that is left to its own *Open Project*.
 */
class StartWorkbenchAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val bin = DayZToolsLauncher.knownToolsBin()
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows && bin != null &&
            Files.isRegularFile(bin.resolve(DayZToolsCatalog.WORKBENCH.exe))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val bin = DayZToolsLauncher.knownToolsBin() ?: return
        DayZToolsLauncher.start(e.project, bin, DayZToolsCatalog.WORKBENCH)
    }
}
