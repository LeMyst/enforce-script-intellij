package io.github.lemyst.enfusion.roots

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile

/**
 * Puts the configured script roots into the project's index and search scopes.
 *
 * This one extension point covers everything needed: files under these roots are indexed by
 * `FileBasedIndex` (so `enfusion.types` sees vanilla declarations) and are included in
 * `GlobalSearchScope.allScope`, which is what makes Ctrl+Click on a vanilla type resolve.
 *
 * Note both methods have default bodies on the base class — they are overridden, not implemented.
 */
class EnfusionLibraryRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
        librariesFor(project, TrustedProjects.isProjectTrusted(project))

    /** These roots live outside project content, so VFS events only fire if they are watched. */
    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        rootsToWatchFor(project, TrustedProjects.isProjectTrusted(project))

    /** Split out so tests, which run with the platform's trust check disabled, can pass [trusted]. */
    internal fun librariesFor(project: Project, trusted: Boolean): Collection<SyntheticLibrary> {
        // A game-data folder in the root list becomes a library of its own: it is the only root
        // that needs an exclude condition, because it is the whole 24 GB unpacked game.
        val (gameData, scriptRoots) = EnfusionRoots.splitGameData(resolveRoots(project, trusted))
        val libraries = ArrayList<SyntheticLibrary>(3)
        if (scriptRoots.isNotEmpty()) libraries += EnfusionScriptLibrary(scriptRoots)
        CfStubs.rootsFor(scriptRoots).forEach { libraries += CfStubsLibrary(it) }
        if (gameData.isNotEmpty()) libraries += EnfusionVanillaConfigLibrary(gameData)
        return libraries
    }

    internal fun rootsToWatchFor(project: Project, trusted: Boolean): Collection<VirtualFile> {
        // Game-data folders are deliberately *not* watched. Watching means the platform keeps
        // 113,000 files under file system notification for the sake of 207 configs that never
        // change unless the game updates.
        val scriptRoots = EnfusionRoots.splitGameData(resolveRoots(project, trusted)).second
        return scriptRoots + CfStubs.rootsFor(scriptRoots)
    }

    /**
     * Untrusted projects resolve nothing from their root list: see [EnfusionRoots.configuredRoots].
     * A `.pbo` root contributes its unpacked cache, and only once it exists: this can run inside a
     * read action, so unpacking is left to [EnfusionRoots.apply] and the startup refresh. It also
     * contributes the `.pbo` file itself, which is what the PBO browser opens from the tree.
     */
    private fun resolveRoots(project: Project, trusted: Boolean): List<VirtualFile> =
        EnfusionRoots.resolveForRead(EnfusionRoots.configuredRoots(project, trusted))
}
