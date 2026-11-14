package io.github.lemyst.enfusion.tools

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.WindowManager
import io.github.lemyst.enfusion.roots.EnfusionRoots
import io.github.lemyst.enfusion.tools.detect.DayZPaths
import io.github.lemyst.enfusion.tools.detect.WorkDriveCommands
import java.io.File

/**
 * Mounting and unmounting the work drive, and knowing whether it is mounted.
 *
 * The state is read from `subst`, which lists WorkDrive's mount too, plus whether the drive's root
 * exists at all: a letter can also be a real disk or a network share, which this never unmounts.
 */
@Service(Service.Level.APP)
class WorkDrive {

    /** What is known about the work drive [letter]: mounted from [source], or a real disk ([present] without a source). */
    data class Status(val letter: Char, val source: String?, val present: Boolean) {
        val mounted: Boolean get() = source != null
    }

    @Volatile
    var status: Status? = null
        private set

    /** The letter to use: the configured or detected one, `P` otherwise. */
    fun letter(paths: DayZPaths): Char = paths.workDriveLetter?.firstOrNull()?.uppercaseChar() ?: 'P'

    /** Reads the state again. Starts `subst`, so not on the EDT. */
    fun refresh(paths: DayZPaths): Status {
        val letter = letter(paths)
        val mapped = if (SystemInfo.isWindows) run(listOf("subst"))?.text?.let(WorkDriveCommands::parseSubst).orEmpty() else emptyMap()
        val result = Status(letter, mapped[letter], File("$letter:\\").isDirectory)
        status = result
        updateWidgets()
        return result
    }

    fun mount(project: Project?) = runInBackground(project, "Mounting the work drive") { indicator ->
        val paths = DayZInstallation.getInstance().paths(project)
        val source = paths.workDriveSource
            ?: return@runInBackground fail(project, "The work-drive folder is not set. Set it in Settings | Languages & Frameworks | Enforce Script | DayZ Installation.")
        val letter = letter(paths)
        val before = refresh(paths)
        if (before.present) {
            val message = if (before.mounted) {
                "$letter: is already mounted from ${before.source}."
            } else {
                "$letter: is already in use by a disk or a network drive, so it cannot be mounted."
            }
            return@runInBackground notify(project, message, NotificationType.WARNING)
        }
        val command = WorkDriveCommands.mount(paths.toolsBin, source, letter)
        val output = run(command) ?: return@runInBackground fail(project, "Could not start ${command.first()}.")
        indicator.text = "Waiting for $letter: to appear"
        // WorkDrive returns before Windows shows the new drive on some machines; give it a moment.
        val after = waitFor(paths, indicator) { it.present }
        if (!after.present) return@runInBackground fail(project, "$letter: did not appear.${details(output.text)}")
        notify(project, "Work drive $letter: mounted from $source.", NotificationType.INFORMATION)
        refreshRoots(letter, indicator)
        // A freshly mounted drive may never have had the game data extracted onto it.
        project?.let { ExtractGameData.checkAndAsk(it) }
    }

    fun unmount(project: Project?) = runInBackground(project, "Unmounting the work drive") { indicator ->
        val paths = DayZInstallation.getInstance().paths(project)
        val letter = letter(paths)
        val before = refresh(paths)
        if (!before.mounted) {
            val message = if (before.present) "$letter: is a disk or a network drive, not a mounted work drive; it is left alone." else "$letter: is not mounted."
            return@runInBackground notify(project, message, NotificationType.WARNING)
        }
        val command = WorkDriveCommands.dismount(paths.toolsBin, letter)
        val output = run(command) ?: return@runInBackground fail(project, "Could not start ${command.first()}.")
        val after = waitFor(paths, indicator) { !it.present }
        if (after.present) return@runInBackground fail(project, "$letter: is still mounted.${details(output.text)}")
        notify(project, "Work drive $letter: unmounted.", NotificationType.INFORMATION)
        refreshRoots(letter, indicator)
    }

    private fun waitFor(paths: DayZPaths, indicator: ProgressIndicator, done: (Status) -> Boolean): Status {
        var current = refresh(paths)
        repeat(20) {
            if (done(current) || indicator.isCanceled) return current
            Thread.sleep(500)
            current = refresh(paths)
        }
        return current
    }

    /**
     * Brings every open project's roots up to date: a vanilla root on the drive that was missing is
     * found once the drive is back, without a restart.
     */
    private fun refreshRoots(letter: Char, indicator: ProgressIndicator) {
        LocalFileSystem.getInstance().refreshAndFindFileByPath("$letter:/")?.refresh(false, false)
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            indicator.text = "Refreshing Enforce Script roots of ${project.name}"
            EnfusionRoots.refreshAndReportMissing(project, indicator)
        }
    }

    private class Output(val exitCode: Int, val text: String)

    private fun run(command: List<String>): Output? = try {
        val handler = CapturingProcessHandler(GeneralCommandLine(command).withCharset(Charsets.UTF_8))
        val result = handler.runProcess(TIMEOUT_MS)
        Output(result.exitCode, (result.stdout + "\n" + result.stderr).trim())
    } catch (e: Exception) {
        LOG.info("Could not run ${command.first()}", e)
        null
    }

    private fun details(output: String?): String =
        output?.takeIf { it.isNotBlank() }?.let { " Output: ${StringUtil.first(it, 400, true)}" } ?: ""

    private fun runInBackground(project: Project?, title: String, body: (ProgressIndicator) -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) = body(indicator)
        })
    }

    private fun fail(project: Project?, message: String) = notify(project, message, NotificationType.ERROR)

    private fun notify(project: Project?, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Enforce Script")
            .createNotification("Work drive", StringUtil.escapeXmlEntities(message), type)
            .notify(project)
    }

    private fun updateWidgets() {
        ApplicationManager.getApplication().invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(WorkDriveWidgetFactory.ID)
            }
        }
    }

    companion object {
        fun getInstance(): WorkDrive = service()

        private val LOG = logger<WorkDrive>()

        /** WorkDrive normally returns at once; this only bounds a hung process. */
        private const val TIMEOUT_MS = 60_000

        /** Whether [path] is on the work drive [letter]. */
        fun isOn(path: String, letter: Char): Boolean = WorkDriveCommands.driveOf(path) == letter
    }
}
