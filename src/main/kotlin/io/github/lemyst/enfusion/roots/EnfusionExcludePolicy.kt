package io.github.lemyst.enfusion.roots

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.VfsUtilCore

/**
 * Excludes `<project>/.claude/worktrees` from indexing.
 *
 * That directory holds full duplicate checkouts of the same sources, so without this every class in
 * the project would be indexed twice and Ctrl+Click would offer two identical candidates for names
 * that are declared exactly once. Applied automatically rather than left to the user to configure.
 */
class EnfusionExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {

    override fun getExcludeUrlsForProject(): Array<String> {
        val base = project.basePath ?: return emptyArray()
        // VFS URLs, not filesystem paths.
        return arrayOf(VfsUtilCore.pathToUrl("$base/.claude/worktrees"))
    }
}
