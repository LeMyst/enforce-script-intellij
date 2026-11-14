package io.github.lemyst.enfusion.startup

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import io.github.lemyst.enfusion.roots.EnfusionRoots
import io.github.lemyst.enfusion.settings.EnfusionProjectSettings
import io.github.lemyst.enfusion.tools.DayZInstallation
import io.github.lemyst.enfusion.tools.WorkDrive
import java.io.File

/**
 * Finds the vanilla script tree on first open so navigation works without configuration.
 *
 * Without a vanilla root fewer than half of all type references resolve, which reads as a broken
 * feature rather than an unconfigured one. The vanilla tree is therefore indexed from the work
 * drive unasked and announced; dependency mod sources are only *offered*, since adding four
 * unrelated checkouts to someone's index unasked is a different matter.
 */
class EnfusionAutoDetect : ProjectActivity {

    private companion object {
        /** Deep enough for Scripts/Client/config.cpp, shallow enough to stay cheap. */
        const val MARKER_SEARCH_DEPTH = 3
    }

    override suspend fun execute(project: Project) {
        // Never in tests: this mutates project settings and pulls a 2,811-file tree into the index,
        // which would pollute every fixture that only meant to index its own scratch files.
        if (ApplicationManager.getApplication().isUnitTestMode) return

        // Nothing at all in Safe Mode: the steps below resolve the root list, which an untrusted
        // project supplies itself (see EnfusionRoots.configuredRoots). Returning before
        // `autoDetected` is set means detection still runs once the project is opened trusted.
        if (!TrustedProjects.isProjectTrusted(project)) return

        // Only for projects that are actually DayZ addons. Without this, opening any unrelated
        // project would silently attach the vanilla script tree to it.
        if (!looksLikeAddon(project)) return

        // A background task with progress: the refresh below scans folder roots for PBOs and
        // unpacks new or updated ones, which takes about a minute for a thousand new PBOs.
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing Enforce Script roots", true) {
            override fun run(indicator: ProgressIndicator) = prepare(project, indicator)
        })
    }

    private fun prepare(project: Project, indicator: ProgressIndicator) {
        // The work drive's letter comes from DayZ Tools' settings unless it is set by hand, and the
        // library provider never detects (it runs in read actions). So detect before the roots are
        // refreshed below, and rescan if the letter turned out not to be the default.
        val installation = DayZInstallation.getInstance()
        if (installation.lastDetected == null) EnfusionRoots.updateNow(project, indicator) { installation.detect() }

        // The vanilla scripts, layouts, imagesets, stringtable and configs come from the work drive
        // rather than the root list, so they follow the letter set on this machine. Turned on once,
        // on the first open of an addon; after that the checkbox in the settings decides.
        val settings = EnfusionProjectSettings.getInstance(project)
        var workDrive: String? = null
        if (!settings.state.workDriveOffered) {
            EnfusionRoots.updateNow(project, indicator) {
                settings.state.workDriveOffered = true
                settings.state.indexWorkDrive = true
            }
            workDrive = EnfusionRoots.workDriveScripts(project)
        }

        // Every open: roots persisted from an earlier session are absent from
        // a fresh VFS snapshot, and the library provider silently drops a root it cannot resolve.
        // Without this a vanilla root only takes effect once some unrelated refresh happens to
        // reach it, which presents as navigation that works intermittently.
        val missing = EnfusionRoots.refreshAndReportMissing(project, indicator)
        notifyMissing(project, missing)

        val dependencies = if (settings.state.autoDetected) {
            emptyList()
        } else {
            settings.state.autoDetected = true
            EnfusionRoots.detectDependencyRoots(project).filterNot { it in settings.roots }
        }
        when {
            workDrive != null -> notify(
                project,
                "Indexing vanilla DayZ from the work drive (${FileUtil.toSystemDependentName(workDrive)} and its data). " +
                    "Change this in Settings | Languages & Frameworks | Enforce Script.",
                dependencies,
            )
            dependencies.isNotEmpty() -> notify(project, "Found dependency mod sources next to this project.", dependencies)
        }
    }

    /**
     * A DayZ addon is identified by a `config.cpp` (or the disabled form) near the project root.
     * That is the same marker [io.github.lemyst.enfusion.lang.EnfusionFileTypeOverrider] uses to
     * decide whether a `.c` file is EnfusionScript, so the two agree by construction.
     *
     * The walk is depth-limited because it runs on every project open, including large ones.
     */
    private fun looksLikeAddon(project: Project): Boolean {
        val base = project.basePath?.let { File(it) } ?: return false
        return containsAddonMarker(base, depth = 0)
    }

    private fun containsAddonMarker(dir: File, depth: Int): Boolean {
        if (depth > MARKER_SEARCH_DEPTH) return false
        val children = dir.listFiles() ?: return false
        for (child in children) {
            if (child.isFile && (child.name == "config.cpp" || child.name == "config.cpp.disabled")) {
                return true
            }
        }
        for (child in children) {
            if (child.isDirectory && !child.name.startsWith(".")) {
                if (containsAddonMarker(child, depth + 1)) return true
            }
        }
        return false
    }

    /**
     * The missing roots, and when the work drive is needed but not mounted, a *Mount* action: that
     * is the usual reason, and it is otherwise a silent loss of navigation into vanilla.
     *
     * The vanilla roots taken from the work drive are never in [missing] (an unmounted drive is
     * not a mistake in the list), so the drive is checked on its own. It is only reported then
     * when it can be mounted: a machine with no work drive at all would be told so on every open.
     */
    private fun notifyMissing(project: Project, missing: List<String>) {
        val paths = DayZInstallation.getInstance().paths(project)
        val workDrive = WorkDrive.getInstance()
        val letter = workDrive.letter(paths)
        val needed = EnfusionRoots.workDriveRoots(project).isNotEmpty() || missing.any { WorkDrive.isOn(it, letter) }
        val unmounted = needed && !File("$letter:\\").isDirectory
        if (missing.isEmpty() && !(unmounted && paths.workDriveSource != null)) return
        val message = if (missing.isEmpty()) {
            "The work drive $letter: is not mounted, so vanilla DayZ is not indexed."
        } else {
            missingRootsMessage(missing) + if (unmounted) " The work drive $letter: is not mounted." else ""
        }
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Enforce Script")
            .createNotification("Enforce Script", message, NotificationType.WARNING)
        if (unmounted && paths.workDriveSource != null) {
            notification.addAction(NotificationAction.createSimpleExpiring("Mount $letter:") { workDrive.mount(project) })
        }
        notification.notify(project)
    }

    private fun notify(project: Project, message: String, dependencies: List<String>) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Enforce Script")
            .createNotification("Enforce Script", message, NotificationType.INFORMATION)

        if (dependencies.isNotEmpty()) {
            val names = dependencies.map { it.substringAfterLast('/') }
            notification.addAction(
                NotificationAction.createSimpleExpiring("Also index ${names.joinToString(", ")}") {
                    val settings = EnfusionProjectSettings.getInstance(project)
                    EnfusionRoots.apply(project, settings.roots + dependencies.filterNot { it in settings.roots })
                },
            )
        }
        notification.notify(project)
    }
}

/**
 * Notification content is HTML, and these paths are the root list's text verbatim, so each one is
 * escaped: unescaped, a root such as `<img src=…>` became markup in the balloon, and an ordinary
 * path containing `&` rendered wrong.
 */
internal fun missingRootsMessage(missing: List<String>): String =
    "These Enforce Script roots could not be found and are being ignored: " +
        missing.joinToString(", ") { StringUtil.escapeXmlEntities(it) } + "."
