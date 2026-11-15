package io.github.lemyst.enfusion.tools.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.util.xmlb.annotations.Tag
import io.github.lemyst.enfusion.tools.DayZInstallation
import io.github.lemyst.enfusion.tools.detect.DayZAddonPacking
import io.github.lemyst.enfusion.tools.detect.DayZLaunch
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCleanup
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZMod
import io.github.lemyst.enfusion.tools.detect.DayZModKind
import io.github.lemyst.enfusion.tools.detect.DayZMods
import io.github.lemyst.enfusion.tools.detect.DayZPaths
import io.github.lemyst.enfusion.tools.detect.DayZPboStaging
import io.github.lemyst.enfusion.tools.detect.DayZPlayer
import io.github.lemyst.enfusion.tools.detect.GoldbergIdentity
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * *Run | Edit Configurations | DayZ*: one configuration starts a server, clients, or both, with one
 * mod list, since a multiplayer mod is tested with the server and its clients loading the same mods.
 */
class DayZRunConfigurationType : ConfigurationTypeBase(
    ID,
    "DayZ",
    "Start a DayZ server and clients with the project's mods",
    NotNullLazyValue.createValue { AllIcons.Nodes.Console },
) {
    init {
        addFactory(DayZRunConfigurationFactory(this))
    }

    companion object {
        const val ID = "EnfusionDayZ"
    }
}

class DayZRunConfigurationFactory(type: DayZRunConfigurationType) : ConfigurationFactory(type) {

    override fun getId(): String = DayZRunConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration = DayZRunConfiguration(project, this, "DayZ")

    override fun getOptionsClass(): Class<out BaseState> = DayZRunOptions::class.java
}

/** A row of the mod list, as saved. */
@Tag("mod")
class DayZModState : BaseState() {
    var path by string()
    var kind by string(DayZModKind.MOD.name)
    var enabled by property(true)
    var serverOnly by property(false)

    fun toMod(): DayZMod? {
        val path = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val kind = runCatching { DayZModKind.valueOf(kind ?: "") }.getOrDefault(DayZMods.kindOf(path))
        return DayZMod(path, kind, enabled, serverOnly)
    }

    companion object {
        fun of(mod: DayZMod) = DayZModState().also {
            it.path = mod.path
            it.kind = mod.kind.name
            it.enabled = mod.enabled
            it.serverOnly = mod.serverOnly
        }
    }
}

/** A client of a Client configuration, as saved. */
@Tag("player")
class DayZPlayerState : BaseState() {
    var name by string()
    var steamId by string()
    var profile by string()

    fun toPlayer() = DayZPlayer(name?.trim()?.ifEmpty { null }, steamId?.trim()?.ifEmpty { null }, profile?.trim()?.ifEmpty { null })

    companion object {
        fun of(player: DayZPlayer) = DayZPlayerState().also {
            it.name = player.name
            it.steamId = player.steamId
            it.profile = player.profile
        }
    }
}

class DayZRunOptions : RunConfigurationOptions() {
    var startServer by property(false)
    var startClients by property(true)

    /**
     * Before one configuration started both: `SERVER` or `BULDOZER` for a configuration of that
     * kind (a client's was the default, so it was not saved). Read once by [migrate].
     */
    var mode by string()
    var diag by property(true)
    var modList by list<DayZModState>()

    /** Before the mod list: `;`-separated, read once to fill it (see [mods]), never written again. */
    var mods by string()
    var serverMods by string()
    var players by list<DayZPlayerState>()

    /** Before the players list: the one client's name, read once to fill it. */
    var playerName by string()
    var profiles by string()
    var serverProfile by string()
    var filePatching by property(true)
    var window by property(true)
    var connect by string()
    var port by string()
    var serverConfig by string()
    var mission by string()
    var extraArguments by string()
    var modsFolder by string()
    /** Saved with the configuration, in plain text: it is meant for a local test server. */
    var password by string()
    var stopRunning by property(false)
    var clearLogs by property(false)
    var wipeStorage by property(false)
    var steamEmulator by property(false)

    /** Before the players list: the one client's Steam ID. */
    var steamId by string()
    var clientDelaySeconds by string()

    /**
     * Turns a configuration saved when each kind had its own into one of this shape: a server's
     * starts the server only, with its profile as the server's; any other starts clients only.
     */
    fun migrate() {
        val old = mode ?: return
        mode = null
        if (old == "SERVER") {
            startServer = true
            startClients = false
            if (serverProfile == null) serverProfile = profiles
            profiles = null
        }
    }

