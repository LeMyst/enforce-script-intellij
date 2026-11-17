package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Loose `.pbo` files in a run configuration's mod list. The game loads PBOs only from a mod's
 * `Addons` folder, so they are copied, with their `.bisign` signatures, into a generated
 * `@ExtraPBOs\Addons`, which takes their place in `-mod=`: the same as mod build scripts copying
 * third-party PBOs into the built mod, without touching the mod.
 */
object DayZPboStaging {

    const val MOD_NAME = "@ExtraPBOs"

    /** What to copy into `Addons` (source to target) and which of its files to delete. */
    data class Plan(val copy: List<Pair<Path, Path>>, val delete: List<Path>)

    /**
     * [pbos] with their [signaturesOf] into [addons], whose files are [existing]: a file already
     * there and [unchanged] is not copied again, and a file no longer wanted is deleted. Two PBOs
     * with one name would overwrite each other in one folder, so that is a problem, not a guess.
     */
    fun plan(
        pbos: List<Path>,
        addons: Path,
        existing: List<Path>,
        signaturesOf: (Path) -> List<Path>,
        unchanged: (Path, Path) -> Boolean,
    ): Plan {
        val wanted = LinkedHashMap<String, Path>()
        for (pbo in pbos) {
            for (file in listOf(pbo) + signaturesOf(pbo)) {
                val name = file.fileName.toString()
                val previous = wanted.put(name.lowercase(), file)
                if (previous != null && previous != file) {
                    throw DayZLaunchCommand.Problem("Two PBOs of the mod list are both named $name: $previous and $file.")
                }
            }
        }
        val present = existing.associateBy { it.fileName.toString().lowercase() }
        val copy = wanted.values.mapNotNull { source ->
            val target = addons.resolve(source.fileName.toString())
            val there = present[source.fileName.toString().lowercase()]
            if (there != null && unchanged(source, there)) null else source to target
        }
        val delete = existing.filter { it.fileName.toString().lowercase() !in wanted }
        return Plan(copy, delete)
    }

    /** The `.bisign` files next to [pbo] that sign it: `<name>.pbo.<authority>.bisign`. */
    fun signaturesOf(pbo: Path): List<Path> {
        val prefix = pbo.fileName.toString().lowercase() + "."
        val dir = pbo.parent ?: return emptyList()
        return runCatching {
            Files.list(dir).use { files ->
                files.filter {
                    val name = it.fileName.toString().lowercase()
                    name.startsWith(prefix) && name.endsWith(".bisign") && Files.isRegularFile(it)
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Stages [pbos] under [folder] and returns the mod folder to pass to `-mod=`. Missing PBOs are
     * a problem: the game would start without them and say nothing.
     */
    fun stage(pbos: List<Path>, folder: Path): Path {
        pbos.firstOrNull { !Files.isRegularFile(it) }?.let { throw DayZLaunchCommand.Problem("The PBO $it of the mod list does not exist.") }
        val mod = folder.resolve(MOD_NAME)
        val addons = mod.resolve("Addons")
        Files.createDirectories(addons)
        val existing = Files.list(addons).use { files -> files.filter { Files.isRegularFile(it) }.toList() }
        val plan = plan(pbos, addons, existing, ::signaturesOf, ::sameSizeAndDate)
        plan.delete.forEach { Files.deleteIfExists(it) }
        for ((source, target) in plan.copy) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
        return mod
    }

    /**
     * Whether [target] is a copy of [source] that needs no copying again: same size, same date to
     * the millisecond. Not the exact date: a copy keeps the original's date, but Linux file systems
     * can store it with less precision than the original had, and the copy would never match.
     */
    fun sameSizeAndDate(source: Path, target: Path): Boolean =
        Files.isRegularFile(target) && Files.size(source) == Files.size(target) &&
            Files.getLastModifiedTime(source).toMillis() == Files.getLastModifiedTime(target).toMillis()
}
