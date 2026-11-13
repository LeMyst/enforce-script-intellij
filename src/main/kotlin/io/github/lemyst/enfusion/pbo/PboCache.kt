package io.github.lemyst.enfusion.pbo

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Where configured `.pbo` roots are unpacked, by [PboExtractor], for indexing.
 *
 * On disk rather than served from the PBO through a virtual file system, for two reasons.
 * - The platform's archive file system cannot be extended without internal API:
 *   `ArchiveHandler.EntryInfo` is `@ApiStatus.Internal`.
 * - Files outside `LocalFileSystem` fell out of `GlobalSearchScope.allScope`. That is what
 *   [io.github.lemyst.enfusion.roots.CfStubs] ran into, and why it extracts its stub too.
 *
 * One directory per PBO, under the IDE's system directory, named after the PBO and a hash of its
 * path. A stamp file records the source's size and modification time, so a Workshop update is
 * picked up the next time roots are resolved (project open or settings change), and an unchanged
 * PBO is never read twice.
 */
object PboCache {

    /** Bump when [PboExtractor] changes what it writes, so caches from an older plugin are rebuilt. */
    private const val FORMAT = 1
    private const val STAMP = ".enfusion-pbo"

    private val log = logger<PboCache>()

    val root: Path by lazy { Path.of(PathManager.getSystemPath(), "enfusion-pbo") }

    private val rootPath: String by lazy { root.toString().replace('\\', '/').trimEnd('/') + "/" }

    fun isPbo(path: String): Boolean = path.endsWith(".pbo", ignoreCase = true)

    class Ensured(val dir: Path, val extracted: Boolean, val warnings: List<String>)

    /** The cache directory for [pboPath] if one was ever written, current or not. Touches no PBO. */
    fun existingDir(pboPath: String): Path? =
        dirFor(pboPath).takeIf { Files.isRegularFile(it.resolve(STAMP)) }

    /**
     * The up-to-date cache directory for [pboPath], extracting it first if needed. Null when the
     * PBO is missing or cannot be read at all.
     *
     * Slow on first use: call it off the EDT and outside read actions.
     */
    @Synchronized
    fun ensure(pboPath: String): Ensured? {
        val pbo = Path.of(pboPath)
        if (!Files.isRegularFile(pbo)) return null
        val dir = dirFor(pboPath)
        val stamp = stampFor(pbo)
        val current = dir.resolve(STAMP)
        if (Files.isRegularFile(current) && runCatching { Files.readString(current) }.getOrNull() == stamp) {
            return Ensured(dir, extracted = false, warnings = emptyList())
        }

        val tmp = dir.resolveSibling(dir.fileName.toString() + ".tmp")
        return try {
            PboExtractor.deleteTree(tmp)
            val result = PboExtractor.extract(pbo, tmp, labelOf(pbo))
            Files.writeString(tmp.resolve(STAMP), stamp)
            PboExtractor.makeReadOnly(tmp)
            PboExtractor.deleteTree(dir)
            Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE)
            log.info("Unpacked ${result.filesWritten} files (${result.bytesWritten} bytes) from $pbo into $dir")
            result.warnings.forEach { log.info("$pbo: $it") }
            Ensured(dir, extracted = true, warnings = result.warnings)
        } catch (e: IOException) {
            log.warn("Could not unpack $pbo: ${e.message}")
            runCatching { PboExtractor.deleteTree(tmp) }
            null
        }
    }

    /** True for a file this cache wrote. */
    fun contains(file: VirtualFile): Boolean =
        file.isInLocalFileSystem && file.path.startsWith(rootPath, ignoreCase = true)

    /** The PBO a cached file was unpacked from, as the stamp records it. */
    fun sourceOf(file: VirtualFile): String? {
        if (!contains(file)) return null
        val dirName = file.path.substring(rootPath.length).substringBefore('/')
        val stamp = root.resolve(dirName).resolve(STAMP)
        return runCatching { Files.readAllLines(stamp) }.getOrNull()
            ?.firstOrNull { it.startsWith("source=") }?.removePrefix("source=")
    }

    private fun dirFor(pboPath: String): Path {
        val pbo = Path.of(pboPath).toAbsolutePath().normalize()
        val name = pbo.fileName.toString().removeSuffix(".pbo").removeSuffix(".PBO")
        // `@Mod/addons/Scripts.pbo`: most mods name their PBO Scripts.pbo, so the mod folder is
        // what tells two cache directories apart for a human looking at them.
        val mod = pbo.parent?.parent?.fileName?.toString()?.takeIf { it.startsWith("@") }
        val readable = listOfNotNull(mod, name).joinToString("-").replace(Regex("[^A-Za-z0-9@._-]"), "_")
        val digest = MessageDigest.getInstance("SHA-256").digest(pbo.toString().lowercase().toByteArray())
        val hash = digest.take(4).joinToString("") { "%02x".format(it) }
        return root.resolve("$readable-$hash")
    }

    private fun stampFor(pbo: Path): String =
        "format=$FORMAT\nsource=${pbo.toAbsolutePath().normalize()}\n" +
            "size=${Files.size(pbo)}\nmtime=${Files.getLastModifiedTime(pbo).toMillis()}\n"

    /** `@Mod/addons/Scripts.pbo`, enough to recognise the PBO without the machine's full path. */
    private fun labelOf(pbo: Path): String {
        val abs = pbo.toAbsolutePath().normalize()
        val count = abs.nameCount
        return abs.subpath(maxOf(0, count - 3), count).toString().replace('\\', '/')
    }
}

/**
 * Keeps unpacked PBO files read-only in the editor. The read-only flag on disk alone is not enough:
 * the IDE offers to clear it with one click, and an edit there would be lost on the next unpack
 * anyway.
 */
class PboCacheWritingAccessProvider : WritingAccessProvider() {

    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> =
        files.filter { PboCache.contains(it) }

    override fun isPotentiallyWritable(file: VirtualFile): Boolean = !PboCache.contains(file)

    override fun getReadOnlyMessage(): String = "Unpacked from a PBO: read-only"
}