    /** The mod list, or, for a configuration saved before there was one, its mod strings converted. */
    fun modRows(): List<DayZMod> =
        modList.mapNotNull { it.toMod() }.ifEmpty {
            DayZMods.fromLegacy(DayZLaunchCommand.splitMods(mods), DayZLaunchCommand.splitMods(serverMods))
        }

    /** The players, or the one client a configuration saved before the list described. */
    fun playerRows(): List<DayZPlayer> =
        players.map { it.toPlayer() }.ifEmpty {
            val name = playerName?.trim()?.ifEmpty { null }
            val id = steamId?.trim()?.ifEmpty { null }
            if (name == null && id == null) emptyList() else listOf(DayZPlayer(name, id))
        }

    fun setModRows(rows: List<DayZMod>) {
        modList.clear()
        modList.addAll(rows.map { DayZModState.of(it) })
        mods = null
        serverMods = null
    }

    fun setPlayerRows(rows: List<DayZPlayer>) {
        players.clear()
        players.addAll(rows.map { DayZPlayerState.of(it) })
        playerName = null
        steamId = null
    }

    fun toLaunch(): DayZLaunch = migrate().let { toLaunchAsSaved() }

    private fun toLaunchAsSaved() = DayZLaunch(
        startServer = startServer,
        startClients = startClients,
        diag = diag,
        modList = modRows(),
        players = playerRows(),
        profiles = profiles?.trim()?.ifEmpty { null },
        serverProfile = serverProfile?.trim()?.ifEmpty { null },
        filePatching = filePatching,
        window = window,
        connect = connect?.trim()?.ifEmpty { null },
        port = port?.trim()?.toIntOrNull(),
        serverConfig = serverConfig?.trim()?.ifEmpty { null },
        mission = mission?.trim()?.ifEmpty { null },
        extraArguments = extraArguments.orEmpty(),
        modsFolder = modsFolder?.trim()?.ifEmpty { null },
        password = password?.ifEmpty { null },
        stopRunning = stopRunning,
        clearLogs = clearLogs,
        wipeStorage = wipeStorage,
        steamEmulator = steamEmulator,
        clientDelaySeconds = clientDelaySeconds?.trim()?.toIntOrNull()?.coerceIn(0, 600) ?: 5,
    )
}

/** When the last Goldberg identity was written, by any DayZ configuration of this IDE. */
private object GoldbergSpacing {
    @Volatile
    var lastWrite: Long? = null
}

class DayZRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<DayZRunOptions>(project, factory, name) {

    public override fun getOptions(): DayZRunOptions = super.getOptions() as DayZRunOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = DayZRunConfigurationEditor(project)

