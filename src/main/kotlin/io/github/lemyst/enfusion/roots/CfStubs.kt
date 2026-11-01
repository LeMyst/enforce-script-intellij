package io.github.lemyst.enfusion.roots

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.lemyst.enfusion.lang.EnfusionIcons
import java.io.File
import javax.swing.Icon

/**
 * Locates the bundled Community Framework stub (`resources/stubs/cf`).
 *
 * Extracted to a real file on disk rather than exposed straight from the plugin jar via
 * `VfsUtil.findFileByURL`. That was the first approach, and it looked right -- the file loaded, its
 * PSI parsed, `ScalarIndexExtension`s picked it up -- but `ProjectIndexableFilesFilterHealthCheck`
 * logged it as "indexable but not found in filter" alongside the platform's own bundled jar
 * resources, and references into it never resolved from a real project: `GlobalSearchScope.allScope`,
 * which every resolver in this plugin queries, silently excluded it. Extracting to disk and treating
 * it as an ordinary `LocalFileSystem` root sidesteps that gap entirely by reusing the exact path the
 * vanilla script root already takes.
 */
object CfStubs {

    private val FILES = listOf("config.cpp", "RPCManager.c")

    // Re-extracted on first access each process rather than skipped when already present: these are
    // two files under 2 KB, and skip-if-exists would leave a stale copy on disk after a plugin
    // update changed the stub content.
    val root: VirtualFile? by lazy { extract() }

    /**
     * The stub's root, unless [scriptRoots] already hold CF's real sources.
     *
     * Automatic rather than a setting: the stub only exists for the common case of CF arriving as a
     * packed Workshop PBO with no source on disk. Once the real source is a root, the stub would
     * sit beside it as a second, poorer declaration of the same names, and every Ctrl+Click on
     * `GetRPCManager` would ask which one to open.
     */
    fun rootsFor(scriptRoots: List<VirtualFile>): List<VirtualFile> {
        if (scriptRoots.any { holdsRealSources(it) }) return emptyList()
        return listOfNotNull(root)
    }

    /**
     * Where CF's own `RPCManager.c` sits, from the top of its repository down. A root may be any
     * level of that path: the repository, `JM`, `JM/CF`, or the `Scripts` folder, which is also
     * what an unpacked `P:\JM\CF\Scripts` looks like. Each level is tried.
     */
    private val REAL_SOURCE_PATH =
        listOf("JM", "CF", "Scripts", "3_Game", "CommunityFramework", "RPC", "RPCManager.c")

    /** True when [dir] is a CF checkout at any level, or directly contains one (a "mods" folder). */
    fun holdsRealSources(dir: VirtualFile): Boolean =
        isRealSourceLevel(dir) || dir.children.any { it.isDirectory && isRealSourceLevel(it) }

    private fun isRealSourceLevel(dir: VirtualFile): Boolean =
        (0..REAL_SOURCE_PATH.indexOf("3_Game")).any { start ->
            dir.findFileByRelativePath(REAL_SOURCE_PATH.drop(start).joinToString("/")) != null
        }

    private fun extract(): VirtualFile? {
        val dir = File(PathManager.getSystemPath(), "enfusion-cf-stubs")
        dir.mkdirs()
        for (name in FILES) {
            val resource = javaClass.classLoader.getResourceAsStream("stubs/cf/$name") ?: continue
            resource.use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(dir.path.replace('\\', '/'))
    }
}

/** The bundled CF stub, presented as its own library so it reads as "stub", not "real source". */
class CfStubsLibrary(private val root: VirtualFile) : SyntheticLibrary(), ItemPresentation {

    override fun getSourceRoots(): Collection<VirtualFile> = listOf(root)

    override fun getPresentableText(): String = "Community Framework Stubs"

    override fun getIcon(unused: Boolean): Icon = EnfusionIcons.FILE

    override fun equals(other: Any?): Boolean = other is CfStubsLibrary && other.root == root

    override fun hashCode(): Int = root.hashCode()
}
