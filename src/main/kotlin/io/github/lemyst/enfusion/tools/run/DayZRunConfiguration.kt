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
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.ThrowableComputable
import io.github.lemyst.enfusion.tools.DayZInstallation
import io.github.lemyst.enfusion.tools.detect.DayZLaunch
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCleanup
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZLaunchMode
import io.github.lemyst.enfusion.tools.detect.DayZPaths
import io.github.lemyst.enfusion.tools.detect.GoldbergIdentity
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * *Run | Edit Configurations | DayZ*, with one entry per [DayZLaunchMode] under it in the *+* menu,
 * as Docker does: each editor then shows only the fields its mode uses, instead of one editor with
 * the other modes' fields disabled.
 */
class DayZRunConfigurationType : ConfigurationTypeBase(
    ID,
    "DayZ",
    "Start DayZ, a DayZ server or Buldozer with the project's mods",
    NotNullLazyValue.createValue { AllIcons.Nodes.Console },
) {
    init {
        DayZLaunchMode.entries.forEach { addFactory(DayZRunConfigurationFactory(this, it)) }
    }

    fun factory(mode: DayZLaunchMode): ConfigurationFactory =
        configurationFactories.first { (it as DayZRunConfigurationFactory).mode == mode }

    companion object {
        const val ID = "EnfusionDayZ"
    }
}

class DayZRunConfigurationFactory(type: DayZRunConfigurationType, val mode: DayZLaunchMode) : ConfigurationFactory(type) {

    /**
     * The client keeps the ID of the single factory there was before the split, so that a
     * configuration saved then still loads; its saved mode, not the factory, decides its editor.
     */
    override fun getId(): String = if (mode == DayZLaunchMode.CLIENT) DayZRunConfigurationType.ID else "${DayZRunConfigurationType.ID}.${mode.name}"

    override fun getName(): String = mode.title

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        DayZRunConfiguration(project, this, "DayZ ${mode.title}").also { it.options.mode = mode.name }

    override fun getOptionsClass(): Class<out BaseState> = DayZRunOptions::class.java
}

class DayZRunOptions : RunConfigurationOptions() {
    var mode by string(DayZLaunchMode.CLIENT.name)
    var diag by property(true)
    var mods by string()
    var serverMods by string()
    var profiles by string()
    var filePatching by property(true)
    var window by property(true)
    var connect by string()
    var port by string()
    var serverConfig by string()
    var mission by string()
    var extraArguments by string()
    var modsFolder by string()
    var playerName by string()
    /** Saved with the configuration, in plain text: it is meant for a local test server. */
    var password by string()
    var stopRunning by property(false)
    var clearLogs by property(false)
    var wipeStorage by property(false)
    var steamEmulator by property(false)
    var steamId by string()

