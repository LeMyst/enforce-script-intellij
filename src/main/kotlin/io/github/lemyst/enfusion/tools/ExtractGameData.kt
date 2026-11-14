package io.github.lemyst.enfusion.tools

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import io.github.lemyst.enfusion.roots.EnfusionRoots
import io.github.lemyst.enfusion.settings.EnfusionProjectSettings
import io.github.lemyst.enfusion.tools.detect.GameData
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Extracting the game data onto the work drive, and noticing when it was never done.
 *
 * Without it the vanilla roots (`P:\scripts`, `P:\DZ`, `P:\gui`) do not exist and navigation into
 * vanilla silently finds nothing.
 */
object ExtractGameData {

    private const val DONT_ASK = "enfusion.gameData.dontAsk"

    /**
     * Whether to ask: only when the drive is mounted, data is missing, and the user has not opted
     * out. A drive that is not mounted is the work-drive notification's business, not this one's.
     */
    fun shouldAsk(driveMounted: Boolean, missing: List<String>, optedOut: Boolean): Boolean =
        driveMounted && missing.isNotEmpty() && !optedOut

    /** Checks the work drive and shows the notification if [shouldAsk]. Reads the disk: not on the EDT. */
    fun checkAndAsk(project: Project) {
        if (!SystemInfo.isWindows) return
        val paths = DayZInstallation.getInstance().paths(project)
        val letter = WorkDrive.getInstance().letter(paths)
        val drive = Path.of("$letter:\\")
        val mounted = File("$letter:\\").isDirectory
        val missing = if (mounted) GameData.missing(drive) else emptyList()
        if (!shouldAsk(mounted, missing, PropertiesComponent.getInstance().getBoolean(DONT_ASK))) return
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Enforce Script")
            .createNotification(
                "Vanilla game data is not extracted",
                StringUtil.escapeXmlEntities(
                    "$letter: has no ${missing.joinToString(", ") { "$letter:\\$it" }}. Navigation into vanilla sources is unavailable until DayZ Tools extracts the game data.",
                ),
                NotificationType.WARNING,
            )
        if (paths.toolsBin != null && paths.game != null) {
            notification.addAction(NotificationAction.createSimpleExpiring("Extract Game Data…") { start(project) })
        }
        paths.game?.resolve("Addons")?.takeIf { Files.isDirectory(it) }?.let { addons ->
            notification.addAction(NotificationAction.createSimpleExpiring("Use the DayZ install's PBOs instead") {
                val settings = EnfusionProjectSettings.getInstance(project)
                val root = addons.toString()
                if (root !in settings.roots) EnfusionRoots.apply(project, settings.roots + root)
            })
        }
        notification.addAction(NotificationAction.createSimpleExpiring("Don't ask again") {
            PropertiesComponent.getInstance().setValue(DONT_ASK, true)
        })
        notification.notify(project)
    }

    /** Confirms, then runs WorkDrive's extraction with its output in a *Run* tab. */
    fun start(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing to extract game data", false) {
            override fun run(indicator: ProgressIndicator) {
                val paths = DayZInstallation.getInstance().paths(project)
                val bin = paths.toolsBin
                val game = paths.game
                val letter = WorkDrive.getInstance().letter(paths)
                ApplicationManager.getApplication().invokeLater({
                    if (bin == null || game == null || !Files.isRegularFile(bin.resolve("WorkDrive/WorkDrive.exe"))) {
                        Messages.showErrorDialog(
                            project,
                            "Extracting needs DayZ Tools and the DayZ folder. Set them in Settings | Languages & Frameworks | Enforce Script | DayZ Installation.",
                            "Extract Game Data",
                        )
                        return@invokeLater
                    }
                    val destination = Path.of("$letter:\\")
                    val ok = Messages.showOkCancelDialog(
                        project,
                        "DayZ Tools will extract the game data from\n$game\nto $destination.\n\n" +
                            "This writes several gigabytes and takes a while. Files already extracted are replaced.",
                        "Extract Game Data",
                        "Extract",
                        Messages.getCancelButton(),
                        Messages.getWarningIcon(),
                    ) == Messages.OK
                    if (!ok) return@invokeLater
                    val command = GeneralCommandLine(GameData.extractCommand(bin, game, destination))
                        .withWorkDirectory(bin.resolve("WorkDrive").toFile())
                    RunContentExecutor(project, OSProcessHandler(command))
                        .withTitle("Extract Game Data")
                        .withAfterCompletion {
                            ApplicationManager.getApplication().executeOnPooledThread {
                                for (open in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
                                    if (!open.isDisposed) EnfusionRoots.refreshAndReportMissing(open)
                                }
                            }
                        }
                        .run()
                }, { project.isDisposed })
            }
        })
    }
}

/** *Tools | DayZ | Extract Game Data…*. */
class ExtractGameDataAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows && e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        ExtractGameData.start(e.project ?: return)
    }
}
