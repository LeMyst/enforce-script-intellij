package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Files
import java.nio.file.Path

/** The Steam app IDs the tooling looks for. */
object DayZApps {
    const val DAYZ = 221100
    const val DAYZ_SERVER = 223350
    const val DAYZ_TOOLS = 830640
}

/**
 * Where the DayZ installs and folders are on this machine. Every field is null when unknown.
 *
 * This package uses no IntelliJ API, like `texture/format`, so that detection can be tested on
 * temporary folders.
 */
data class DayZPaths(
    /** The game: `DayZ_x64.exe`, `DayZDiag_x64.exe`. */
    val game: Path? = null,
    /** The dedicated server: `DayZServer_x64.exe`. */
    val server: Path? = null,
    /** DayZ Tools: its `Bin` folder holds WorkDrive, AddonBuilder, Publisher, Workbench... */
    val tools: Path? = null,
    /** The folder DayZ Tools mounts as the work drive. */
    val workDriveSource: Path? = null,
    /** The work drive's letter, `P` unless DayZ Tools is set otherwise. */
    val workDriveLetter: String? = null,
    /** The client's profile folder, holding `script_*.log`, `*.RPT` and `crash_*.log`. */
    val clientProfile: Path? = null,
    /** The server's profile folder, as passed to `-profiles=`. Never detected: each server chooses its own. */
    val serverProfile: Path? = null,
) {
    val gameExe: Path? get() = game?.resolve(GAME_EXE)
    val gameDiagExe: Path? get() = game?.resolve(GAME_DIAG_EXE)
    val serverExe: Path? get() = server?.resolve(SERVER_EXE)
    val toolsBin: Path? get() = tools?.resolve("Bin")

    /** Each field of [this], or the one from [fallback] where this one is unknown. */
    fun orElse(fallback: DayZPaths): DayZPaths = DayZPaths(
        game ?: fallback.game,
        server ?: fallback.server,
        tools ?: fallback.tools,
        workDriveSource ?: fallback.workDriveSource,
        workDriveLetter ?: fallback.workDriveLetter,
        clientProfile ?: fallback.clientProfile,
        serverProfile ?: fallback.serverProfile,
    )

    companion object {
        const val GAME_EXE = "DayZ_x64.exe"
        const val GAME_DIAG_EXE = "DayZDiag_x64.exe"
        const val SERVER_EXE = "DayZServer_x64.exe"
        const val TOOLS_SETTINGS = "settings.ini"
    }
}

/**
 * DayZ Tools' own `settings.ini`, next to its `Bin` folder. Its `[ProjectDrive]` section is where the
 * DayZ Tools launcher keeps the work-drive folder and letter, and `[Game]` the game folder it uses.
 */
class DayZToolsSettings(private val sections: Map<String, Map<String, String>>) {

    val workDriveSource: String? get() = value("ProjectDrive", "path")
    val workDriveLetter: String? get() = value("ProjectDrive", "Letter")?.trim()?.trimEnd(':')?.takeIf { it.length == 1 && it[0].isLetter() }?.uppercase()
    val gamePath: String? get() = value("Game", "path")

    private fun value(section: String, key: String): String? =
        sections.entries.firstOrNull { it.key.equals(section, ignoreCase = true) }?.value
            ?.entries?.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            ?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        fun parse(text: String): DayZToolsSettings {
            val sections = LinkedHashMap<String, MutableMap<String, String>>()
            var current: MutableMap<String, String>? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    current = sections.getOrPut(line.substring(1, line.length - 1).trim()) { LinkedHashMap() }
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                current?.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim())
            }
            return DayZToolsSettings(sections)
        }

        fun read(toolsFolder: Path): DayZToolsSettings? = runCatching {
            val file = toolsFolder.resolve(DayZPaths.TOOLS_SETTINGS)
            if (Files.isRegularFile(file)) parse(Files.readString(file)) else null
        }.getOrNull()
    }
}

/**
 * Detects the installs from Steam's records, DayZ Tools' settings and the usual folders.
 *
 * [steamRoots] are the candidate Steam folders, the one from the registry first; [localAppData] is
 * `%LOCALAPPDATA%`. Both are parameters so that tests can point them at temporary folders.
 */
object DayZDetector {

    fun detect(steamRoots: List<Path>, localAppData: Path?): DayZPaths {
        val libraries = steamRoots.filter { Files.isDirectory(it) }
            .flatMap { SteamLibraries.libraryFolders(it) }
            .distinct()
        val tools = SteamLibraries.findApp(libraries, DayZApps.DAYZ_TOOLS)
        val toolsSettings = tools?.let { DayZToolsSettings.read(it) }
        val game = SteamLibraries.findApp(libraries, DayZApps.DAYZ)
            ?: toolsSettings?.gamePath?.let(::existingFolder)
        return DayZPaths(
            game = game,
            server = SteamLibraries.findApp(libraries, DayZApps.DAYZ_SERVER),
            tools = tools,
            workDriveSource = toolsSettings?.workDriveSource?.let(::existingFolder),
            workDriveLetter = toolsSettings?.workDriveLetter,
            clientProfile = localAppData?.resolve("DayZ")?.takeIf { Files.isDirectory(it) },
        )
    }

    /**
     * Where Steam usually is when the registry says nothing: the default install folders. The
     * registry's `SteamPath` comes first when it is known.
     */
    fun steamRootCandidates(registrySteamPath: String?, programFilesX86: String?, programFiles: String?): List<Path> =
        listOfNotNull(registrySteamPath, programFilesX86?.let { "$it\\Steam" }, programFiles?.let { "$it\\Steam" })
            .mapNotNull { runCatching { Path.of(it).normalize() }.getOrNull() }
            .distinct()

    private fun existingFolder(path: String): Path? =
        runCatching { Path.of(path) }.getOrNull()?.takeIf { Files.isDirectory(it) }
}

/** What a configured folder is missing, said in a line; null when it looks right. */
object DayZPathChecks {

    fun game(folder: Path): String? = when {
        !Files.isDirectory(folder) -> "Folder not found"
        !Files.isRegularFile(folder.resolve(DayZPaths.GAME_EXE)) -> "${DayZPaths.GAME_EXE} not found in this folder"
        else -> null
    }

    fun server(folder: Path): String? = when {
        !Files.isDirectory(folder) -> "Folder not found"
        !Files.isRegularFile(folder.resolve(DayZPaths.SERVER_EXE)) -> "${DayZPaths.SERVER_EXE} not found in this folder"
        else -> null
    }

    fun tools(folder: Path): String? = when {
        !Files.isDirectory(folder) -> "Folder not found"
        !Files.isDirectory(folder.resolve("Bin")) -> "Not a DayZ Tools folder: it has no Bin folder"
        else -> null
    }

    fun folder(folder: Path): String? = if (Files.isDirectory(folder)) null else "Folder not found"

    fun driveLetter(letter: String): String? =
        if (letter.length == 1 && letter[0].uppercaseChar() in 'A'..'Z') null else "A single letter, such as P"
}
