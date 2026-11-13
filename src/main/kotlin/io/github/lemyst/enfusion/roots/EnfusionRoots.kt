package io.github.lemyst.enfusion.roots

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.lemyst.enfusion.lang.EnfusionFileTypeOverrider
import io.github.lemyst.enfusion.pbo.PboCache
import io.github.lemyst.enfusion.pbo.PboFolders
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

    /**
     * The configured roots, or none while the project is untrusted.
     *
     * The list is stored in `.idea/enfusion.xml`, which a cloned repository can ship, so until the
     * user trusts the project it is the repository's word, not theirs. Resolving a root touches the
     * disk, and a UNC root (`\\host\share`) makes Windows contact that host over SMB, which can send
     * the user's NTLM hash to it. Safe Mode promises that opening a project does nothing of the kind,
     * so nothing from the list is resolved or indexed until the project is trusted.
     *
     * [trusted] is a parameter only because tests run with the platform's trust check disabled.
     */
    fun configuredRoots(
        project: Project,
        trusted: Boolean = TrustedProjects.isProjectTrusted(project),
    ): List<String> = if (trusted) EnfusionProjectSettings.getInstance(project).roots else emptyList()

    fun currentRootFiles(project: Project): List<VirtualFile> {
        val fs = LocalFileSystem.getInstance()
        return configuredRoots(project).mapNotNull { fs.findFileByPath(it.replace('\\', '/')) }
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

        // The old list may be the repository's (see configuredRoots), and resolving it below would
        // touch every path in it. While untrusted the provider offers no roots anyway, so there is
        // nothing to rescan: the saved list takes effect when the project is next opened trusted.
        if (!TrustedProjects.isProjectTrusted(project)) return

        // Resolving paths to VirtualFiles is a slow operation and the platform asserts against
        // those on the EDT -- and the settings dialog's apply() is on the EDT. So the VFS work and
        // the roots-changed write action both go to a background task, and so do scanning folders
        // and unpacking PBOs, which take about a minute the first time a folder holds a thousand.
        //
        // The old list resolves through the existing PBO caches and folder scans only. A `.pbo` it
        // names has nothing to unpack: it was either unpacked already or never resolved.
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing Enforce Script roots", true) {
            override fun run(indicator: ProgressIndicator) {
                val old = resolveAll(oldRoots, unpack = false, indicator = null).files
                fireChanged(project, old, resolveAll(newRoots, unpack = true, indicator).files)
            }
        })
    }

    /**
     * Tells the platform what changed between two resolved root lists, library by library.
     *
     * The list feeds three libraries, so each is told only about its own files: a game-data
     * folder coming or going must not make the platform rescan the script roots, and the
     * reverse. The CF stub comes and goes with the list too, when a root with CF's real sources
     * is added or removed.
     */
    private fun fireChanged(project: Project, oldRoots: List<VirtualFile>, newRoots: List<VirtualFile>) {
        val (oldData, oldScripts) = splitGameData(oldRoots)
        val (newData, newScripts) = splitGameData(newRoots)
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
     * A `.pbo` path resolves to its cache directory (see [PboCache]), and a folder is followed by
     * the PBOs under it (see [expand]). With [unpack] folders are scanned again and PBOs unpacked
     * when missing or stale; without it only existing scans and caches are used.
     *
     * [Resolved.unresolved] lists the configured paths that came to nothing. A PBO found by a
     * folder scan is never in it: one that cannot be read is logged and skipped, since the user
     * never named it.
     *
     * Must not run on the EDT, and must not run inside a read action.
     */
    private fun resolveAll(paths: List<String>, unpack: Boolean, indicator: ProgressIndicator?): Resolved {
        val expanded = expand(paths, scan = unpack)
        val files = ArrayList<VirtualFile>(expanded.size)
        val unresolved = ArrayList<String>()
        val configured = paths.toHashSet()
        indicator?.isIndeterminate = false
        for ((i, path) in expanded.withIndex()) {
            indicator?.checkCanceled()
            indicator?.text2 = path
            indicator?.fraction = i.toDouble() / expanded.size
            val file = resolveForWrite(path, unpack)?.first
            if (file != null && file.isDirectory) {
                files += file
            } else if (path in configured) {
                unresolved += path
            }
            if (path in configured) packedFile(path, refresh = true)?.let { files += it }
        }
        return Resolved(files, unresolved)
    }

    /**
     * The `.pbo` file itself, for a `.pbo` named in the root list, so that it shows under
     * *External Libraries* next to its cache and opens in the PBO browser from there. Without it
     * the cache is all the tree shows of a PBO root, and textures, models and every other entry
     * the cache leaves out could only be reached through *File | Open*. A PBO found by a folder
     * scan needs nothing: it already shows under its folder.
     *
     * Being a library root costs nothing to index: the file is binary, so only its name is.
     */
    private fun packedFile(path: String, refresh: Boolean): VirtualFile? {
        if (!PboCache.isPbo(path)) return null
        val fs = LocalFileSystem.getInstance()
        val normalized = path.replace('\\', '/')
        val file = if (refresh) fs.refreshAndFindFileByPath(normalized) else fs.findFileByPath(normalized)
        return file?.takeIf { !it.isDirectory }
    }

    private class Resolved(val files: List<VirtualFile>, val unresolved: List<String>)

    /**
     * The configured paths with each folder followed by the `.pbo` files under it, in list order,
     * each path once. The folder itself stays a root too: a mod checkout can hold both its sources
     * and a packed build, and its sources then come first in the tiebreak.
     *
     * With [scan] folders are walked again ([PboFolders.scanAndRecord]); without it the last scan's
     * record is used, which walks nothing and is safe in a read action. The unpacked game data
     * (`P:\DZ`) is never scanned: it holds no PBOs, and walking it means 113,000 files.
     */
    private fun expand(paths: List<String>, scan: Boolean): List<String> {
        val out = ArrayList<String>(paths.size)
        val seen = HashSet<String>()
        fun add(path: String) {
            if (seen.add(path.replace('\\', '/').lowercase())) out += path
        }
        for (path in paths) {
            add(path)
            if (PboCache.isPbo(path)) continue
            val pbos = when {
                !scan -> PboFolders.recordedPbos(path)
                isGameDataPath(path) -> emptyList()
                else -> PboFolders.scanAndRecord(path)
            }
            pbos.forEach(::add)
        }
        return out
    }

    /** [EnfusionVanillaConfigLibrary.isGameDataTree] for a path, without the VFS. */
    fun isGameDataPath(path: String): Boolean {
        val dir = File(path)
        return dir.name.equals("DZ", ignoreCase = true) &&
            File(dir, "gear").isDirectory && File(dir, "characters").isDirectory
    }

    /** The root as a refreshed VirtualFile, and whether a PBO behind it was just unpacked. */
    private fun resolveForWrite(path: String, unpack: Boolean): Pair<VirtualFile, Boolean>? {
        val fs = LocalFileSystem.getInstance()
        if (!PboCache.isPbo(path)) return fs.refreshAndFindFileByPath(path.replace('\\', '/'))?.let { it to false }
        val (dir, extracted) = if (unpack) {
            PboCache.ensure(path)?.let { it.dir to it.extracted } ?: return null
        } else {
            (PboCache.existingDir(path) ?: return null) to false
        }
        val file = fs.refreshAndFindFileByPath(dir.toString().replace('\\', '/')) ?: return null
        // A re-unpacked PBO replaced the whole tree, which the VFS snapshot still remembers as it was.
        if (extracted) VfsUtil.markDirtyAndRefresh(false, true, true, file)
        return file to extracted
    }

    /**
     * Pulls every configured root into the VFS, returning any that could not be found.
     *
     * Runs on every project open, not only the first: roots persisted from an earlier session are
     * equally absent from a fresh VFS snapshot. It is also where folders are scanned again for
     * newly installed PBOs, where `.pbo` roots are unpacked or brought up to date after a Workshop
     * update, and where the platform is told about any cache that appeared, since the library
     * provider never scans or unpacks anything itself.
     */
    fun refreshAndReportMissing(project: Project, indicator: ProgressIndicator? = null): List<String> {
        val roots = configuredRoots(project)
        val before = resolveAll(roots, unpack = false, indicator = null).files
        val after = resolveAll(roots, unpack = true, indicator)
        if (before != after.files) fireChanged(project, before, after.files)
        return after.unresolved
    }

    /**
     * The roots as the library provider sees them: no VFS refresh, a `.pbo` stands for its cache
     * directory only if one exists (and for itself, see [packedFile]), and a folder brings the PBOs
     * its last scan found. Safe in a read action.
     */
    fun resolveForRead(paths: List<String>): List<VirtualFile> {
        val fs = LocalFileSystem.getInstance()
        val configured = paths.toHashSet()
        val out = ArrayList<VirtualFile>()
        for (path in expand(paths, scan = false)) {
            val target = if (PboCache.isPbo(path)) PboCache.existingDir(path)?.toString() else path
            target?.let { fs.findFileByPath(it.replace('\\', '/')) }?.takeIf { it.isDirectory }?.let { out += it }
            if (path in configured) packedFile(path, refresh = false)?.let { out += it }
        }
        return out
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
