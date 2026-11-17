package io.github.lemyst.enfusion.tools.check

import com.intellij.execution.ExecutionException
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
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import io.github.lemyst.enfusion.tools.DayZInstallation
import io.github.lemyst.enfusion.tools.detect.DayZLaunch
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.logs.DayZLogLines
import io.github.lemyst.enfusion.tools.logs.DayZLogTail
import io.github.lemyst.enfusion.tools.logs.LocationFilter
import io.github.lemyst.enfusion.tools.logs.ScriptCheckLog
import io.github.lemyst.enfusion.tools.run.DayZRunConfiguration
import java.nio.file.Files
import java.nio.file.Path

/**
 * *Tools | DayZ | Check Scripts (Server)* and *(Client)*: lets the engine itself compile the
 * project's scripts, as the server compiles them (`SERVER` defined) or as a client does, which is
 * where `#ifndef SERVER` code, most of a mod's UI, is compiled.
 *
 * DayZ Diag is started as a server (`DayZDiag_x64.exe -server`, so no game window), or as a client
 * in a window that closes once the scripts compile, on the mod list
 * of a DayZ run configuration, the selected one or else the project's first. The project's addons
 * are packed as a run packs them: unpacked folders mounted at the root of the game's files would
 * override vanilla's. The server gets a throwaway profile folder and a config of its own, written
 * there, naming no real mission: it is stopped before it would load one, so nothing writes to a
 * mission's storage. A client gets the profile only, and joins nothing. The `script_*.log` is read as it is written; as soon as it says the last
 * module compiled, or that one could not be, the server is stopped and the verdict shown, with the
 * errors as links. Compiling takes about as long as a game start, so this is an explicit action,
 * never an on-save check.
 */
object CheckScripts {

    /** Which side compiles: the server, with `SERVER` defined, or a client. */
    enum class Side(val title: String, val noun: String) {
        SERVER("Check Scripts (Server)", "server"),
        CLIENT("Check Scripts (Client)", "client"),
    }

    /** Long enough for a large mod set on a slow disk; the check normally ends within a minute. */
    private const val TIMEOUT_MS = 5 * 60 * 1000L

    /** Away from the 2302 of a test server that may be running. */
    private const val CHECK_PORT = 2402

