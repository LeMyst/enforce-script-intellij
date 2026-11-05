package io.github.lemyst.enfusion.roots

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.roots.SyntheticLibrary.ExcludeFileCondition
import com.intellij.openapi.vfs.VirtualFile
import io.github.lemyst.enfusion.lang.EnfusionIcons
import javax.swing.Icon

/**
 * The vanilla configs inside the unpacked game data (`P:\DZ`), and **only** those.
 *
 * `P:\DZ` is the entire unpacked game: 24 GB across roughly 113,000 files — 39k `.rvmat`, 30k `.paa`,
 * 27k `.ogg` — of which exactly 207 are `config.cpp`, about 19 MB. Indexing it the way an ordinary
 * script root is indexed would hand the indexer three orders of magnitude more than it needs, which is
 * why a game-data folder in the root list is split off into this library and its exclude condition.
 *
 * The condition keeps the *index* small. It cannot make the directory walk cheap — the platform still
 * has to traverse the tree once to discover the configs — and that walk is why the folder is never
 * added automatically, and why the settings page asks before adding it.
 */
class EnfusionVanillaConfigLibrary(private val roots: List<VirtualFile>) :
    SyntheticLibrary(null, ONLY_CONFIGS), ItemPresentation {

    override fun getSourceRoots(): Collection<VirtualFile> = roots

    override fun getPresentableText(): String = LIBRARY_NAME

    override fun getIcon(unused: Boolean): Icon = EnfusionIcons.FILE

    override fun equals(other: Any?): Boolean = other is EnfusionVanillaConfigLibrary && other.roots == roots

    override fun hashCode(): Int = roots.hashCode()

    companion object {
        const val LIBRARY_NAME = "Vanilla Configs"

        private val CONFIG_NAMES = listOf("config.cpp", "mod.cpp")

        /**
         * Directories pass so the walk can continue; every file but a config is dropped.
         *
         * Passed to the constructor rather than overriding `getExcludeFileCondition()`, which is
         * deprecated: this form sees only the name and the directory flag, never a `VirtualFile`,
         * which is all this condition needs.
         */
        private val ONLY_CONFIGS = ExcludeFileCondition { isDir, name, _, _, _ ->
            !isDir && CONFIG_NAMES.none { name.equals(it, ignoreCase = true) }
        }

        /**
         * Whether [dir] is the unpacked game data rather than a script or mod root.
         *
         * Matched on the name *and* the content. The name is reliable because the game's own paths
         * start with `dz\` (`dz\gear\…`), so the extracted tree is called `DZ` wherever it lands.
         * The two subdirectories rule out an unrelated folder of that name. Requiring both matters
         * because a false match is costly: a mod root taken for game data would have its scripts
         * excluded and keep only its configs.
         */
        fun isGameDataTree(dir: VirtualFile): Boolean =
            dir.isDirectory &&
                dir.name.equals("DZ", ignoreCase = true) &&
                MARKER_DIRS.all { dir.findChild(it)?.isDirectory == true }

        private val MARKER_DIRS = listOf("gear", "characters")
    }
}
