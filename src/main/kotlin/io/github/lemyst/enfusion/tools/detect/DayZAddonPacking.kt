package io.github.lemyst.enfusion.tools.detect

import io.github.lemyst.enfusion.pbo.format.PboWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * The project's addons packed into a mod at launch, so that the game loads them exactly as it loads
 * a built mod: one PBO per addon under its prefix. Loading the folders unpacked instead mounts them
 * at the root of the game's file system, where a `LanguageCore\stringtable.csv` or a
 * `Scripts\4_World\…\WeaponManager.c` replaces vanilla's file of the same path.
 *
 * Only addons whose files changed since the last launch are packed again, as mod build scripts do
 * with a mirrored copy: here a manifest of each addon's files (path, size, date) is kept beside the
 * mod, and an addon is packed when it differs or its PBO is gone. PBOs of addons no longer listed
 * are removed.
 */
object DayZAddonPacking {

    /** An addon to pack: its folder, and the prefix the game mounts it under. */
    data class Addon(val folder: Path, val prefix: String) {
        /** `MyMod\Scripts\Client` → `Scripts_Client.pbo`, as mod build scripts name them. */
        val pboName: String
            get() {
                val parts = prefix.split('\\', '/').filter { it.isNotEmpty() }
                return (if (parts.size > 1) parts.drop(1) else parts).joinToString("_") + ".pbo"
            }
    }

    data class Result(val packed: List<String>, val unchanged: List<String>, val removed: List<String>)

    /**
     * Files a mod's build leaves out, as mod build scripts do: sources, backups, logs, packed files
     * and their signatures.
     */
    private val EXCLUDED_EXTENSIONS = setOf("bak", "png", "psd", "tif", "tga", "log", "bat", "cmd", "pbo", "bisign")

    fun excluded(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in EXCLUDED_EXTENSIONS || extension.startsWith("blend")
    }

    /** The files to pack, with their names in the PBO, sorted. Dot folders and `source` folders are skipped. */
    fun files(folder: Path): List<PboWriter.File> {
        val out = ArrayList<PboWriter.File>()
        fun walk(dir: Path) {
            val children = Files.list(dir).use { s -> s.toList() }.sortedBy { it.name.lowercase() }
            for (child in children) {
                val name = child.name
                if (child.isDirectory()) {
                    if (!name.startsWith(".") && !name.equals("source", ignoreCase = true)) walk(child)
                } else if (!excluded(name) && !name.startsWith("\$PBOPREFIX\$", ignoreCase = true)) {
                    out += PboWriter.File(folder.relativize(child).joinToString("\\"), child)
                }
            }
        }
        walk(folder)
        return out
    }

    /** One line per file: name, size and date, which change whenever the file is added, removed or edited. */
    fun manifest(files: List<PboWriter.File>): String = files.joinToString("\n") {
        "${it.name}|${Files.size(it.source)}|${Files.getLastModifiedTime(it.source).toMillis()}"
    }

    /**
     * The prefix of the addon at [path]: the one it declares, or else its path under the work drive
     * [letter] (`P:\MyMod\Scripts` → `MyMod\Scripts`, as the drive's link to the mod makes it), or
     * else its folder's name.
     */
    fun prefixOf(path: Path, letter: Char): String {
        declaredPrefix(path)?.let { return it }
        val text = path.toString().replace('/', '\\')
        if (text.length > 3 && text[1] == ':' && text[0].equals(letter, ignoreCase = true) && text[2] == '\\') {
            return text.substring(3).trimEnd('\\')
        }
        return path.name
    }

    /**
     * The prefix an addon declares in a `$PBOPREFIX$` file, as Addon Builder reads it, or null.
     * Some tools write `prefix=…` rather than the bare prefix.
     */
    fun declaredPrefix(folder: Path): String? {
        val file = runCatching {
            Files.list(folder).use { s -> s.filter { it.name.startsWith("\$PBOPREFIX\$", ignoreCase = true) }.findFirst().orElse(null) }
        }.getOrNull() ?: return null
        val line = runCatching { Files.readAllLines(file).firstOrNull { it.isNotBlank() } }.getOrNull() ?: return null
        return line.trim().removePrefix("prefix=").trim().trim('\\', '/').takeIf { it.isNotEmpty() }
    }

    /**
     * Packs [addons] into `[mod]\Addons`, each only when its manifest in [manifests] differs or its
     * PBO is missing, and removes the PBOs there that no addon produces any more.
     */
    fun pack(addons: List<Addon>, mod: Path, manifests: Path, modCpp: Path? = null): Result {
        val names = addons.groupBy { it.pboName.lowercase() }.filterValues { it.size > 1 }.keys
        if (names.isNotEmpty()) throw DayZLaunchCommand.Problem("Two addons would both be packed as ${names.first()}.")
        val target = mod.resolve("Addons")
        Files.createDirectories(target)
        Files.createDirectories(manifests)
        val packed = ArrayList<String>()
        val unchanged = ArrayList<String>()
        for (addon in addons) {
            if (!Files.isRegularFile(addon.folder.resolve("config.cpp"))) {
                throw DayZLaunchCommand.Problem("${addon.folder} has no config.cpp.")
            }
            val files = files(addon.folder)
            val manifest = "prefix=${addon.prefix}\n" + manifest(files)
            val pbo = target.resolve(addon.pboName)
            val record = manifests.resolve(addon.pboName + ".manifest")
            val previous = runCatching { Files.readString(record) }.getOrNull()
            if (previous == manifest && Files.isRegularFile(pbo)) {
                unchanged += addon.pboName
                continue
            }
            PboWriter.write(pbo, addon.prefix, files)
            Files.writeString(record, manifest)
            packed += addon.pboName
        }
        // The mod's name and logo in the game's mod list, as mod build scripts copy it.
        if (modCpp != null && Files.isRegularFile(modCpp)) {
            Files.copy(modCpp, mod.resolve("mod.cpp"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val wanted = addons.mapTo(HashSet()) { it.pboName.lowercase() }
        val removed = Files.list(target).use { s -> s.toList() }
            .filter { it.name.endsWith(".pbo", ignoreCase = true) && it.name.lowercase() !in wanted }
        removed.forEach {
            Files.deleteIfExists(it)
            Files.deleteIfExists(manifests.resolve(it.name + ".manifest"))
        }
        return Result(packed, unchanged, removed.map { it.name })
    }
}
