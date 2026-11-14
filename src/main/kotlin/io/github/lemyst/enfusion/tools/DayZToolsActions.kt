package io.github.lemyst.enfusion.tools

import com.intellij.execution.configurations.GeneralCommandLine
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
import io.github.lemyst.enfusion.tools.detect.DayZToolsCatalog
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

    fun start(project: Project?, toolsBin: Path, tool: DayZToolsCatalog.Tool, file: Path? = null) {
        val exe = toolsBin.resolve(tool.exe)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val command = GeneralCommandLine(exe.toString()).withWorkDirectory(exe.parent.toFile())
                if (file != null) command.addParameter(file.toString())
                command.createProcess()
            } catch (e: Exception) {
                LOG.info("Could not start ${tool.name}", e)
                NotificationGroupManager.getInstance().getNotificationGroup("Enforce Script")
                    .createNotification("DayZ Tools", StringUtil.escapeXmlEntities("Could not start ${tool.name}: ${e.message}"), NotificationType.ERROR)
                    .notify(project)
            }
        }
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
