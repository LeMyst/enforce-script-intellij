package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Files
import java.nio.file.Path

/**
 * The game data DayZ Tools extracts onto the work drive, and the command that extracts it.
 *
 * `WorkDrive.exe` documents `/extractGameData [game path] [destination path]` in its help text, and
 * that is what the DayZ Tools launcher's *Extract Game Data* runs.
 */
object GameData {

    /** The folders whose presence says the data was extracted: scripts, configs, GUI. */
    val EXPECTED = listOf("scripts", "DZ", "gui")

    /** Which of [EXPECTED] are missing under [drive] (the drive's root, such as `P:\`). */
    fun missing(drive: Path): List<String> = EXPECTED.filter { !Files.isDirectory(drive.resolve(it)) }

    fun extractCommand(toolsBin: Path, game: Path, destination: Path): List<String> =
        listOf(toolsBin.resolve(WorkDriveCommands.WORK_DRIVE_EXE).toString(), "/extractGameData", game.toString(), destination.toString())

    /**
     * [command] in a console window of its own, waited for. WorkDrive ends with "Program terminated,
     * hit any key to exit." and reads that key with `Console.ReadKey`, which throws when the input
     * is a pipe, as it is under the IDE. The [title] must contain a space: `start` takes its first
     * quoted argument as the title, so one left unquoted would make the program's path the title.
     */
    fun inOwnConsole(title: String, command: List<String>): List<String> {
        require(' ' in title) { "the title must be quoted, so must contain a space: $title" }
        return listOf("cmd", "/c", "start", title, "/wait") + command
    }
}
