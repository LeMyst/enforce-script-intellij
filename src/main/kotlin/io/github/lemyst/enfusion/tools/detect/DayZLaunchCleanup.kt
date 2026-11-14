package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * What a DayZ run configuration can do before it starts the game, as a mod's launch scripts
 * usually do: stop the game, clear the profile's logs, wipe a server's persistence.
 *
 * The folders come from free-text fields, so every deletion stays inside one folder, among its
 * direct children, and a drive root or the home folder is refused outright: a profile field left
 * as `C:\` must not become a sweep of the whole drive.
 */
object DayZLaunchCleanup {

    private val EXECUTABLES = setOf(DayZPaths.GAME_EXE, DayZPaths.GAME_DIAG_EXE, DayZPaths.SERVER_EXE).map { it.lowercase() }.toSet()

    /** Script logs, RPTs, crash dumps and admin logs, as the game writes them into its profile. */
    private val LOG_EXTENSIONS = setOf("log", "rpt", "mdmp", "adm")

    /**
     * Stops every running DayZ, DayZ Diag and DayZ Server process and waits for them to exit, so
     * that their log files are no longer locked. Returns how many were stopped.
     */
    fun stopRunning(): Int {
        val running = ProcessHandle.allProcesses().filter { process ->
            process.info().command()
                .map { command -> runCatching { Path.of(command).fileName.toString().lowercase() in EXECUTABLES }.getOrDefault(false) }
                .orElse(false)
        }.toList()
        val stopped = running.filter { it.destroyForcibly() }
        stopped.forEach { runCatching { it.onExit().get(10, TimeUnit.SECONDS) } }
        return stopped.size
    }

    /** Deletes the log files directly in [profile]; returns them. Nothing when the folder does not exist yet. */
    fun clearLogs(profile: Path): List<Path> {
        val dir = safeFolder(profile) ?: return emptyList()
        val logs = Files.list(dir).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.', "").lowercase() in LOG_EXTENSIONS }.toList()
        }
        return logs.filter { runCatching { Files.delete(it) }.isSuccess }
    }

    /**
     * Deletes the `storage_*` folders directly in [mission], the server's persistence; returns them.
     * Without them the Central Economy spawns the loot afresh, so every run starts from the same
     * world, at the price of a slower start.
     */
    fun wipeStorage(mission: Path): List<Path> {
        val dir = safeFolder(mission) ?: return emptyList()
        val storages = Files.list(dir).use { files ->
            files.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("storage_", ignoreCase = true) }.toList()
        }
        return storages.filter { runCatching { it.toFile().deleteRecursively() }.getOrDefault(false) }
    }

    /**
     * [folder] if it exists and may be cleaned, null if it does not exist; throws when it is a drive
     * root or the home folder.
     */
    private fun safeFolder(folder: Path): Path? {
        val dir = folder.toAbsolutePath().normalize()
        if (dir.parent == null || dir == Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()) {
            throw DayZLaunchCommand.Problem("Refusing to clean $dir: it is a drive root or the home folder.")
        }
        return dir.takeIf { Files.isDirectory(it) }
    }

    /**
     * The profile folder a command writes its logs to: its `-profiles=`, else [defaultProfile] (the
     * game's own default, `%LOCALAPPDATA%\DayZ`, for a client). Relative to the working directory.
     */
    fun profileFolder(command: List<String>, defaultProfile: Path?): Path? =
        argument(command, "-profiles=")?.let { resolve(command, it) } ?: defaultProfile

    /** The mission folder a command starts, from its `-mission=`, relative to the working directory. */
    fun missionFolder(command: List<String>): Path? = argument(command, "-mission=")?.let { resolve(command, it) }

    private fun argument(command: List<String>, prefix: String): String? =
        command.drop(1).lastOrNull { it.startsWith(prefix) }?.substring(prefix.length)?.trim()?.takeIf { it.isNotEmpty() }

    private fun resolve(command: List<String>, value: String): Path? = runCatching {
        val path = Path.of(value)
        if (path.isAbsolute) path else DayZLaunchCommand.workingDirectory(command)?.resolve(path) ?: path
    }.getOrNull()
}
