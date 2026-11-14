package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds Steam apps on disk the way Steam itself records them.
 *
 * Steam keeps a list of its library folders in `<Steam>/steamapps/libraryfolders.vdf`, and each
 * installed app has an `appmanifest_<id>.acf` in its library's `steamapps` folder naming its
 * `installdir` under `steamapps/common`. Reading those beats guessing paths: DayZ is often
 * installed on another drive than Steam.
 */
object SteamLibraries {

    /**
     * Steam's library folders, [steamRoot] first. Understands both layouts of
     * `libraryfolders.vdf`: the current one, a block per library with a `path` key, and the older
     * one, where each numbered key's value is the path itself.
     */
    fun libraryFolders(steamRoot: Path): List<Path> {
        // The registry spells the folder in lower case; the real path has the case users recognise.
        val libraries = linkedSetOf(runCatching { steamRoot.toRealPath() }.getOrDefault(steamRoot.normalize()))
        val vdf = steamRoot.resolve("steamapps").resolve("libraryfolders.vdf")
        val root = readKeyValues(vdf) ?: return libraries.toList()
        val folders = root.block("libraryfolders") ?: return libraries.toList()
        for ((key, value) in folders.all) {
            if (key.toIntOrNull() == null) continue
            val path = when (value) {
                is KeyValues -> value.string("path")
                is String -> value
                else -> null
            } ?: continue
            if (path.isBlank()) continue
            runCatching { Path.of(path).normalize() }.getOrNull()?.let { libraries.add(it) }
        }
        return libraries.toList()
    }

    /** The install folder of [appId] in any of [libraries], or null when no manifest names one that exists. */
    fun findApp(libraries: List<Path>, appId: Int): Path? {
        for (library in libraries) {
            val steamapps = library.resolve("steamapps")
            val manifest = readKeyValues(steamapps.resolve("appmanifest_$appId.acf"))?.block("AppState") ?: continue
            val installDir = manifest.string("installdir")?.takeIf { it.isNotBlank() } ?: continue
            val folder = runCatching { steamapps.resolve("common").resolve(installDir) }.getOrNull() ?: continue
            if (Files.isDirectory(folder)) return folder
        }
        return null
    }

    /**
     * The `SteamPath` value from `reg query HKCU\Software\Valve\Steam /v SteamPath` output, or null.
     * Steam writes it with forward slashes and in lower case (`d:/program files (x86)/steam`).
     */
    fun parseRegQuery(output: String, valueName: String): String? {
        for (line in output.lineSequence()) {
            val parts = line.trim().split(Regex("\\s{2,}|\\t"), limit = 3)
            if (parts.size == 3 && parts[0].equals(valueName, ignoreCase = true) && parts[1].startsWith("REG_")) {
                return parts[2].trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun readKeyValues(file: Path): KeyValues? =
        runCatching { if (Files.isRegularFile(file)) KeyValues.parse(Files.readString(file)) else null }.getOrNull()
}
