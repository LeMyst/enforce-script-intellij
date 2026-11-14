package io.github.lemyst.enfusion.tools.check

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import io.github.lemyst.enfusion.tools.DayZInstallation
import io.github.lemyst.enfusion.tools.detect.DayZLaunch
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZLaunchMode
import io.github.lemyst.enfusion.tools.logs.DayZLogLines
import io.github.lemyst.enfusion.tools.logs.DayZLogTail
import io.github.lemyst.enfusion.tools.logs.LocationFilter
import io.github.lemyst.enfusion.tools.logs.ScriptCheckLog
import io.github.lemyst.enfusion.tools.run.DayZRunConfiguration
import java.nio.file.Files
import java.nio.file.Path

/**
 * *Tools | DayZ | Check Scripts*: lets the engine itself compile the project's scripts.
 *
 * DayZ Diag is started as a server (`DayZDiag_x64.exe -server`, so no game window) with the mods of
 * the selected DayZ run configuration, or the project's `@` folders, and a throwaway profile folder
 * of its own. Its `script_*.log` is read as it is written; as soon as it says the last module
 * compiled, or that one could not be, the server is stopped and the verdict shown, with the errors
 * as links. Compiling takes about as long as a game start, so this is an explicit action, never an
 * on-save check.
 */
object CheckScripts {

    /** Long enough for a large mod set on a slow disk; the check normally ends within a minute. */
    private const val TIMEOUT_MS = 5 * 60 * 1000L

    /** Called on the EDT, from the action. */
    fun run(project: Project) {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.addMessageFilter(LocationFilter(project))
        val descriptor = RunContentDescriptor(console, null, console.component, "Check Scripts")
        Disposer.register(descriptor, console)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        val mods = modsFor(project)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Checking scripts with DayZ Diag", true) {
            override fun run(indicator: ProgressIndicator) = check(project, mods, console, indicator)
        })
    }

    /** The mods of the selected DayZ run configuration, or else the project's `@` folders. */
    private fun modsFor(project: Project): List<String> {
        val selected = RunManager.getInstance(project).selectedConfiguration?.configuration as? DayZRunConfiguration
        if (selected != null) return selected.options.toLaunch().mods
        return DayZLaunchCommand.modFolders(ProjectRootManager.getInstance(project).contentRoots.map { it.toNioPath() })
    }

    private fun check(project: Project, mods: List<String>, console: ConsoleView, indicator: ProgressIndicator) {
        val paths = DayZInstallation.getInstance().paths(project)
        val profile = Files.createTempDirectory(Path.of(PathManager.getTempPath()), "enfusion-check-")
        val launch = DayZLaunch(mode = DayZLaunchMode.SERVER, diag = true, mods = mods, profiles = profile.toString(), filePatching = true)
        val command = try {
            DayZLaunchCommand.build(paths, launch)
        } catch (e: DayZLaunchCommand.Problem) {
            report(project, console, "${e.message} Set it in Settings | Languages & Frameworks | Enforce Script | DayZ Installation.", NotificationType.ERROR)
            return
        }
        console.print("> ${command.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }}\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        val handler = OSProcessHandler(GeneralCommandLine(command).withWorkDirectory(Path.of(command.first()).parent.toFile()))
        handler.startNotify()
        val tail = DayZLogTail(profile, DayZLogLines.Kind.SCRIPT)
        val lines = ArrayList<String>()
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var verdict: ScriptCheckLog.Verdict = ScriptCheckLog.Verdict.Pending
        try {
            while (!indicator.isCanceled && System.currentTimeMillis() < deadline) {
                tail.poll()?.let { chunk ->
                    lines += chunk.lines
                    chunk.lines.lastOrNull { it.contains("Module:") }
                        ?.substringAfter("Module:")?.substringBefore(';')?.trim()
                        ?.let { indicator.text2 = "Compiled $it" }
                }
                verdict = ScriptCheckLog.verdict(lines)
                if (verdict != ScriptCheckLog.Verdict.Pending) break
                // The server stopped on its own: read what it left, then give up waiting.
                if (handler.waitFor(500)) {
                    tail.poll()?.let { lines += it.lines }
                    verdict = ScriptCheckLog.verdict(lines)
                    break
                }
            }
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
        }
        when (val result = verdict) {
            is ScriptCheckLog.Verdict.Compiled ->
                report(project, console, "All five script modules compiled.", NotificationType.INFORMATION)

            is ScriptCheckLog.Verdict.Failed -> {
                console.print("Can't compile \"${result.module}\" script module:\n", ConsoleViewContentType.ERROR_OUTPUT)
                result.errors.forEach { console.print("  $it\n", ConsoleViewContentType.ERROR_OUTPUT) }
                val count = "${result.errors.size} ${if (result.errors.size == 1) "error" else "errors"}"
                report(project, console, "The \"${result.module}\" script module does not compile: $count. See the Run tool window.", NotificationType.ERROR)
            }

            ScriptCheckLog.Verdict.Pending -> {
                val why = when {
                    indicator.isCanceled -> "Cancelled."
                    lines.isEmpty() -> "DayZ Diag wrote no script log to $profile. Check the DayZ folder, and that DayZ Diag starts."
                    else -> "No verdict: the script log did not say whether the modules compiled."
                }
                lines.takeLast(20).forEach { console.print("$it\n", ConsoleViewContentType.NORMAL_OUTPUT) }
                report(project, console, why, NotificationType.WARNING)
            }
        }
    }

    private fun report(project: Project, console: ConsoleView, message: String, type: NotificationType) {
        val contentType = if (type == NotificationType.ERROR) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.SYSTEM_OUTPUT
        console.print("\n$message\n", contentType)
        NotificationGroupManager.getInstance().getNotificationGroup("Enforce Script")
            .createNotification("Check Scripts", StringUtil.escapeXmlEntities(message), type)
            .notify(project)
    }
}

/** *Tools | DayZ | Check Scripts*. */
class CheckScriptsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows && e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        CheckScripts.run(e.project ?: return)
    }
}