    fun toLaunch(): DayZLaunch = DayZLaunch(
        mode = runCatching { DayZLaunchMode.valueOf(mode ?: "") }.getOrDefault(DayZLaunchMode.CLIENT),
        diag = diag,
        mods = DayZLaunchCommand.splitMods(mods),
        serverMods = DayZLaunchCommand.splitMods(serverMods),
        profiles = profiles?.trim()?.ifEmpty { null },
        filePatching = filePatching,
        window = window,
        connect = connect?.trim()?.ifEmpty { null },
        port = port?.trim()?.toIntOrNull(),
        serverConfig = serverConfig?.trim()?.ifEmpty { null },
        mission = mission?.trim()?.ifEmpty { null },
        extraArguments = extraArguments.orEmpty(),
        modsFolder = modsFolder?.trim()?.ifEmpty { null },
        playerName = playerName?.trim()?.ifEmpty { null },
        password = password?.ifEmpty { null },
        stopRunning = stopRunning,
        clearLogs = clearLogs,
        wipeStorage = wipeStorage,
        steamEmulator = steamEmulator,
        steamId = steamId?.trim()?.ifEmpty { null },
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

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        DayZRunConfigurationEditor(project, options.toLaunch().mode)

    override fun checkConfiguration() {
        val paths = DayZInstallation.getInstance().configured.orElse(DayZInstallation.getInstance().lastDetected ?: return)
        val launch = options.toLaunch()
        try {
            DayZLaunchCommand.build(paths, launch)
        } catch (e: DayZLaunchCommand.Problem) {
            throw RuntimeConfigurationError("${e.message} Set it in Settings | Languages & Frameworks | Enforce Script | DayZ Installation.")
        }
        val steamId = launch.steamId
        if (launch.mode == DayZLaunchMode.CLIENT && launch.steamEmulator && steamId != null && !GoldbergIdentity.isSteamId(steamId)) {
            throw RuntimeConfigurationError("The Steam ID must be 17 digits starting with 7656119, or empty to derive one from the player name.")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                val paths = DayZInstallation.getInstance().paths(project)
                val launch = options.toLaunch()
                val command = try {
                    DayZLaunchCommand.build(paths, launch)
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
                val report = beforeLaunch(launch, command, paths)
                val line = GeneralCommandLine(command).withWorkDirectory(File(command.first()).parentFile)
                val handler = KillableColoredProcessHandler(line)
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

            /**
             * Stops the game, clears the profile's logs and wipes the mission's storage, as asked;
             * returns a line for each, for the console. Deleting files and waiting for processes to
             * exit is slow, so on the EDT it runs under a modal progress.
             */
            private fun beforeLaunch(launch: DayZLaunch, command: List<String>, paths: DayZPaths): List<String> {
                val wipe = launch.wipeStorage && launch.mode == DayZLaunchMode.SERVER
                val identity = launch.steamEmulator && launch.mode == DayZLaunchMode.CLIENT
                if (!launch.stopRunning && !launch.clearLogs && !wipe && !identity) return emptyList()
                val work = ThrowableComputable<List<String>, ExecutionException> {
                    val lines = ArrayList<String>()
                    try {
                        if (launch.stopRunning) lines += "Stopped ${DayZLaunchCleanup.stopRunning()} running DayZ process(es)."
                        if (launch.clearLogs) {
                            val default = if (launch.mode == DayZLaunchMode.CLIENT) paths.clientProfile else null
                            DayZLaunchCleanup.profileFolder(command, default)?.let { profile ->
                                lines += "Cleared ${DayZLaunchCleanup.clearLogs(profile).size} log file(s) in $profile."
                            }
                        }
                        if (wipe) {
                            val mission = DayZLaunchCleanup.missionFolder(command)
                            lines += if (mission == null) {
                                "No -mission= to wipe the storage of."
                            } else {
                                "Wiped ${DayZLaunchCleanup.wipeStorage(mission).size} storage folder(s) in $mission."
                            }
                        }
                        if (identity) lines += writeIdentity(launch)
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
             * Writes this client's Goldberg identity, after the previous client has had
             * [GoldbergIdentity.SPACING_MS] to read its own: every client reads the same two files,
             * and a Compound configuration starts its clients back to back.
             */
            private fun writeIdentity(launch: DayZLaunch): String {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: throw ExecutionException("%APPDATA% is not set, so the Goldberg identity has nowhere to go.")
                val name = GoldbergIdentity.accountName(launch.playerName)
                val steamId = GoldbergIdentity.steamId(launch.steamId, name)
                val folder = GoldbergIdentity.settingsFolder(Path.of(appData))
                synchronized(GoldbergSpacing) {
                    val wait = GoldbergIdentity.waitBeforeWriting(GoldbergSpacing.lastWrite, System.currentTimeMillis())
                    if (wait > 0) Thread.sleep(wait)
                    try {
                        GoldbergIdentity.write(folder, name, steamId)
                    } catch (e: IOException) {
                        throw ExecutionException("Could not write the Goldberg identity to $folder: ${e.message}")
                    }
                    GoldbergSpacing.lastWrite = System.currentTimeMillis()
                }
                return "Goldberg Steam identity: $name, $steamId (in $folder)."
            }
        }
}
