package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Path

/** How a DayZ run configuration starts the game. */
enum class DayZLaunchMode(val title: String) {
    CLIENT("Client"),
    SERVER("Server"),
    BULDOZER("Buldozer");
}

/** What a DayZ run configuration holds, as plain values. */
data class DayZLaunch(
    val mode: DayZLaunchMode = DayZLaunchMode.CLIENT,
    /** Run DayZ Diag (`DayZDiag_x64.exe`) rather than the retail executable; a Diag server is the same executable with `-server`. */
    val diag: Boolean = true,
    /** `-mod=`: folders, `;`-separated as the game takes them. */
    val mods: List<String> = emptyList(),
    /** `-serverMod=`: server-only mods. */
    val serverMods: List<String> = emptyList(),
    val profiles: String? = null,
    val filePatching: Boolean = true,
    val window: Boolean = true,
    /** Client: `-connect=` and `-port=` to join a server at start. */
    val connect: String? = null,
    val port: Int? = null,
    /** Server: `-config=`, usually `serverDZ.cfg`. */
    val serverConfig: String? = null,
    /** Server, Diag only: `-mission=`. */
    val mission: String? = null,
    /** Appended as they are, split on spaces outside quotes. */
    val extraArguments: String = "",
)

/**
 * The executable and arguments for a [DayZLaunch].
 *
 * Buldozer is started exactly as DayZ Tools' Object Builder starts its external viewer
 * (`DayZ_x64.exe -buldozer -window -noland -nopause -profiles=P:\Buldozer -name:Buldozer`, its
 * default *External Viewer* setting), with the mods added.
 */
object DayZLaunchCommand {

    class Problem(message: String) : Exception(message)

    fun build(paths: DayZPaths, launch: DayZLaunch): List<String> {
        val args = ArrayList<String>()
        when (launch.mode) {
            DayZLaunchMode.CLIENT -> {
                args += executable(paths, if (launch.diag) DayZPaths.GAME_DIAG_EXE else DayZPaths.GAME_EXE)
                mods(args, "-mod=", launch.mods)
                launch.profiles?.let { args += "-profiles=$it" }
                if (launch.filePatching) args += "-filePatching"
                if (launch.window) args += "-window"
                launch.connect?.takeIf { it.isNotBlank() }?.let { args += "-connect=${it.trim()}" }
                launch.port?.let { args += "-port=$it" }
            }

            DayZLaunchMode.SERVER -> {
                if (launch.diag) {
                    args += executable(paths, DayZPaths.GAME_DIAG_EXE)
                    args += "-server"
                } else {
                    val server = paths.serverExe ?: throw Problem("The DayZ Server folder is not set.")
                    args += server.toString()
                }
                launch.serverConfig?.takeIf { it.isNotBlank() }?.let { args += "-config=${it.trim()}" }
                launch.port?.let { args += "-port=$it" }
                val profiles = launch.profiles ?: paths.serverProfile?.toString()
                profiles?.let { args += "-profiles=$it" }
                mods(args, "-mod=", launch.mods)
                mods(args, "-serverMod=", launch.serverMods)
                if (launch.filePatching) args += "-filePatching"
                if (launch.diag) launch.mission?.takeIf { it.isNotBlank() }?.let { args += "-mission=${it.trim()}" }
            }

            DayZLaunchMode.BULDOZER -> {
                args += executable(paths, DayZPaths.GAME_EXE)
                args += listOf("-buldozer", "-window", "-noland", "-nopause")
                val drive = paths.workDriveLetter ?: "P"
                args += "-profiles=${launch.profiles ?: "$drive:\\Buldozer"}"
                args += "-name:Buldozer"
                mods(args, "-mod=", launch.mods)
                if (launch.filePatching) args += "-filePatching"
            }
        }
        args += splitArguments(launch.extraArguments)
        return args
    }

    private fun executable(paths: DayZPaths, name: String): String {
        val game = paths.game ?: throw Problem("The DayZ folder is not set.")
        return game.resolve(name).toString()
    }

    private fun mods(args: MutableList<String>, flag: String, mods: List<String>) {
        val list = mods.map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isNotEmpty()) args += flag + list.joinToString(";")
    }

    /** `-a "b c" d` → `-a`, `b c`, `d`. */
    fun splitArguments(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var any = false
        for (c in text) {
            when {
                c == '"' -> {
                    quoted = !quoted
                    any = true
                }

                c.isWhitespace() && !quoted -> {
                    if (any) out += current.toString()
                    current.setLength(0)
                    any = false
                }

                else -> {
                    current.append(c)
                    any = true
                }
            }
        }
        if (any) out += current.toString()
        return out
    }

    /** `a;b; c` → `a`, `b`, `c`. */
    fun splitMods(text: String?): List<String> =
        text.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }

    /** The `@Mod` folders directly under [roots], as a starting `-mod=` list. */
    fun modFolders(roots: List<Path>): List<String> =
        roots.flatMap { root ->
            runCatching { java.nio.file.Files.list(root).use { s -> s.filter { java.nio.file.Files.isDirectory(it) && it.fileName.toString().startsWith("@") }.toList() } }
                .getOrDefault(emptyList())
        }.map { it.toString() }.sorted()
}
