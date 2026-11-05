package io.github.lemyst.enfusion.startup

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.StringUtil
import io.github.lemyst.enfusion.roots.EnfusionRoots
import io.github.lemyst.enfusion.settings.EnfusionProjectSettings
import java.io.File

/**
 * Finds the vanilla script tree on first open so navigation works without configuration.
 *
 * Without a vanilla root fewer than half of all type references resolve, which reads as a broken
 * feature rather than an unconfigured one. The vanilla root is therefore added silently and
 * announced; dependency mod sources are only *offered*, since adding four unrelated checkouts to
 * someone's index unasked is a different matter.
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

        // Every open, before anything else: roots persisted from an earlier session are absent from
        // a fresh VFS snapshot, and the library provider silently drops a root it cannot resolve.
        // Without this a vanilla root only takes effect once some unrelated refresh happens to
        // reach it, which presents as navigation that works intermittently.
        val missing = EnfusionRoots.refreshAndReportMissing(project)
        if (missing.isNotEmpty()) notify(project, missingRootsMessage(missing), emptyList())

        val settings = EnfusionProjectSettings.getInstance(project)
        if (settings.state.autoDetected) return
        settings.state.autoDetected = true

        val vanilla = EnfusionRoots.detectVanillaRoot()
        val dependencies = EnfusionRoots.detectDependencyRoots(project)
        // Layouts, imagesets and the stringtable travel with the scripts: they are what the string
        // literals in vanilla-derived code point at, and they are small enough to add unasked.
        val data = EnfusionRoots.detectDataRoots(vanilla)
        if (vanilla == null && dependencies.isEmpty()) return

        // No invokeLater: ProjectActivity already runs on a background coroutine, and hopping to
        // the EDT is what put the VFS lookups inside EnfusionRoots.apply on the UI thread.
        if (vanilla != null && !settings.roots.contains(vanilla)) {
            val added = (listOf(vanilla) + data).filterNot { it in settings.roots }
            EnfusionRoots.apply(project, settings.roots + added)
            notify(
                project,
                "Indexing vanilla DayZ scripts from $vanilla. " +
                    "Change this in Settings | Languages & Frameworks | Enforce Script.",
                dependencies,
            )
        } else if (dependencies.isNotEmpty()) {
            notify(project, "Found dependency mod sources next to this project.", dependencies)
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
