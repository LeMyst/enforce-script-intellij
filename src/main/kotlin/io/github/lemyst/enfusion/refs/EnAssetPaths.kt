package io.github.lemyst.enfusion.refs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

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
        val candidates = FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.allScope(project))
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

    fun segmentsOf(path: String): List<String> =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() }

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
