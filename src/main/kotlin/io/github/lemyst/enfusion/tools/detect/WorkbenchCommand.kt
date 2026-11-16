package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Files
import java.nio.file.Path

/**
 * How Workbench is started on a mod: `WorkbenchApp.exe -filePatching "-mod=P:\MyMod\"`, from its
 * own folder, which it needs as the current directory. `-filePatching` makes it read the mod's
 * unpacked files rather than packed PBOs, and `-mod=` names the mod's folder on the work drive.
 */
object WorkbenchCommand {

    fun command(exe: Path, mod: String?): List<String> {
        val args = mutableListOf(exe.toString(), "-filePatching")
        mod?.trim()?.takeIf { it.isNotEmpty() }?.let { args += "-mod=" + withTrailingSeparator(it) }
        return args
    }

    /**
     * The addon folder directly in [modFolder] that makes Workbench crash, or null: one named
     * `Scripts`. Workbench, like the game, mounts an unpacked `-mod=` folder at the root of the
     * game's files, so that addon's `config.cpp` replaces the game's `scripts\config.cpp`, the one
     * that declares the script modules, and Workbench crashes as it starts (an access violation,
     * every time). `Scripts\Client\` is fine. Other folders named like the game's (`GUI`,
     * `LanguageCore`) replace its config too, but Workbench runs with them, so they are not flagged.
     */
    fun crashingAddon(modFolder: Path): String? = runCatching {
        Files.list(modFolder).use { children ->
            children.filter { Files.isDirectory(it) && it.fileName.toString().equals("scripts", ignoreCase = true) }
                .filter { Files.isRegularFile(it.resolve("config.cpp")) }
                .map { it.fileName.toString() }
                .findFirst()
                .orElse(null)
        }
    }.getOrNull()

    /** With the trailing backslash the working command line has (`-mod=P:\MyMod\`). */
    fun withTrailingSeparator(folder: String): String =
        if (folder.endsWith('\\') || folder.endsWith('/')) folder else "$folder\\"
}