    /** Called on the EDT, from the action. */
    fun run(project: Project, side: Side) {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.addMessageFilter(LocationFilter(project))
        val descriptor = RunContentDescriptor(console, null, console.component, side.title)
        Disposer.register(descriptor, console)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        val configuration = configurationFor(project)
        if (configuration == null) {
            report(
                project, console, side,
                "Check Scripts starts DayZ Diag on the mod list of a DayZ run configuration. Create one in Run | Edit Configurations, or select it.",
                NotificationType.WARNING,
            )
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Checking ${side.noun} scripts with DayZ Diag", true) {
            override fun run(indicator: ProgressIndicator) = check(project, configuration, side, console, indicator)
        })
    }

    /** The selected DayZ run configuration, or else the project's first. */
    private fun configurationFor(project: Project): DayZRunConfiguration? {
        val manager = RunManager.getInstance(project)
        (manager.selectedConfiguration?.configuration as? DayZRunConfiguration)?.let { return it }
        return manager.allConfigurationsList.filterIsInstance<DayZRunConfiguration>().firstOrNull()
    }

    private fun check(project: Project, configuration: DayZRunConfiguration, side: Side, console: ConsoleView, indicator: ProgressIndicator) {
        val paths = DayZInstallation.getInstance().paths(project)
        val launch = configuration.options.toLaunch()
        console.print("Mods of the run configuration '${configuration.name}'.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        val profile = Files.createTempDirectory(Path.of(PathManager.getTempPath()), "enfusion-check-")
        val config = profile.resolve("serverDZ.cfg")
        if (side == Side.SERVER) Files.writeString(config, ScriptCheckLog.SERVER_CONFIG)
        val check = launchFor(launch, side, profile.toString(), config.toString())
        // Before packing, which reads the addons from the drive: the game would start without the
        // mods and say nothing.
        if (SystemInfo.isWindows) {
            val unmounted = DayZLaunchCommand.drivesUsed(check).filter { !Files.isDirectory(Path.of("$it:\\")) }
            if (unmounted.isNotEmpty()) {
                report(project, console, side, "${unmounted.joinToString(", ") { "$it:" }} is not mounted, and the mod list reads from it.", NotificationType.ERROR)
                return
            }
        }
        indicator.text2 = "Packing the project's addons"
        val prepared = try {
            configuration.prepareMods(launch, paths)
        } catch (e: ExecutionException) {
            report(project, console, side, e.message.orEmpty(), NotificationType.ERROR)
            return
        }
        prepared.lines.forEach { console.print("$it\n", ConsoleViewContentType.SYSTEM_OUTPUT) }
        val command = try {
            when (side) {
                Side.SERVER -> DayZLaunchCommand.buildServer(paths, check, prepared.pboMod, prepared.addonMod)
                Side.CLIENT -> DayZLaunchCommand.buildClient(paths, check, null, prepared.pboMod, prepared.addonMod)
            }
        } catch (e: DayZLaunchCommand.Problem) {
            report(project, console, side, "${e.message} Set it in Settings | Languages & Frameworks | Enforce Script | DayZ Installation.", NotificationType.ERROR)
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
                // The game stopped on its own: read what it left, then give up waiting.
                if (handler.waitFor(500)) {
                    tail.poll()?.let { lines += it.lines }
                    verdict = ScriptCheckLog.verdict(lines)
                    break
                }
            }
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
            // The log has been read, and the folder was the game's alone; best effort, once it exits.
            handler.waitFor(5_000)
            profile.toFile().deleteRecursively()
        }
        when (val result = verdict) {
            is ScriptCheckLog.Verdict.Compiled ->
                report(project, console, side, "All five script modules compiled on the ${side.noun}.", NotificationType.INFORMATION)

            is ScriptCheckLog.Verdict.Failed -> {
                console.print("Can't compile \"${result.module}\" script module:\n", ConsoleViewContentType.ERROR_OUTPUT)
                result.errors.forEach { console.print("  $it\n", ConsoleViewContentType.ERROR_OUTPUT) }
                val count = "${result.errors.size} ${if (result.errors.size == 1) "error" else "errors"}"
                report(project, console, side, "The \"${result.module}\" script module does not compile on the ${side.noun}: $count. See the Run tool window.", NotificationType.ERROR)
            }

            ScriptCheckLog.Verdict.Pending -> {
                val why = when {
                    indicator.isCanceled -> "Cancelled."
                    lines.isEmpty() -> "DayZ Diag wrote no script log to $profile. Check the DayZ folder, and that DayZ Diag starts."
                    else -> "No verdict: the script log did not say whether the modules compiled."
                }
                lines.takeLast(20).forEach { console.print("$it\n", ConsoleViewContentType.NORMAL_OUTPUT) }
                report(project, console, side, why, NotificationType.WARNING)
            }
        }
    }

    /**
     * The launch a check makes of the run configuration's [launch]: its mods and options, as one
     * server with its own [profile] and [config], or one client with its own [profile] that joins
     * nothing and plays no mission, so that it stops at the main menu.
     */
    internal fun launchFor(launch: DayZLaunch, side: Side, profile: String, config: String): DayZLaunch = when (side) {
        Side.SERVER -> launch.copy(
            startServer = true,
            startClients = false,
            diag = true,
            serverProfile = profile,
            serverConfig = config,
            serverMission = null,
            port = CHECK_PORT,
        )
        Side.CLIENT -> launch.copy(
            startServer = false,
            startClients = true,
            diag = true,
            players = emptyList(),
            profiles = profile,
            connect = null,
            offlineMission = null,
            window = true,
            port = null,
        )
    }

    private fun report(project: Project, console: ConsoleView, side: Side, message: String, type: NotificationType) {
        val contentType = if (type == NotificationType.ERROR) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.SYSTEM_OUTPUT
        console.print("\n$message\n", contentType)
        NotificationGroupManager.getInstance().getNotificationGroup("Enforce Script")
            .createNotification(side.title, StringUtil.escapeXmlEntities(message), type)
            .notify(project)
    }
}

/** *Tools | DayZ | Check Scripts (Server)* and *(Client)*. */
abstract class CheckScriptsActionBase(private val side: CheckScripts.Side) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows && e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        CheckScripts.run(e.project ?: return, side)
    }
}

class CheckScriptsAction : CheckScriptsActionBase(CheckScripts.Side.SERVER)

class CheckClientScriptsAction : CheckScriptsActionBase(CheckScripts.Side.CLIENT)
