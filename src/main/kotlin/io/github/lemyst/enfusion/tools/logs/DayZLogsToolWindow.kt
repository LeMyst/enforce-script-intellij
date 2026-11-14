package io.github.lemyst.enfusion.tools.logs

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import io.github.lemyst.enfusion.refs.EnAssetPaths
import io.github.lemyst.enfusion.tools.DayZInstallation
import java.awt.BorderLayout
import java.awt.Font
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.JPanel

/**
 * The content of the *DayZ Logs* tool window: one tab per log kind (script, RPT, crash) of the
 * client profile and, when one is set, the server profile. Each tab follows the newest file of its
 * kind, switches to the next one when the game starts again, and turns source locations into links.
 *
 * The factory itself is Java ([DayZLogsToolWindowFactory]): a Kotlin `ToolWindowFactory` gets
 * compiler bridges for the interface's deprecated and internal default methods, which the Plugin
 * Verifier reports.
 */
object DayZLogsContent {

    @JvmStatic
    fun fill(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        ApplicationManager.getApplication().executeOnPooledThread {
            val paths = DayZInstallation.getInstance().paths(project)
            val profiles = listOfNotNull(paths.clientProfile?.let { "Client" to it }, paths.serverProfile?.let { "Server" to it })
            ApplicationManager.getApplication().invokeLater({
                if (profiles.isEmpty()) {
                    val label = JBLabel("No profile folder is known. Set one in Settings | Languages & Frameworks | Enforce Script | DayZ Installation.")
                    label.border = JBUI.Borders.empty(12)
                    toolWindow.contentManager.addContent(factory.createContent(label, "", false))
                    return@invokeLater
                }
                for ((side, folder) in profiles) {
                    for (kind in listOf(DayZLogLines.Kind.SCRIPT, DayZLogLines.Kind.RPT, DayZLogLines.Kind.CRASH)) {
                        val panel = LogPanel(project, folder, kind)
                        val title = if (profiles.size > 1) "$side ${kind.title}" else kind.title
                        val content = factory.createContent(panel, title, false)
                        content.setDisposer(panel)
                        toolWindow.contentManager.addContent(content)
                    }
                }
            }, { project.isDisposed })
        }
    }
}

/** One followed log: a read-only console fed by a [DayZLogTail] polled once a second. */
private class LogPanel(private val project: Project, folder: Path, kind: DayZLogLines.Kind) :
    JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val tail = DayZLogTail(folder, kind)
    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
    private val header = JBLabel()

    /** Every line of the current file, so a filter change can show it again. */
    private val lines = ArrayList<String>()
    private var onlyProblems = false
    private var collapse = true

    init {
        Disposer.register(this, console)
        console.addMessageFilter(LocationFilter(project))
        val actions = DefaultActionGroup(
            object : DumbAwareToggleAction("Errors and Warnings Only", "Show only SCRIPT (E) and (W) lines and the reports that follow them", com.intellij.icons.AllIcons.General.Filter) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = onlyProblems
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    onlyProblems = state
                    reprint()
                }
            },
            object : DumbAwareToggleAction("Collapse Repeated Lines", "Show a line repeated back to back once, with a count", com.intellij.icons.AllIcons.Actions.Collapseall) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = collapse
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    collapse = state
                    reprint()
                }
            },
            object : DumbAwareAction("Open Older Run…", "Show the log of an earlier run instead of the newest", com.intellij.icons.AllIcons.Vcs.History) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) = chooseRun()
            },
            object : DumbAwareAction("Follow Newest", "Go back to following the newest log", com.intellij.icons.AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    tail.pinned = null
                }
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("EnfusionDayZLogs", actions, false)
        toolbar.targetComponent = this
        header.border = JBUI.Borders.empty(2, 6)
        add(header, BorderLayout.NORTH)
        add(toolbar.component, BorderLayout.WEST)
        add(console.component, BorderLayout.CENTER)
        header.text = "Waiting for ${kind.prefix}*${kind.extension} in $folder"

        val future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ poll() }, 0, 1, TimeUnit.SECONDS)
        Disposer.register(this) { future.cancel(false) }
    }

    private fun poll() {
        val chunk = tail.poll() ?: return
        ApplicationManager.getApplication().invokeLater({
            if (chunk.restarted) {
                lines.clear()
                console.clear()
                header.text = chunk.file.toString()
            }
            val start = lines.size
            lines += chunk.lines
            print(start)
        }, { project.isDisposed })
    }

    private fun reprint() {
        console.clear()
        print(0)
    }

    /** Prints [lines] from [start], applying the filters. */
    private fun print(start: Int) {
        val shown = LogView.render(lines, start, onlyProblems, collapse)
        for (line in shown) console.print(line.text + "\n", contentType(line.severity))
    }

    private fun contentType(severity: DayZLogLines.Severity): ConsoleViewContentType = when (severity) {
        DayZLogLines.Severity.ERROR -> ConsoleViewContentType.ERROR_OUTPUT
        DayZLogLines.Severity.WARNING -> WARNING_OUTPUT
        DayZLogLines.Severity.SCRIPT -> ConsoleViewContentType.NORMAL_OUTPUT
        DayZLogLines.Severity.OTHER -> ConsoleViewContentType.NORMAL_OUTPUT
    }

    private fun chooseRun() {
        val runs = tail.runs()
        if (runs.isEmpty()) return
        ApplicationManager.getApplication().invokeLater {
            JBPopupFactory.getInstance().createPopupChooserBuilder(runs.map { it.fileName.toString() })
                .setTitle("Open Older Run")
                .setItemChosenCallback { name -> tail.pinned = runs.firstOrNull { it.fileName.toString() == name } }
                .createPopup()
                .showInCenterOf(this)
        }
    }

    override fun dispose() = Unit

    companion object {
        val WARNING_OUTPUT = ConsoleViewContentType(
            "ENFUSION_LOG_WARNING",
            TextAttributes(JBColor(0x9A6700, 0xD9A343), null, null, null, Font.PLAIN),
        )
    }
}

/**
 * Turns `@"path.c,17"`, `path.c(17):` and `path.c:17 Function` into links. The path is as the game
 * knows it, from a PBO prefix or the work drive, so it is matched from the right against the
 * project and its roots, like an asset path in script.
 */
private class LocationFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        if (DumbService.isDumb(project)) return null
        val locations = DayZLogLines.locations(line)
        if (locations.isEmpty()) return null
        val offset = entireLength - line.length
        val items = locations.mapNotNull { location ->
            val file = ApplicationManager.getApplication().runReadAction(Computable {
                EnAssetPaths.findBySuffix(project, location.path).firstOrNull()
            }) ?: return@mapNotNull null
            val info: HyperlinkInfo = OpenFileHyperlinkInfo(project, file, (location.line - 1).coerceAtLeast(0))
            Filter.ResultItem(offset + location.start, offset + location.end, info)
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }
}
