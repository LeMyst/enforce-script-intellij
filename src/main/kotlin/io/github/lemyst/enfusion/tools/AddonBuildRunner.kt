package io.github.lemyst.enfusion.tools

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.lemyst.enfusion.tools.detect.AddonBuild
import io.github.lemyst.enfusion.tools.detect.AddonBuilderCommand
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs Addon Builder for one or more addons, one after another, with the output in a *Run* tab.
 *
 * The result of each build is read from its output (see [AddonBuilderCommand]), and a signed build
 * is checked for its `.bisign` and for the `.bikey` a server needs in the mod's `Keys` folder.
 * Cancelling the background task stops the running Addon Builder and the builds after it.
 */
object AddonBuildRunner {

    /** Called on the EDT, from an action. */
    fun run(project: Project, builds: List<AddonBuild>) {
        if (builds.isEmpty()) return
        val title = if (builds.size == 1) "Build ${builds[0].pboName}" else "Build ${builds.size} PBOs"
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.addMessageFilter(ConfigErrorFilter(project))
        val descriptor = RunContentDescriptor(console, null, console.component, title)
        Disposer.register(descriptor, console)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                // Detection may run here the first time, so not on the EDT.
                val toolsBin = DayZInstallation.getInstance().paths(project).toolsBin
                if (toolsBin == null || !Files.isRegularFile(toolsBin.resolve(AddonBuilderCommand.EXE))) {
                    val message = "Addon Builder was not found. Set the DayZ Tools folder in Settings | Languages & Frameworks | Enforce Script | DayZ Installation."
                    console.print(message + "\n", ConsoleViewContentType.ERROR_OUTPUT)
                    notify(project, message, NotificationType.ERROR)
                    return
                }
                buildAll(project, toolsBin, builds, console, indicator)
            }
        })
    }

    private fun buildAll(project: Project, toolsBin: Path, builds: List<AddonBuild>, console: ConsoleView, indicator: ProgressIndicator) {
        val failed = ArrayList<String>()
        val warnings = ArrayList<String>()
        for ((index, build) in builds.withIndex()) {
            if (indicator.isCanceled) break
            indicator.text = "Building ${build.pboName}"
            indicator.fraction = index.toDouble() / builds.size
            Files.createDirectories(build.destination)
            val command = AddonBuilderCommand.commandLine(toolsBin, build)
            console.print("> ${command.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            val output = StringBuilder()
            val handler = OSProcessHandler(GeneralCommandLine(command).withWorkDirectory(build.source.toFile()))
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                }
            })
            console.attachToProcess(handler)
            handler.startNotify()
            while (!handler.waitFor(250)) {
                if (indicator.isCanceled) {
                    handler.destroyProcess()
                    break
                }
            }
            val result = AddonBuilderCommand.result(output.toString())
            val ok = result == AddonBuilderCommand.Result.SUCCEEDED && Files.isRegularFile(build.pbo)
            if (ok) {
                console.print("✓ ${build.pbo}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                build.signKey?.let { key -> warnings += signingProblems(build, key) }
                LocalFileSystem.getInstance().refreshNioFiles(listOf(build.pbo))
            } else {
                failed += build.pboName
                console.print("✗ ${build.pboName} failed\n\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
        }
        warnings.forEach { console.print("! $it\n", ConsoleViewContentType.ERROR_OUTPUT) }
        when {
            indicator.isCanceled -> notify(project, "PBO build cancelled.", NotificationType.WARNING)
            failed.isNotEmpty() -> notify(project, "Build failed: ${failed.joinToString(", ")}. See the Run tool window.", NotificationType.ERROR)
            warnings.isNotEmpty() -> notify(project, warnings.joinToString(" "), NotificationType.WARNING)
            else -> notify(project, if (builds.size == 1) "Built ${builds[0].pboName}." else "Built ${builds.size} PBOs.", NotificationType.INFORMATION)
        }
    }

    private fun signingProblems(build: AddonBuild, key: Path): List<String> {
        val problems = ArrayList<String>()
        val signature = AddonBuilderCommand.signatureFor(build, key)
        if (!Files.isRegularFile(signature)) problems += "No signature ${signature.fileName} next to ${build.pboName}."
        val publicKey = AddonBuilderCommand.publicKeyFor(build, key)
        if (publicKey != null && !Files.isRegularFile(publicKey)) {
            problems += "${publicKey.fileName} is not in ${publicKey.parent}: servers need it to accept the signed PBO."
        }
        return problems
    }

    /** `File <path>, line <n>:` from CfgConvert, as a link to that line. */
    private class ConfigErrorFilter(private val project: Project) : Filter {
        override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
            val match = AddonBuilderCommand.CONFIG_ERROR.find(line) ?: return null
            val file = LocalFileSystem.getInstance().findFileByPath(match.groupValues[1].replace('\\', '/')) ?: return null
            val lineNumber = (match.groupValues[2].toIntOrNull() ?: 1) - 1
            val start = entireLength - line.length + match.groups[1]!!.range.first
            val end = entireLength - line.length + match.range.last
            return Filter.Result(start, end, OpenFileHyperlinkInfo(project, file, lineNumber.coerceAtLeast(0)))
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Enforce Script")
            .createNotification("Build PBO", StringUtil.escapeXmlEntities(message), type)
            .notify(project)
    }
}
