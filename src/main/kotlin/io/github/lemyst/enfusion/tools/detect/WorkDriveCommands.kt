package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Path

/**
 * The commands that mount and unmount the work drive, and reading back what is mounted.
 *
 * DayZ Tools' own `Bin/WorkDrive/WorkDrive.exe` is preferred: it is what the DayZ Tools launcher
 * runs, and its help text gives `/Mount [source]` and `/Dismount`. It takes the drive letter from
 * DayZ Tools' `settings.ini`. Without DayZ Tools, `subst` does the same job, a drive letter
 * standing for a folder, and takes the letter on its command line.
 *
 * Either way the mapping belongs to the Windows logon session: a drive mounted from an elevated
 * process is not visible to a normal one, and the reverse.
 */
object WorkDriveCommands {

    const val WORK_DRIVE_EXE = "WorkDrive/WorkDrive.exe"

    fun mount(toolsBin: Path?, source: Path, letter: Char): List<String> {
        val exe = toolsBin?.resolve(WORK_DRIVE_EXE)
        return if (exe != null && java.nio.file.Files.isRegularFile(exe)) {
            listOf(exe.toString(), "/Mount", source.toString())
        } else {
            listOf("subst", "$letter:", source.toString())
        }
    }

    fun dismount(toolsBin: Path?, letter: Char): List<String> {
        val exe = toolsBin?.resolve(WORK_DRIVE_EXE)
        return if (exe != null && java.nio.file.Files.isRegularFile(exe)) {
            listOf(exe.toString(), "/Dismount")
        } else {
            listOf("subst", "$letter:", "/D")
        }
    }

    /**
     * The drive mappings `subst` lists, one `P:\: => F:\Work Drive` per line. WorkDrive's mount
     * is listed there too.
     */
    fun parseSubst(output: String): Map<Char, String> {
        val result = LinkedHashMap<Char, String>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            val arrow = line.indexOf("=>")
            if (arrow < 2 || line.length < 3 || line[1] != ':') continue
            val letter = line[0].uppercaseChar()
            if (letter !in 'A'..'Z') continue
            val target = line.substring(arrow + 2).trim()
            // A long path comes back with the `\??\` namespace prefix on some Windows versions.
            result[letter] = target.removePrefix("\\??\\").takeIf { it.isNotEmpty() } ?: continue
        }
        return result
    }

    /** The drive letter a path is on, `P` for `P:\scripts` or `p:/scripts`; null for a relative or UNC path. */
    fun driveOf(path: String): Char? {
        if (path.length < 2 || path[1] != ':') return null
        val letter = path[0].uppercaseChar()
        return if (letter in 'A'..'Z') letter else null
    }
}
