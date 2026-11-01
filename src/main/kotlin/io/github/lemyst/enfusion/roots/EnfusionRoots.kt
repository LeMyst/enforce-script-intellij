package io.github.lemyst.enfusion.roots

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.lemyst.enfusion.lang.EnfusionFileTypeOverrider
import io.github.lemyst.enfusion.settings.EnfusionProjectSettings
import java.io.File

object EnfusionRoots {

    const val LIBRARY_NAME = "Enforce Script Sources"
    const val CF_STUBS_LIBRARY_NAME = "Community Framework Stubs"

    /**
     * Candidate vanilla script trees. `P:` is the work drive DayZ Tools mounts and extracts the game
     * data onto, so it is the one location common to every setup; anything else is added by hand
     * in the settings page.
     */
    private val VANILLA_CANDIDATES = listOf(
        "P:/scripts",
    )

    /**
     * Vanilla data directories, relative to the parent of the script root. Small and specific:
     * ~330 layouts and imagesets plus one 7,300-row stringtable, about 12 MB in total.
     */
    private val DATA_CANDIDATES = listOf(
        "gui/layouts",
        "gui/imagesets",
        "languagecore",
    )

    private val DEPENDENCY_CANDIDATES = listOf(
        "DayZ-Expansion-Scripts",
        "DayZ-CommunityOnlineTools",
        "DayZ-Dabs-Framework",
        "VPP-Admin-Tools",
    )

    fun currentRootFiles(project: Project): List<VirtualFile> {
        val fs = LocalFileSystem.getInstance()
        return EnfusionProjectSettings.getInstance(project).roots
            .mapNotNull { fs.findFileByPath(it.replace('\\', '/')) }
    }

    /**
     * Persists a new root list and tells the platform to rescan.
     *
     * `fireAdditionalLibraryChanged` is used rather than a full roots change: it is targeted at this
     * library only. The file-type overrider cache must be cleared at the same time — it memoises
     * "is this directory inside an Enfusion addon" per directory and never expires, so a vanilla
     * directory probed before the root was configured would stay unrecognised for the session.
     */
    fun apply(project: Project, newRoots: List<String>) {
        val settings = EnfusionProjectSettings.getInstance(project)
        val oldRoots = settings.roots
        if (oldRoots == newRoots) return

        // Persisting the paths is cheap and touches no VFS, so it happens on the caller's thread
        // and `isModified()` goes false immediately.
        settings.roots = newRoots
        EnfusionFileTypeOverrider.clearCache()

        // Resolving paths to VirtualFiles is a slow operation and the platform asserts against
        // those on the EDT -- and the settings dialog's apply() is on the EDT. So the VFS work and
        // the roots-changed write action both go to a pooled thread.
        //
        // The list feeds three libraries, so each is told only about its own files: a game-data
        // folder coming or going must not make the platform rescan the script roots, and the
        // reverse. The CF stub comes and goes with the list too, when a root with CF's real
        // sources is added or removed.
        ApplicationManager.getApplication().executeOnPooledThread {
            val (oldData, oldScripts) = splitGameData(toFiles(oldRoots))
            val (newData, newScripts) = splitGameData(toFiles(newRoots))
            val oldStub = CfStubs.rootsFor(oldScripts)
            val newStub = CfStubs.rootsFor(newScripts)
            WriteAction.runAndWait<RuntimeException> {
                if (oldScripts != newScripts) {
                    AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                        project, LIBRARY_NAME, oldScripts, newScripts, "enfusion",
                    )
                }
                if (oldData != newData) {
                    AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                        project, EnfusionVanillaConfigLibrary.LIBRARY_NAME, oldData, newData,
                        "enfusion-vanilla-configs",
                    )
                }
                if (oldStub != newStub) {
                    AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                        project, CF_STUBS_LIBRARY_NAME, oldStub, newStub, "enfusion-cf-stubs",
                    )
                }
            }
        }
    }

    /**
     * Splits configured roots into game-data folders (`P:\DZ`, indexed for its configs only by
     * [EnfusionVanillaConfigLibrary]) and everything else (indexed whole by [EnfusionScriptLibrary]).
     * Order is preserved on both sides, since it is the tiebreak between roots.
     */
    fun splitGameData(roots: List<VirtualFile>): Pair<List<VirtualFile>, List<VirtualFile>> =
        roots.partition { EnfusionVanillaConfigLibrary.isGameDataTree(it) }

    /**
     * Resolves configured paths, refreshing the VFS so roots outside the project are actually found.
     *
     * `findFileByPath` consults only IntelliJ's existing VFS snapshot and returns null for anything
     * it has not already seen. A vanilla root such as `P:\scripts` lives nowhere near the project,
     * so on a cold start it was absent from the snapshot and the root was silently dropped — the
     * library came up empty and no vanilla type resolved, with nothing logged. It then started
     * working by itself once an unrelated background refresh happened to reach the drive, which is
     * exactly the kind of intermittent behaviour that is miserable to diagnose.
     *
     * Must not run on the EDT, and must not run inside a read action.
     */
    private fun toFiles(paths: List<String>): List<VirtualFile> {
        val fs = LocalFileSystem.getInstance()
        return paths.mapNotNull { fs.refreshAndFindFileByPath(it.replace('\\', '/')) }
    }

    /**
     * Pulls every configured root into the VFS, returning any that could not be found.
     *
     * Runs on every project open, not only the first: roots persisted from an earlier session are
     * equally absent from a fresh VFS snapshot.
     */
    fun refreshAndReportMissing(project: Project): List<String> {
        val fs = LocalFileSystem.getInstance()
        return EnfusionProjectSettings.getInstance(project).roots.filter { path ->
            val file = fs.refreshAndFindFileByPath(path.replace('\\', '/'))
            file == null || !file.isDirectory
        }
    }

    /**
     * A vanilla script root, if one is present. Probing for the `3_game` subdirectory rather than
     * just the directory itself avoids matching an unrelated drive that happens to have `scripts`.
     */
    fun detectVanillaRoot(): String? =
        VANILLA_CANDIDATES.firstOrNull { File(it, "3_game").isDirectory }

    /**
     * The vanilla *data* trees that string literals navigate into: layouts, imagesets and the
     * localization table.
     *
     * Named individually rather than as their common parent, and that is not fussiness. `P:\gui`
     * holds 4.6 MB of layouts and 7.9 MB of imagesets next to 78 MB of textures and 53 MB of fonts;
     * rooting the library at `gui` would pull all of it into the index for no benefit.
     *
     * Derived from wherever the script root was found, so a script root configured outside `P:`
     * gets its own siblings rather than `P:`'s.
     */
    fun detectDataRoots(scriptRoot: String?): List<String> {
        val parent = scriptRoot?.let { File(it).parentFile } ?: return emptyList()
        return DATA_CANDIDATES
            .map { File(parent, it) }
            .filter { it.isDirectory }
            .map { it.path.replace('\\', '/') }
    }

    /** Dependency mod checkouts sitting beside the project, if any. */
    fun detectDependencyRoots(project: Project): List<String> {
        val parent = project.basePath?.let { File(it).parentFile } ?: return emptyList()
        return DEPENDENCY_CANDIDATES
            .map { File(parent, it) }
            .filter { it.isDirectory }
            .map { it.path.replace('\\', '/') }
    }
}
