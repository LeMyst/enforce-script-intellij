package io.github.lemyst.enfusion.tools

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import io.github.lemyst.enfusion.settings.EnfusionProjectSettings
import io.github.lemyst.enfusion.tools.detect.DayZToolsCatalog
import io.github.lemyst.enfusion.tools.detect.WorkDriveLink
import io.github.lemyst.enfusion.tools.detect.WorkbenchCommand
import java.nio.file.Files
import java.nio.file.Path

/** Starting DayZ Tools' desktop tools as separate processes the IDE does not wait for. */
internal object DayZToolsLauncher {

    private val LOG = logger<DayZToolsLauncher>()

    /**
     * The `Bin` folder without detecting: action updates run often and must not start `reg.exe`.
     * Until detection has run once in the session, only a configured folder is known.
     */
    fun knownToolsBin(): Path? {
        val installation = DayZInstallation.getInstance()
        return (installation.configured.tools ?: installation.lastDetected?.tools)?.resolve("Bin")
    }

    /**
     * Starts [tool] from its own folder, which Workbench needs as its current directory. Workbench,
     * started on a project rather than a file, gets the project's mod (see [WorkbenchMod]), unless
     * that mod would make it crash and [anyway] is false: then a notification says why instead.
     */
    fun start(project: Project?, toolsBin: Path, tool: DayZToolsCatalog.Tool, file: Path? = null, anyway: Boolean = false) {
        val exe = toolsBin.resolve(tool.exe)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val args = when {
                    file != null -> listOf(exe.toString(), file.toString())
                    tool == DayZToolsCatalog.WORKBENCH && project != null -> {
                        val mod = WorkbenchMod.folder(project)
                        val crashing = mod?.let(WorkbenchCommand::crashingAddon)
                        if (mod != null && crashing != null && !anyway) {
                            warnCrashing(project, toolsBin, mod, crashing)
                            return@executeOnPooledThread
                        }
                        WorkbenchCommand.command(exe, mod?.toString())
                    }
                    else -> listOf(exe.toString())
                }
                GeneralCommandLine(args).withWorkDirectory(exe.parent.toFile()).createProcess()
            } catch (e: Exception) {
                LOG.info("Could not start ${tool.name}", e)
                NotificationGroupManager.getInstance().getNotificationGroup("Enforce Script")
                    .createNotification("DayZ Tools", StringUtil.escapeXmlEntities("Could not start ${tool.name}: ${e.message}"), NotificationType.ERROR)
                    .notify(project)
            }
        }
    }
}

private fun warnCrashing(project: Project, toolsBin: Path, mod: Path, folder: String) {
    val text = "$mod has an addon in $folder\\. Workbench mounts the mod at the root of the game's files, so its config.cpp " +
        "replaces the game's scripts\\config.cpp, and Workbench crashes as it starts. Rename the folder (such as MyMod_Scripts) " +
        "and its paths in config.cpp."
    NotificationGroupManager.getInstance().getNotificationGroup("Enforce Script")
        .createNotification("Workbench would crash on this mod", StringUtil.escapeXmlEntities(text), NotificationType.WARNING)
        .addAction(
            NotificationAction.createSimpleExpiring("Start Workbench anyway") {
                DayZToolsLauncher.start(project, toolsBin, DayZToolsCatalog.WORKBENCH, anyway = true)
            },
        )
        .notify(project)
}

/** The folder Workbench is given as `-mod=` for a project. Reads the disk: not on the EDT. */
internal object WorkbenchMod {

    /** The project's setting, or else the project's folder on the work drive, when it has one. */
    fun folder(project: Project): Path? {
        EnfusionProjectSettings.getInstance(project).workbenchMod?.let { return runCatching { Path.of(it) }.getOrNull() }
        return guess(project)
    }

    fun guess(project: Project): Path? {
        val base = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
        val letter = WorkDrive.getInstance().letter(DayZInstallation.getInstance().paths(project))
        val drive = Path.of("$letter:\\")
        if (!Files.isDirectory(drive)) return null
        val entries = runCatching { Files.list(drive).use { s -> s.filter { Files.isDirectory(it) }.toList() } }.getOrDefault(emptyList())
        // The project itself when it lies on the drive, else the link at the drive's root to it or
        // to a folder holding it (P:\MyMod → the checkout), as mod setup scripts create.
        val link = WorkDriveLink.linkOf(base, drive, entries) { a, b -> runCatching { Files.isSameFile(a, b) }.getOrDefault(false) }
        return link?.let { WorkDriveLink.onDrive(base, it) }
    }
}

/**
 * *Tools | DayZ Tools*: one entry per installed desktop tool. Tools that are not installed are not
 * listed, and without DayZ Tools the group is hidden.
 */
class DayZToolsGroup : ActionGroup(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows && DayZToolsCatalog.installed(DayZToolsLauncher.knownToolsBin()).isNotEmpty()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val bin = DayZToolsLauncher.knownToolsBin() ?: return EMPTY_ARRAY
        return DayZToolsCatalog.installed(bin).map { tool ->
            object : DumbAwareAction(tool.name, "Start ${tool.name}", null) {
                override fun actionPerformed(e: AnActionEvent) = DayZToolsLauncher.start(e.project, bin, tool)
            }
        }.toTypedArray()
    }
}

/**
 * *Open in TexView 2*, *Open in Object Builder*, *Open in Material Editor* on a file those tools
 * take. The built-in texture viewer stays the default for textures; this is for the cases it does
 * not cover, such as checking a texture the way the game's own tools show it.
 */
class OpenInDayZToolAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val tool = if (SystemInfo.isWindows && file != null && !file.isDirectory && file.isInLocalFileSystem) {
            DayZToolsCatalog.opener(DayZToolsLauncher.knownToolsBin(), file.extension ?: "")
        } else {
            null
        }
        e.presentation.isEnabledAndVisible = tool != null
        if (tool != null) e.presentation.text = "Open in ${tool.name}"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val bin = DayZToolsLauncher.knownToolsBin() ?: return
        val tool = DayZToolsCatalog.opener(bin, file.extension ?: "") ?: return
        DayZToolsLauncher.start(e.project, bin, tool, file.toNioPath())
    }
}