    override fun checkConfiguration() {
        val launch = options.toLaunch()
        if (!launch.startServer && !launch.startClients) throw RuntimeConfigurationError("Tick Start a server, Start clients, or both.")
        val paths = DayZInstallation.getInstance().configured.orElse(DayZInstallation.getInstance().lastDetected ?: return)
        try {
            if (launch.startServer) DayZLaunchCommand.buildServer(paths, launch)
            if (launch.startClients) DayZLaunchCommand.buildClient(paths, launch)
        } catch (e: DayZLaunchCommand.Problem) {
            throw RuntimeConfigurationError("${e.message} Set it in Settings | Languages & Frameworks | Enforce Script | DayZ Installation.")
        }
        val letter = paths.workDriveLetter ?: "P"
        launch.modList.firstOrNull { it.enabled && it.kind == DayZModKind.ADDON && !it.path.startsWith("$letter:", ignoreCase = true) }?.let {
            throw RuntimeConfigurationWarning("${it.path} is not on the work drive ($letter:), which file patching reads from.")
        }
        if (!launch.startClients) return
        val players = launch.players
        players.mapNotNull { it.steamId }.firstOrNull { !GoldbergIdentity.isSteamId(it) }?.let {
            throw RuntimeConfigurationError("The Steam ID $it must be 17 digits starting with 7656119, or empty to derive one from the player name.")
        }
        val names = players.map { GoldbergIdentity.accountName(it.name).lowercase() }
        if (names.size != names.toSet().size) throw RuntimeConfigurationError("Two players have the same name.")
        val profiles = players.mapNotNull { DayZLaunchCommand.profileOf(paths, launch, it)?.let(DayZMods::key) }
        if (profiles.size != profiles.toSet().size) throw RuntimeConfigurationError("Two players have the same profile folder.")
        if (players.size > 1 && !launch.steamEmulator) {
            throw RuntimeConfigurationWarning(
                "Several clients on one Steam account kick each other off a server: tick Write a Goldberg Steam emulator identity.",
            )
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                val paths = DayZInstallation.getInstance().paths(project)
                val launch = options.toLaunch()
                if (!launch.startServer && !launch.startClients) throw ExecutionException("Tick Start a server, Start clients, or both.")
                val players = if (launch.startClients) launch.players.ifEmpty { listOf(DayZPlayer()) } else emptyList()
                // Packed and staged once: the server and every client load the same generated mods.
                val prepared = onBackground("Preparing the mods") { prepareMods(launch, paths) }
                val server: List<String>?
                val clients: List<List<String>>
                try {
                    server = if (launch.startServer) DayZLaunchCommand.buildServer(paths, launch, prepared.pboMod, prepared.addonMod) else null
                    clients = players.map { DayZLaunchCommand.buildClient(paths, launch, it, prepared.pboMod, prepared.addonMod) }
                } catch (e: DayZLaunchCommand.Problem) {
                    throw ExecutionException(e.message)
                }
                // The game starts happily without its mods and says nothing, so an unmounted work
                // drive is refused here instead.
                if (SystemInfo.isWindows) {
                    val unmounted = DayZLaunchCommand.drivesUsed(launch).filter { !File("$it:\\").isDirectory }
                    if (unmounted.isNotEmpty()) {
                        throw ExecutionException(
                            "${unmounted.joinToString(", ") { "$it:" }} is not mounted, and this configuration reads from it. " +
                                "Mount the work drive with Tools | DayZ | Mount Work Drive.",
                        )
                    }
                }
                val report = prepared.lines + beforeLaunch(launch, server, clients, paths)
                val processes = ArrayList<DayZProcessesHandler.Process>()
                server?.let { processes += DayZProcessesHandler.Process("Server", commandLine(it)) }
                players.zip(clients).forEachIndexed { index, (player, command) ->
                    // The first client waits for a server started with it to be up.
                    val delay = if (index == 0 && server != null) launch.clientDelaySeconds * 1000L else 0L
                    processes += DayZProcessesHandler.Process(player.name ?: "Client ${index + 1}", commandLine(command), delay) {
                        if (launch.steamEmulator) writeIdentity(player) else null
                    }
                }
                val handler = DayZProcessesHandler(processes)
                ProcessTerminatedListener.attach(handler)
                // Printed once the console is attached: text sent before startNotify is dropped.
                if (report.isNotEmpty()) {
                    handler.addProcessListener(object : ProcessListener {
                        override fun startNotified(event: ProcessEvent) {
                            report.forEach { handler.notifyTextAvailable("$it\n", ProcessOutputTypes.SYSTEM) }
                        }
                    })
                }
                return handler
            }

            private fun commandLine(command: List<String>) = GeneralCommandLine(command).withWorkDirectory(File(command.first()).parentFile)

            /**
             * The generated mods, in a folder of this configuration's own in the IDE's system folder
             * rather than the project: the ticked loose PBOs copied into one, and the ticked project
             * addons packed into another, each only where something changed since the last launch.
             */
            private fun prepareMods(launch: DayZLaunch, paths: DayZPaths): PreparedMods {
                val ticked = launch.modList.filter { it.enabled }
                val pbos = ticked.filter { it.kind == DayZModKind.PBO }.map { Path.of(it.path.trim()) }
                val addons = ticked.filter { it.kind == DayZModKind.ADDON }.map { Path.of(it.path.trim()) }
                if (pbos.isEmpty() && addons.isEmpty()) return PreparedMods(null, null, emptyList())
                val folder = Path.of(PathManager.getSystemPath(), "enfusion", "run-mods", "${project.locationHash}-${stagingName(name)}")
                val lines = ArrayList<String>()
                try {
                    val pboMod = if (pbos.isEmpty()) null else DayZPboStaging.stage(pbos, folder.resolve("pbos")).toString()
                    var addonMod: String? = null
                    if (addons.isNotEmpty()) {
                        val letter = (paths.workDriveLetter ?: "P").first()
                        val packing = addons.map { DayZAddonPacking.Addon(it, DayZAddonPacking.prefixOf(it, letter)) }
                        val mod = folder.resolve("addons").resolve("@" + stagingName(project.name))
                        // The mod's mod.cpp is at the root its addons' prefixes start from (P:\MyMod).
                        val modRoot = packing.firstNotNullOfOrNull { addon ->
                            addon.prefix.substringBefore('\\').takeIf { it.isNotEmpty() }?.let { Path.of("$letter:\\", it) }
                        }
                        val result = DayZAddonPacking.pack(packing, mod, folder.resolve("manifests"), modRoot?.resolve("mod.cpp"))
                        lines += buildString {
                            append("Packed ${result.packed.size} addon(s)")
                            if (result.packed.isNotEmpty()) append(" (${result.packed.joinToString(", ")})")
                            append(", ${result.unchanged.size} unchanged")
                            if (result.removed.isNotEmpty()) append(", removed ${result.removed.joinToString(", ")}")
                            append(", into $mod.")
                        }
                        addonMod = mod.toString()
                    }
                    return PreparedMods(pboMod, addonMod, lines)
                } catch (e: DayZLaunchCommand.Problem) {
                    throw ExecutionException(e.message)
                } catch (e: IOException) {
                    throw ExecutionException("Could not prepare the mods in $folder: ${e.message}")
                }
            }

            /** Disk work: under a modal progress on the EDT, where a run may be started from. */
            private fun <T> onBackground(title: String, work: () -> T): T {
                val computable = ThrowableComputable<T, ExecutionException> { work() }
                return if (ApplicationManager.getApplication().isDispatchThread) {
                    ProgressManager.getInstance().runProcessWithProgressSynchronously(computable, title, false, project)
                } else {
                    computable.compute()
                }
            }

            /**
             * Stops the game, clears each client's or the server's profile logs and wipes the
             * mission's storage, as asked; returns a line for each, for the console. Deleting files
             * and waiting for processes to exit is slow, so on the EDT it runs under a modal progress.
             */
            private fun beforeLaunch(launch: DayZLaunch, server: List<String>?, clients: List<List<String>>, paths: DayZPaths): List<String> {
                val wipe = launch.wipeStorage && server != null
                if (!launch.stopRunning && !launch.clearLogs && !wipe) return emptyList()
                val work = ThrowableComputable<List<String>, ExecutionException> {
                    val lines = ArrayList<String>()
                    try {
                        if (launch.stopRunning) lines += "Stopped ${DayZLaunchCleanup.stopRunning()} running DayZ process(es)."
                        if (launch.clearLogs) {
                            val profiles = listOfNotNull(server?.let { DayZLaunchCleanup.profileFolder(it, null) }) +
                                clients.mapNotNull { DayZLaunchCleanup.profileFolder(it, paths.clientProfile) }
                            for (profile in profiles.distinct()) {
                                lines += "Cleared ${DayZLaunchCleanup.clearLogs(profile).size} log file(s) in $profile."
                            }
                        }
                        if (wipe && server != null) {
                            val mission = DayZLaunchCleanup.missionFolder(server)
                            lines += if (mission == null) {
                                "No -mission= to wipe the storage of."
                            } else {
                                "Wiped ${DayZLaunchCleanup.wipeStorage(mission).size} storage folder(s) in $mission."
                            }
                        }
                    } catch (e: DayZLaunchCommand.Problem) {
                        throw ExecutionException(e.message)
                    }
                    lines
                }
                return if (ApplicationManager.getApplication().isDispatchThread) {
                    ProgressManager.getInstance().runProcessWithProgressSynchronously(work, "Preparing to start DayZ", false, project)
                } else {
                    work.compute()
                }
            }

            /**
             * Writes [player]'s Goldberg identity, after the previous client has had
             * [GoldbergIdentity.SPACING_MS] to read its own: every client reads the same two
             * files. Runs off the EDT, as each client is about to start.
             */
            private fun writeIdentity(player: DayZPlayer?): String {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: throw IOException("%APPDATA% is not set, so the Goldberg identity has nowhere to go.")
                val name = GoldbergIdentity.accountName(player?.name)
                val steamId = GoldbergIdentity.steamId(player?.steamId, name)
                val folder = GoldbergIdentity.settingsFolder(Path.of(appData))
                synchronized(GoldbergSpacing) {
                    val wait = GoldbergIdentity.waitBeforeWriting(GoldbergSpacing.lastWrite, System.currentTimeMillis())
                    if (wait > 0) Thread.sleep(wait)
                    GoldbergIdentity.write(folder, name, steamId)
                    GoldbergSpacing.lastWrite = System.currentTimeMillis()
                }
                return "Goldberg Steam identity: $name, $steamId (in $folder)."
            }
        }

    private class PreparedMods(val pboMod: String?, val addonMod: String?, val lines: List<String>)

    private companion object {
        /** A configuration's name as a folder name. */
        fun stagingName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
    }
}
