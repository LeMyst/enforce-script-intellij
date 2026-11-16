package io.github.lemyst.enfusion.tools.detect

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

    /** With the trailing backslash the working command line has (`-mod=P:\MyMod\`). */
    fun withTrailingSeparator(folder: String): String =
        if (folder.endsWith('\\') || folder.endsWith('/')) folder else "$folder\\"
}
