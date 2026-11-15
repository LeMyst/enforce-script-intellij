package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Path

/**
 * What a DayZ run configuration holds, as plain values. One configuration starts a server, clients,
 * or both, with one mod list: testing a multiplayer mod needs the server and its clients to load the
 * same mods.
 */
data class DayZLaunch(
    /** Start a server, first. */
    val startServer: Boolean = false,
    /** Start the clients: one per [players], or a single one with no name when there are none. */
    val startClients: Boolean = true,
    /** Run DayZ Diag (`DayZDiag_x64.exe`) rather than the retail executables; a Diag server is the same executable with `-server`. */
    val diag: Boolean = true,
    /**
     * The mod list, in load order: ticked rows go to `-mod=` of the server and the clients, *Server
     * only* rows to the server's `-serverMod=` and to no client. A bare name such as `@CF` is taken
     * from [modsFolder] when one is set; otherwise the game looks for it beside its executable.
     */
    val modList: List<DayZMod> = emptyList(),
    /**
     * The clients, one each. With several, each needs its own profile and, to stay connected
     * together, its own Goldberg identity.
     */
    val players: List<DayZPlayer> = emptyList(),
    /** The clients' `-profiles=`, or the folder their own profiles go under when there are several. */
    val profiles: String? = null,
    /** The server's `-profiles=`; empty takes the server profile from the DayZ Installation settings. */
    val serverProfile: String? = null,
    val filePatching: Boolean = true,
    /** Clients: `-window`. */
    val window: Boolean = true,
    /** Clients: `-connect=`, to join a server this run does not start. */
    val connect: String? = null,
    /** The server's `-port=`, and the port the clients join. */
    val port: Int? = null,
    /** Server: `-config=`, usually `serverDZ.cfg`. */
    val serverConfig: String? = null,
    /**
     * A Diag server's `-mission=` (the dedicated server reads its mission from its config). A
     * relative path is relative to the executable's folder, the game's working directory.
     */
    val serverMission: String? = null,
    /** The clients' `-mission=`, to play offline: only when there is no server to join. */
    val offlineMission: String? = null,
    /** Appended to every command line as they are, split on spaces outside quotes. */
    val extraArguments: String = "",
    /** Where bare `@Mod` names in [modList] are, such as `P:\Mods`. */
    val modsFolder: String? = null,
    /** Clients joining a server: `-password=`. */
    val password: String? = null,
    /** Before starting: stop every running DayZ, DayZ Diag and DayZ Server process. */
    val stopRunning: Boolean = false,
    /** Before starting: delete the logs, RPTs, crash dumps and admin logs in each profile folder. */
    val clearLogs: Boolean = false,
    /** Server, before starting: delete the mission's `storage_*` folders (the persistence). */
    val wipeStorage: Boolean = false,
    /** Clients, before each starts: write its Goldberg Steam emulator identity (see [GoldbergIdentity]). */
    val steamEmulator: Boolean = false,
    /** Seconds between the server's start and the first client's, so that the server is up to join. */
    val clientDelaySeconds: Int = 5,
)

/** The executables and arguments for a [DayZLaunch]. */
object DayZLaunchCommand {

    class Problem(message: String) : Exception(message)

    /** The port a client joins when this run starts the server and no port is set: the game's default. */
    const val DEFAULT_PORT = 2302

    /**
     * The server's command line. [pboMod] and [addonMod] are the folders the mod list's PBOs were
     * staged into and its addons packed into, each put where the first row of its kind is. Without
     * them those rows are left out, as when only checking a configuration.
     */
    fun buildServer(paths: DayZPaths, launch: DayZLaunch, pboMod: String? = null, addonMod: String? = null): List<String> {
        val args = ArrayList<String>()
        val (mods, serverMods) = modArguments(launch, forServer = true, pboMod, addonMod)
        if (launch.diag) {
            args += executable(paths, DayZPaths.GAME_DIAG_EXE)
            args += "-server"
        } else {
            val server = paths.serverExe ?: throw Problem("The DayZ Server folder is not set.")
            args += server.toString()
        }
        launch.serverConfig?.takeIf { it.isNotBlank() }?.let { args += "-config=${it.trim()}" }
        launch.port?.let { args += "-port=$it" }
        val profiles = launch.serverProfile ?: paths.serverProfile?.toString()
        profiles?.let { args += "-profiles=$it" }
        if (mods.isNotEmpty()) args += "-mod=" + mods.joinToString(";")
        if (serverMods.isNotEmpty()) args += "-serverMod=" + serverMods.joinToString(";")
        if (launch.filePatching) args += "-filePatching"
        if (launch.diag) launch.serverMission?.takeIf { it.isNotBlank() }?.let { args += "-mission=${it.trim()}" }
        args += splitArguments(launch.extraArguments)
        return args
    }

