package io.github.lemyst.enfusion.pbo

import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The `.pbo` files under a folder root, so a whole `@Mod`, a `!Workshop` folder or a game install's
 * `addons` folder can be added in one entry instead of PBO by PBO.
 *
 * Scanning walks the disk, so it happens only where unpacking does: when the root list is applied
 * and when the project opens. Each scan is recorded next to the PBO caches, and the library
 * provider, which can run inside a read action, reads that record instead of walking.
 */
object PboFolders {

    /**
     * Deep enough for every layout seen in practice, counted from the folder the user picks:
     * `addons/x.pbo` (a mod, or a game install's own PBOs), `@Mod/addons/x.pbo` (`!Workshop`),
     * `<id>/addons/x.pbo` (Steam's workshop content folder), and `!Workshop/@Mod/addons/x.pbo` (a
     * game install's root). Shallow enough that picking a drive by mistake stays quick.
     */
    const val MAX_DEPTH = 4

    /** Far beyond the largest Workshop collection; stops a walk of a whole drive from running away. */
    private const val MAX_PBOS = 20_000

    private val log = logger<PboFolders>()

    private val folders: Path get() = PboCache.root.resolve("folders")

    private val recorded = ConcurrentHashMap<String, List<String>>()

    class Found(val pbos: List<Path>, val totalBytes: Long)

    /**
     * Every `.pbo` under [folder], sorted by path so that the order between mods, which is the
     * tiebreak between roots, stays the same from one scan to the next.
     *
     * Links are followed: every `@Mod` under `!Workshop` is one, into Steam's workshop content. A
     * PBO reached twice (through a link and directly) is kept once, under the first path found.
     * Hidden folders and the PBO cache itself are not entered.
     */
    fun scan(folder: Path): Found {
        if (!Files.isDirectory(folder)) return Found(emptyList(), 0)
        val found = ArrayList<Path>()
        val seen = HashSet<Path>()
        var total = 0L
        val cache = runCatching { PboCache.root.toRealPath() }.getOrNull()
        Files.walkFileTree(folder, setOf(FileVisitOption.FOLLOW_LINKS), MAX_DEPTH, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != folder && dir.fileName?.toString()?.startsWith(".") == true) return FileVisitResult.SKIP_SUBTREE
                if (cache != null && runCatching { dir.toRealPath() }.getOrNull() == cache) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && PboCache.isPbo(file.toString())) {
                    val real = runCatching { file.toRealPath() }.getOrDefault(file)
                    if (seen.add(real)) {
                        found.add(file)
                        total += attrs.size()
                    }
                    if (found.size >= MAX_PBOS) return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }

            // An unreadable folder or a link loop costs that branch, not the scan.
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        found.sortBy { it.toString().lowercase() }
        return Found(found, total)
    }

    /**
     * Scans [folder] and records the result for [recordedPbos]. Synchronized because the startup
     * refresh and a settings change can both scan the same folder and write the same record.
     */
    @Synchronized
    fun scanAndRecord(folder: String): List<String> {
        val pbos = scan(Path.of(folder)).pbos.map { it.toString() }
        recorded[key(folder)] = pbos
        try {
            Files.createDirectories(folders)
            Files.write(recordFile(folder), listOf(folder) + pbos)
        } catch (e: IOException) {
            log.warn("Could not record the PBOs found under $folder: ${e.message}")
        }
        return pbos
    }

    /** The PBOs the last scan of [folder] found, from this session or an earlier one. Walks nothing. */
    fun recordedPbos(folder: String): List<String> =
        recorded.computeIfAbsent(key(folder)) {
            val lines = runCatching { Files.readAllLines(recordFile(folder)) }.getOrNull()
            // The first line is the folder itself, kept for anyone reading the cache by hand.
            lines?.drop(1)?.filter { it.isNotBlank() } ?: emptyList()
        }

    private fun key(folder: String): String = folder.replace('\\', '/').trimEnd('/').lowercase()

    private fun recordFile(folder: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(key(folder).toByteArray())
        return folders.resolve(digest.take(8).joinToString("") { "%02x".format(it) } + ".txt")
    }
}
