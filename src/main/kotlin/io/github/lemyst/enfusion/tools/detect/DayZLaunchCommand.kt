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
     * The mod list, in load order: ticked rows go to `-mod=`, or `-serverMod=` for a server's
     * *Server only* rows. A bare name such as `@CF` is taken from [modsFolder] when one is set;
     * otherwise the game looks for it beside its executable.
     */
    val modList: List<DayZMod> = emptyList(),
    /**
     * Client: the clients to start, one each. Empty is one client with no `-name=`. With several,
     * each needs its own profile and, to stay connected together, its own Goldberg identity.
     */
    val players: List<DayZPlayer> = emptyList(),
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
    /** Where bare `@Mod` names in [modList] are, such as `P:\Mods`. */
    val modsFolder: String? = null,
    /** Client joining a server: `-password=`. */
    val password: String? = null,
    /** Before starting: stop every running DayZ, DayZ Diag and DayZ Server process. */
    val stopRunning: Boolean = false,
    /** Before starting: delete the logs, RPTs, crash dumps and admin logs in the profile folder. */
    val clearLogs: Boolean = false,
    /** Server, before starting: delete the mission's `storage_*` folders (the persistence). */
    val wipeStorage: Boolean = false,
    /** Client, before starting each client: write its Goldberg Steam emulator identity (see [GoldbergIdentity]). */
    val steamEmulator: Boolean = false,
    /** Client: seconds to wait before the first client starts, so that a server started with it is up. */
    val waitSeconds: Int = 0,
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

    /**
     * The command line of one process: for a client, the one of [player] (by default the first).
     * [pboMod] and [addonMod] are the folders the mod list's PBOs were staged into and its addons
     * packed into, each put where the first row of its kind is. Without them those rows are left
     * out, as when only checking a configuration.
     */
    fun build(
        paths: DayZPaths,
        launch: DayZLaunch,
        player: DayZPlayer? = launch.players.firstOrNull(),
        pboMod: String? = null,
        addonMod: String? = null,
    ): List<String> {
        val args = ArrayList<String>()
        val (mods, serverMods) = modArguments(launch, pboMod, addonMod)
        val filePatching = launch.filePatching || DayZMods.needsFilePatching(launch.modList)
        when (launch.mode) {
            DayZLaunchMode.CLIENT -> {
                val connect = launch.connect?.trim()?.takeIf { it.isNotEmpty() }
                val mission = launch.mission?.trim()?.takeIf { it.isNotEmpty() }
                if (connect != null && mission != null) {
                    throw Problem("Set either Connect to (join a server) or Mission (play offline), not both.")
                }
                args += executable(paths, if (launch.diag) DayZPaths.GAME_DIAG_EXE else DayZPaths.GAME_EXE)
                if (mods.isNotEmpty()) args += "-mod=" + mods.joinToString(";")
                profileOf(paths, launch, player)?.let { args += "-profiles=$it" }
                player?.name?.trim()?.takeIf { it.isNotEmpty() }?.let { args += "-name=$it" }
                if (filePatching) args += "-filePatching"
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
                if (mods.isNotEmpty()) args += "-mod=" + mods.joinToString(";")
                if (serverMods.isNotEmpty()) args += "-serverMod=" + serverMods.joinToString(";")
                if (filePatching) args += "-filePatching"
                if (launch.diag) launch.mission?.takeIf { it.isNotBlank() }?.let { args += "-mission=${it.trim()}" }
            }

            DayZLaunchMode.BULDOZER -> {
                args += executable(paths, DayZPaths.GAME_EXE)
                args += listOf("-buldozer", "-window", "-noland", "-nopause")
                val drive = paths.workDriveLetter ?: "P"
                args += "-profiles=${launch.profiles ?: "$drive:\\Buldozer"}"
                args += "-name:Buldozer"
                if (mods.isNotEmpty()) args += "-mod=" + mods.joinToString(";")
                if (filePatching) args += "-filePatching"
            }
        }
        args += splitArguments(launch.extraArguments)
        return args
    }

    private fun executable(paths: DayZPaths, name: String): String {
        val game = paths.game ?: throw Problem("The DayZ folder is not set.")
        return game.resolve(name).toString()
    }

    /**
     * `-mod=` and `-serverMod=` entries from the ticked rows, in order. Only a server splits them:
     * a client or Buldozer passes every row to `-mod=`. A generated mod ([pboMod] for the PBO rows,
     * [addonMod] for the addon rows) goes once, where the first ticked row of its kind is, and to
     * `-serverMod=` only when every ticked row of that kind is server only.
     */
    fun modArguments(launch: DayZLaunch, pboMod: String?, addonMod: String? = null): Pair<List<String>, List<String>> {
        val server = launch.mode == DayZLaunchMode.SERVER
        val rows = launch.modList.filter { it.enabled && it.path.isNotBlank() }
        val generated = mapOf(DayZModKind.PBO to pboMod, DayZModKind.ADDON to addonMod)
        val mods = ArrayList<String>()
        val serverMods = ArrayList<String>()
        val placed = HashSet<DayZModKind>()
        for (row in rows) {
            if (row.kind in generated) {
                val folder = generated[row.kind] ?: continue
                if (!placed.add(row.kind)) continue
                val allServerOnly = server && rows.filter { it.kind == row.kind }.all { it.serverOnly }
                (if (allServerOnly) serverMods else mods) += folder
                continue
            }
            (if (server && row.serverOnly) serverMods else mods) += resolveMod(row.path.trim(), launch.modsFolder)
        }
        return mods to serverMods
    }

    /**
     * A client's `-profiles=`: the player's own, or the configuration's. Several clients cannot
     * share one (each writes its logs and settings there), so with more than one player an empty
     * profile is the configuration's folder, or else the game's default, with the player's name
     * under it.
     */
    fun profileOf(paths: DayZPaths, launch: DayZLaunch, player: DayZPlayer?): String? {
        player?.profile?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (launch.players.size <= 1) return launch.profiles
        val base = launch.profiles ?: paths.clientProfile?.toString() ?: return null
        val name = player?.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Player ${launch.players.indexOf(player) + 1}"
        return resolveMod(name, base)
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
        (
            listOfNotNull(launch.modsFolder, launch.profiles, launch.mission) +
                launch.modList.filter { it.enabled }.map { it.path } +
                launch.players.mapNotNull { it.profile }
            )
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

    /** The `@Mod` folders directly under [roots], as a starting `-mod=` list. */
    fun modFolders(roots: List<Path>): List<String> =
        roots.flatMap { root ->
            runCatching { java.nio.file.Files.list(root).use { s -> s.filter { java.nio.file.Files.isDirectory(it) && it.fileName.toString().startsWith("@") }.toList() } }
                .getOrDefault(emptyList())
        }.map { it.toString() }.sorted()
}
