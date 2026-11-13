package io.github.lemyst.enfusion.pbo

import io.github.lemyst.enfusion.pbo.format.Derapifier
import io.github.lemyst.enfusion.pbo.format.PboArchive
import io.github.lemyst.enfusion.pbo.format.PboFormatException
import io.github.lemyst.enfusion.pbo.format.PboPacking
import io.github.lemyst.enfusion.pbo.format.PboPaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes the navigable part of one PBO to a directory: scripts, configs, layouts, imagesets,
 * stringtables. Models, textures, sounds and everything else stay in the PBO.
 *
 * That subset is the point. A texture PBO can be gigabytes, while what navigation needs out of a
 * whole mod is typically a few hundred KB. Each entry is read by seeking to it, so a large PBO costs
 * no more than its header and the entries kept.
 *
 * Files land under the PBO's prefix (`<target>/MyMod/Scripts/3_Game/...`), so a packaged asset path
 * such as `MyMod/GUI/layouts/x.layout` suffix-matches the file the way it does for a source checkout.
 * A `$PBOPREFIX$` is written at the prefix directory, which is the marker
 * `EnfusionFileTypeOverrider` already looks for, so the `.c` files are claimed with no special case.
 *
 * No IntelliJ API, so it is tested on plain files.
 */
object PboExtractor {

    /** Extensions that are indexed for navigation. Everything else is left in the PBO. */
    private val KEPT = setOf("c", "layout", "imageset", "csv", "xml", "cpp", "hpp", "h", "json")

    /** A script or layout this large is generated data, not something to navigate, and the IDE would not index it anyway. */
    private const val MAX_ENTRY = 16L * 1024 * 1024

    private const val CONFIG_BIN = "config.bin"
    private const val CONFIG_CPP = "config.cpp"
    private const val PREFIX_MARKER = "\$PBOPREFIX\$"

    class Result(val filesWritten: Int, val bytesWritten: Long, val warnings: List<String>)

    /**
     * Writes into [target], which must not exist yet. [label] names the PBO in the header of each
     * rebuilt `config.cpp`.
     */
    fun extract(pbo: Path, target: Path, label: String): Result {
        val warnings = ArrayList<String>()
        var files = 0
        var bytes = 0L
        PboArchive.open(pbo).use { archive ->
            if (archive.isEncrypted) warnings += "encrypted entries were skipped (.ebo content cannot be read)"
            val prefix = archive.prefix?.let { p ->
                PboPaths.safeRelativePath(p).also { if (it == null) warnings += "unsafe prefix '$p' ignored" }
            }
            val base = if (prefix != null) target.resolve(prefix) else target
            Files.createDirectories(base)
            Files.writeString(base.resolve(PREFIX_MARKER), (prefix ?: "").replace('/', '\\') + "\n")

            // Directories holding a config.bin: a text config.cpp beside it is a leftover, and the
            // engine reads the binary one.
            val binDirs = archive.entries
                .filter { it.name.substringAfterLast('/').equals(CONFIG_BIN, ignoreCase = true) }
                .map { it.name.substringBeforeLast('/', "").lowercase() }
                .toSet()

            val seen = HashSet<String>()
            for (entry in archive.entries) {
                if (entry.packing == PboPacking.ENCRYPTED) continue
                val path = PboPaths.safeRelativePath(entry.name)
                if (path == null) {
                    warnings += "entry with an unsafe name skipped: '${entry.name.take(80)}'"
                    continue
                }
                val fileName = path.substringAfterLast('/')
                val dir = path.substringBeforeLast('/', "")
                val isBin = fileName.equals(CONFIG_BIN, ignoreCase = true)
                val ext = fileName.substringAfterLast('.', "").lowercase()
                if (!isBin && ext !in KEPT) continue
                if (fileName.equals(PREFIX_MARKER, ignoreCase = true)) continue
                if (!isBin && fileName.equals(CONFIG_CPP, ignoreCase = true) && dir.lowercase() in binDirs) continue
                if (entry.size > MAX_ENTRY) {
                    warnings += "${entry.name}: ${entry.size} bytes, too large to index"
                    continue
                }
                val outPath = if (isBin) (if (dir.isEmpty()) CONFIG_CPP else "$dir/$CONFIG_CPP") else path
                // Windows paths are case-insensitive; obfuscators also repeat names. The first one wins.
                if (!seen.add(outPath.lowercase())) continue

                val content = try {
                    val raw = archive.read(entry, MAX_ENTRY)
                    if (isBin) rebuiltConfig(raw, label, entry.name) else raw
                } catch (e: PboFormatException) {
                    warnings += "${entry.name}: ${e.message}"
                    continue
                }
                val out = base.resolve(outPath)
                // Belt and braces over safeRelativePath: never write outside the target.
                if (!out.normalize().startsWith(base.normalize())) continue
                Files.createDirectories(out.parent)
                Files.write(out, content)
                files++
                bytes += content.size
            }
        }
        return Result(files, bytes, warnings)
    }

    private fun rebuiltConfig(raw: ByteArray, label: String, entryName: String): ByteArray {
        if (!Derapifier.isRapified(raw)) {
            // Some packers store the text under the binary name.
            return raw
        }
        val text = "// Rebuilt by Enforce Script from $entryName in $label. Read-only.\n" + Derapifier.derapify(raw)
        return text.toByteArray(Charsets.UTF_8)
    }

    /**
     * Deletes a directory tree this object wrote. Files are cleared of the read-only flag first:
     * on Windows a read-only file cannot be deleted.
     */
    fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                p.toFile().setWritable(true)
                try {
                    Files.deleteIfExists(p)
                } catch (e: IOException) {
                    throw IOException("could not delete $p", e)
                }
            }
        }
    }

    /** Marks every file under [root] read-only, so the editor opens them locked. */
    fun makeReadOnly(root: Path) {
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { it.toFile().setWritable(false) }
        }
    }
}
