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
    /**
     * `-mod=`: folders, `;`-separated as the game takes them. A bare name such as `@CF` is taken
     * from [modsFolder] when one is set; otherwise the game looks for it beside its executable.
     */
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
    /**
     * `-mission=`: for a Diag server, or for a client playing offline (then without [connect]).
     * A relative path is relative to the executable's folder, the game's working directory.
     */
    val mission: String? = null,
    /** Appended as they are, split on spaces outside quotes. */
    val extraArguments: String = "",
    /** Where bare `@Mod` names in [mods] and [serverMods] are, such as `P:\Mods`. */
    val modsFolder: String? = null,
    /** Client: `-name=`, the player's name in game. */
    val playerName: String? = null,
    /** Client joining a server: `-password=`. */
    val password: String? = null,
    /** Before starting: stop every running DayZ, DayZ Diag and DayZ Server process. */
    val stopRunning: Boolean = false,
    /** Before starting: delete the logs, RPTs, crash dumps and admin logs in the profile folder. */
    val clearLogs: Boolean = false,
    /** Server, before starting: delete the mission's `storage_*` folders (the persistence). */
    val wipeStorage: Boolean = false,
    /** Client, before starting: write a Goldberg Steam emulator identity (see [GoldbergIdentity]). */
    val steamEmulator: Boolean = false,
    /** The identity's Steam ID; empty derives one from the player name. */
    val steamId: String? = null,
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
                val connect = launch.connect?.trim()?.takeIf { it.isNotEmpty() }
                val mission = launch.mission?.trim()?.takeIf { it.isNotEmpty() }
                if (connect != null && mission != null) {
                    throw Problem("Set either Connect to (join a server) or Mission (play offline), not both.")
                }
                args += executable(paths, if (launch.diag) DayZPaths.GAME_DIAG_EXE else DayZPaths.GAME_EXE)
                mods(args, "-mod=", launch.mods, launch.modsFolder)
                launch.profiles?.let { args += "-profiles=$it" }
                launch.playerName?.trim()?.takeIf { it.isNotEmpty() }?.let { args += "-name=$it" }
                if (launch.filePatching) args += "-filePatching"
                if (launch.window) args += "-window"
                mission?.let { args += "-mission=$it" }
                connect?.let { args += "-connect=$it" }
                launch.port?.let { args += "-port=$it" }
                if (connect != null) launch.password?.takeIf { it.isNotEmpty() }?.let { args += "-password=$it" }
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
                mods(args, "-mod=", launch.mods, launch.modsFolder)
                mods(args, "-serverMod=", launch.serverMods, launch.modsFolder)
                if (launch.filePatching) args += "-filePatching"
                if (launch.diag) launch.mission?.takeIf { it.isNotBlank() }?.let { args += "-mission=${it.trim()}" }
            }

            DayZLaunchMode.BULDOZER -> {
                args += executable(paths, DayZPaths.GAME_EXE)
                args += listOf("-buldozer", "-window", "-noland", "-nopause")
                val drive = paths.workDriveLetter ?: "P"
                args += "-profiles=${launch.profiles ?: "$drive:\\Buldozer"}"
                args += "-name:Buldozer"
                mods(args, "-mod=", launch.mods, launch.modsFolder)
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

    private fun mods(args: MutableList<String>, flag: String, mods: List<String>, folder: String?) {
        val list = mods.map { it.trim() }.filter { it.isNotEmpty() }.map { resolveMod(it, folder) }
        if (list.isNotEmpty()) args += flag + list.joinToString(";")
    }

    /**
     * A bare name (`@CF`) joined to [folder]; a name with a path is left alone. Joined as text, with
     * the folder's own separator, rather than through [Path]: the result goes to a Windows game
     * either way, and a `P:\Mods` path does not parse as one on another OS.
     */
    fun resolveMod(name: String, folder: String?): String {
        val base = folder?.trim()?.takeIf { it.isNotEmpty() } ?: return name
        if (name.contains('\\') || name.contains('/') || name.contains(':')) return name
        val separator = if (base.contains('\\') || !base.contains('/')) '\\' else '/'
        return if (base.endsWith('\\') || base.endsWith('/')) base + name else "$base$separator$name"
    }

    /**
     * The folder of the executable [build] starts, which is the game's working directory: a relative
     * `-mission=` or `-profiles=` is resolved against it.
     */
    fun workingDirectory(command: List<String>): Path? =
        command.firstOrNull()?.let { runCatching { Path.of(it).parent }.getOrNull() }

    /**
     * The drive letters the launch reads from: the mods folder, the mods, the profile and the
     * mission. Used to refuse a launch from an unmounted work drive, which the game would otherwise
     * start without the mods and without saying why.
     */
    fun drivesUsed(launch: DayZLaunch): Set<Char> =
        (listOfNotNull(launch.modsFolder, launch.profiles, launch.mission) + launch.mods + launch.serverMods)
            .mapNotNull { text -> text.trim().takeIf { it.length >= 2 && it[1] == ':' && it[0].isLetter() }?.get(0)?.uppercaseChar() }
            .toSet()

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

    /**
     * The `@Mod` folder a PBO build writes into: the parent of an `Addons` output folder, when that
     * parent is named `@…` (`P:\Mods\@MyMod\Addons` gives `P:\Mods\@MyMod`). A project kept as
     * sources (`Scripts`, `Data`, …) has no `@` folder of its own, and this is where its mod is.
     * Text, not [Path], for the reason [resolveMod] gives.
     */
    fun modOfBuildOutput(destination: String?): String? {
        val output = destination?.trim()?.trimEnd('\\', '/')?.takeIf { it.isNotEmpty() } ?: return null
        val cut = output.lastIndexOfAny(charArrayOf('\\', '/'))
        if (cut < 0 || !output.substring(cut + 1).equals("Addons", ignoreCase = true)) return null
        val mod = output.substring(0, cut)
        val name = mod.substring(mod.lastIndexOfAny(charArrayOf('\\', '/')) + 1)
        return mod.takeIf { name.startsWith("@") && name.length > 1 }
    }

    /**
     * The [current] `-mod=` list with each of [found] added unless it is already there, as a bare
     * name when it lies directly in [modsFolder]. Nothing the user typed is removed or reordered.
     */
    fun withMods(current: String?, found: List<String>, modsFolder: String?): String {
        val mods = splitMods(current).toMutableList()
        val base = modsFolder?.trim()?.trimEnd('\\', '/')?.takeIf { it.isNotEmpty() }
        for (mod in found) {
            val known = mods.any { resolveMod(it, modsFolder).trimEnd('\\', '/').equals(mod.trimEnd('\\', '/'), ignoreCase = true) }
            if (known) continue
            val rest = base?.let { b -> mod.takeIf { it.length > b.length + 1 && it.startsWith(b, ignoreCase = true) && it[b.length] in "\\/" }?.substring(b.length + 1) }
            mods += rest?.takeIf { r -> r.none { it == '\\' || it == '/' } } ?: mod
        }
        return mods.joinToString(";")
    }

    /** The `@Mod` folders directly under [roots], as a starting `-mod=` list. */
    fun modFolders(roots: List<Path>): List<String> =
        roots.flatMap { root ->
            runCatching { java.nio.file.Files.list(root).use { s -> s.filter { java.nio.file.Files.isDirectory(it) && it.fileName.toString().startsWith("@") }.toList() } }
                .getOrDefault(emptyList())
        }.map { it.toString() }.sorted()
}
