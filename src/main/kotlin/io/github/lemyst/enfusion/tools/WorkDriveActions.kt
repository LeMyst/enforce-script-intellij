package io.github.lemyst.enfusion.tools

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import java.awt.event.MouseEvent

/** *Tools | DayZ | Mount Work Drive*. */
class MountWorkDriveAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows
    }

    override fun actionPerformed(e: AnActionEvent) = WorkDrive.getInstance().mount(e.project)
}

/** *Tools | DayZ | Unmount Work Drive*. */
class UnmountWorkDriveAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows
    }

    override fun actionPerformed(e: AnActionEvent) = WorkDrive.getInstance().unmount(e.project)
}

/**
 * `P: → F:\Work Drive` or `P: not mounted` in the status bar, with mount and unmount in its popup.
 * Shown on Windows only, and hidden once detection has found no work-drive folder, so that a
 * machine without DayZ Tools does not keep seeing it.
 */
class WorkDriveWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = ID

    override fun getDisplayName(): String = "DayZ Work Drive"

    override fun isAvailable(project: Project): Boolean {
        if (!SystemInfo.isWindows) return false
        val installation = DayZInstallation.getInstance()
        // Before detection has run in this session nothing is known yet; the widget shows and
        // fills in once install() has detected. It stays hidden on later projects of a session
        // that found no work-drive folder.
        val detected = installation.lastDetected ?: return true
        return installation.configured.workDriveSource != null || detected.workDriveSource != null
    }

    override fun createWidget(project: Project): StatusBarWidget = WorkDriveStatusWidget(project)

    companion object {
        const val ID = "EnfusionWorkDrive"
    }
}

/**
 * What the status-bar widget shows and does. [WorkDriveStatusWidget] is Java because a Kotlin
 * class implementing `StatusBarWidget` gets a compiler bridge for its deprecated
 * `getPresentation(PlatformType)`, which the Plugin Verifier reports as deprecated API usage.
 */
object WorkDriveWidgetSupport {
    private const val MOUNT = "Mount Work Drive"
    private const val UNMOUNT = "Unmount Work Drive"
    private const val REFRESH = "Refresh"

    @JvmStatic
    fun refresh(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            WorkDrive.getInstance().refresh(DayZInstallation.getInstance().paths(project))
        }
    }

    @JvmStatic
    fun text(): String {
        val status = WorkDrive.getInstance().status ?: return "Work drive…"
        return when {
            status.mounted -> "${status.letter}: → ${status.source}"
            status.present -> "${status.letter}: (disk)"
            else -> "${status.letter}: not mounted"
        }
    }

    @JvmStatic
    fun showPopup(project: Project, event: MouseEvent) {
        val status = WorkDrive.getInstance().status
        val choices = if (status?.mounted == true) listOf(UNMOUNT, REFRESH) else listOf(MOUNT, REFRESH)
        val step = object : BaseListPopupStep<String>("Work Drive", choices) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                when (selectedValue) {
                    MOUNT -> WorkDrive.getInstance().mount(project)
                    UNMOUNT -> WorkDrive.getInstance().unmount(project)
                    else -> refresh(project)
                }
                return FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(event.component)
    }
}