    /**
     * The command line of [player]'s client (by default the first). When this run starts the server,
     * the client joins it on this machine; otherwise it joins *Connect to*, or plays [DayZLaunch.offlineMission]
     * offline. See [buildServer] for [pboMod] and [addonMod].
     */
    fun buildClient(
        paths: DayZPaths,
        launch: DayZLaunch,
        player: DayZPlayer? = launch.players.firstOrNull(),
        pboMod: String? = null,
        addonMod: String? = null,
    ): List<String> {
        val args = ArrayList<String>()
        val (mods, _) = modArguments(launch, forServer = false, pboMod, addonMod)
        val connect = if (launch.startServer) "127.0.0.1" else launch.connect?.trim()?.takeIf { it.isNotEmpty() }
        val mission = if (launch.startServer) null else launch.offlineMission?.trim()?.takeIf { it.isNotEmpty() }
        if (connect != null && mission != null) {
            throw Problem("Set either Connect to (join a server) or Offline mission, not both.")
        }
        val port = if (launch.startServer) launch.port ?: DEFAULT_PORT else launch.port
        args += executable(paths, if (launch.diag) DayZPaths.GAME_DIAG_EXE else DayZPaths.GAME_EXE)
        if (mods.isNotEmpty()) args += "-mod=" + mods.joinToString(";")
        profileOf(paths, launch, player)?.let { args += "-profiles=$it" }
        player?.name?.trim()?.takeIf { it.isNotEmpty() }?.let { args += "-name=$it" }
        if (launch.filePatching) args += "-filePatching"
        if (launch.window) args += "-window"
        mission?.let { args += "-mission=$it" }
        connect?.let { args += "-connect=$it" }
        port?.let { args += "-port=$it" }
        if (connect != null) launch.password?.takeIf { it.isNotEmpty() }?.let { args += "-password=$it" }
        args += splitArguments(launch.extraArguments)
        return args
    }

    /**
     * Why the server would not run, or null: without `-config=` a DayZ server logs "Server config
     * not found", counts down ten seconds and quits.
     */
    fun serverProblem(paths: DayZPaths, launch: DayZLaunch): String? =
        if (launch.startServer && launch.serverConfig.isNullOrBlank()) {
            "A DayZ server needs its config: set Server config (-config=), such as serverDZ.cfg."
        } else {
            null
        }

    /**
     * A warning when the server and a client would share a profile folder: with no profile of its
     * own, each falls back to the game's default, and they write their logs over each other.
     */
    fun sharedProfileWarning(paths: DayZPaths, launch: DayZLaunch): String? {
        if (!launch.startServer || !launch.startClients) return null
        val server = DayZMods.key(launch.serverProfile ?: paths.serverProfile?.toString() ?: "")
        val clients = launch.players.ifEmpty { listOf(DayZPlayer()) }
            .map { DayZMods.key(profileOf(paths, launch, it) ?: "") }
        return when {
            server.isEmpty() && clients.any { it.isEmpty() } ->
                "The server and a client both use the game's default profile: set the server's Profile, or Profiles for the clients."
            server.isNotEmpty() && server in clients -> "The server and a client use the same profile folder."
            else -> null
        }
    }

    /**
     * Whether a server config lets clients with `-filePatching` join: `allowFilePatching = 1`.
     * Without it the server refuses them ("The server does not accept the client's current
     * filePatching setting"). Comments are skipped; the setting is off when absent.
     */
    fun allowsFilePatching(serverConfig: String): Boolean =
        serverConfig.lineSequence()
            .map { it.substringBefore("//").trim() }
            .mapNotNull { ALLOW_FILE_PATCHING.find(it)?.groupValues?.get(1) }
            .lastOrNull() == "1"

    private val ALLOW_FILE_PATCHING = Regex("^allowFilePatching\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

    private fun executable(paths: DayZPaths, name: String): String {
        val game = paths.game ?: throw Problem("The DayZ folder is not set.")
        return game.resolve(name).toString()
    }

    /**
     * `-mod=` and `-serverMod=` entries from the ticked rows, in order. The server sends *Server
     * only* rows to `-serverMod=`; a client leaves them out. A generated mod ([pboMod] for the PBO
     * rows, [addonMod] for the addon rows) goes once, where the first ticked row of its kind is: to
     * the server's `-serverMod=`, and to no client, only when every ticked row of that kind is
     * server only.
     */
    fun modArguments(launch: DayZLaunch, forServer: Boolean, pboMod: String?, addonMod: String? = null): Pair<List<String>, List<String>> {
        val rows = launch.modList.filter { it.enabled && it.path.isNotBlank() }
        val generated = mapOf(DayZModKind.PBO to pboMod, DayZModKind.ADDON to addonMod)
        val mods = ArrayList<String>()
        val serverMods = ArrayList<String>()
        val placed = HashSet<DayZModKind>()
        for (row in rows) {
            val serverOnly = if (row.kind in generated) rows.filter { it.kind == row.kind }.all { it.serverOnly } else row.serverOnly
            if (serverOnly && !forServer) continue
            val entry = if (row.kind in generated) {
                val folder = generated[row.kind] ?: continue
                if (!placed.add(row.kind)) continue
                folder
            } else {
                resolveMod(row.path.trim(), launch.modsFolder)
            }
            (if (serverOnly) serverMods else mods) += entry
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
            listOfNotNull(launch.modsFolder, launch.profiles, launch.serverProfile, launch.serverMission, launch.offlineMission) +
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
