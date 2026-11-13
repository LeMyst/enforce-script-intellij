package io.github.lemyst.enfusion.refs

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.lemyst.enfusion.roots.EnfusionLibraryRootsProvider

/**
 * Turning a packaged path such as `BattleRoyale/GUI/layouts/hud/br_hud.layout` into the file
 * on disk.
 *
 * A **longest-suffix match**, deliberately, rather than a prefix rewrite. The codebase writes paths
 * three ways — relative to the PBO prefix, relative to an addon root, and relative to the work drive
 * — and matching from the right handles all three with no map of prefixes to directories. That map
 * lives in `config.cpp` and `$PBOPREFIX$` files this plugin does not read, so avoiding the need for
 * it is what keeps the whole feature cheap.
 */
object EnAssetPaths {

    /**
     * Every file whose path ends with the most segments of [path], ordered nearest-first.
     *
     * Ties are kept rather than broken arbitrarily: two addons shipping a `row.layout` is a real
     * ambiguity and the picker should say so.
     */
    fun findBySuffix(project: Project, path: String): List<VirtualFile> {
        val wanted = segmentsOf(path)
        val fileName = wanted.lastOrNull() ?: return emptyList()

        // allScope, not a module scope: a SyntheticLibrary has no OrderEntry, so the vanilla
        // `gui/layouts` root would be invisible from mod code.
        val scope = GlobalSearchScope.allScope(project)
        val candidates = LinkedHashSet<VirtualFile>()
        candidates += FilenameIndex.getVirtualFilesByName(fileName, scope)
        candidates += underRoots(project, wanted, scope)
        if (candidates.isEmpty()) return emptyList()

        var best = 0
        val scored = ArrayList<Pair<VirtualFile, Int>>(candidates.size)
        for (candidate in candidates) {
            val score = commonSuffixLength(segmentsOf(candidate.path), wanted)
            if (score == 0) continue
            if (score > best) best = score
            scored += candidate to score
        }
        return scored.filter { it.second == best }.map { it.first }.sortedBy { it.path }
    }

    /**
     * The same path looked up directly under each project and library root, for the files the name
     * index never holds.
     *
     * The platform leaves every file larger than its IntelliSense limit (2.5 MB by default) out of
     * [FilenameIndex], and textures cross that line all the time: the 1024² BGRA atlas of a typical
     * mod imageset is 5.5 MB. Without this, a path to one resolves to nothing at all.
     *
     * Every suffix of the path is tried against every root, longest first, because the path may be
     * written relative to the PBO prefix (one level above the checkout) or to the root itself.
     * That is a handful of VFS lookups per path, and resolution is cached by the caller.
     */
    private fun underRoots(project: Project, wanted: List<String>, scope: GlobalSearchScope): List<VirtualFile> {
        val roots = ProjectRootManager.getInstance(project).contentRoots.toList() +
            EnfusionLibraryRootsProvider().getAdditionalProjectLibraries(project).flatMap { it.sourceRoots }
        val found = ArrayList<VirtualFile>()
        for (root in roots) {
            for (take in wanted.size downTo 1) {
                val file = root.findFileByRelativePath(wanted.takeLast(take).joinToString("/")) ?: continue
                // allScope is what drops the excluded copies, such as `.claude/worktrees` checkouts.
                if (!file.isDirectory && scope.contains(file)) {
                    found += file
                    break
                }
            }
        }
        return found
    }

    /**
     * The path's folders and file name. A leading resource GUID is dropped: imagesets and layouts
     * write `{0123456789ABCDEF}MyMod/GUI/…`, and the GUID is an engine resource ID
     * glued to the first folder, not part of any name on disk.
     */
    fun segmentsOf(path: String): List<String> =
        path.replace('\\', '/').replaceFirst(GUID_PREFIX, "").split('/').filter { it.isNotEmpty() }

    private val GUID_PREFIX = Regex("""^\{[0-9A-Fa-f]{16}}""")

    private fun commonSuffixLength(a: List<String>, b: List<String>): Int {
        var matched = 0
        while (matched < a.size && matched < b.size) {
            // Case-insensitive: vanilla writes `gui/layouts/…` where the directory is `GUI`.
            if (!a[a.size - 1 - matched].equals(b[b.size - 1 - matched], ignoreCase = true)) break
            matched++
        }
        return matched
    }
}
