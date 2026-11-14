package io.github.lemyst.enfusion.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo

/**
 * Detects the DayZ installs once per session, in the background, so that menus which must not
 * detect while they update (*Tools | DayZ Tools*, *Open in …*) know where DayZ Tools is without the
 * settings page having been opened first.
 */
class DayZDetectionStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!SystemInfo.isWindows || ApplicationManager.getApplication().isUnitTestMode) return
        val installation = DayZInstallation.getInstance()
        if (installation.lastDetected == null) installation.detect()
    }
}
