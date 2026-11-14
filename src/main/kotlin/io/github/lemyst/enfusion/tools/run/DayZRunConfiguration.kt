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
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import io.github.lemyst.enfusion.tools.DayZInstallation
import io.github.lemyst.enfusion.tools.detect.DayZLaunch
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZLaunchMode

/** *Run | Edit Configurations | DayZ*: the game, its server, or Buldozer. */
class DayZRunConfigurationType : ConfigurationTypeBase(
    ID,
    "DayZ",
    "Start DayZ, a DayZ server or Buldozer with the project's mods",
    NotNullLazyValue.createValue { AllIcons.Nodes.Console },
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId(): String = ID
            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                DayZRunConfiguration(project, this, "DayZ")

            override fun getOptionsClass(): Class<out BaseState> = DayZRunOptions::class.java
        })
    }

    companion object {
        const val ID = "EnfusionDayZ"
    }
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
    )
}

class DayZRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<DayZRunOptions>(project, factory, name) {

    public override fun getOptions(): DayZRunOptions = super.getOptions() as DayZRunOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = DayZRunConfigurationEditor(project)

    override fun checkConfiguration() {
        val paths = DayZInstallation.getInstance().configured.orElse(DayZInstallation.getInstance().lastDetected ?: return)
        try {
            DayZLaunchCommand.build(paths, options.toLaunch())
        } catch (e: DayZLaunchCommand.Problem) {
            throw RuntimeConfigurationError("${e.message} Set it in Settings | Languages & Frameworks | Enforce Script | DayZ Installation.")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                val paths = DayZInstallation.getInstance().paths(project)
                val command = try {
                    DayZLaunchCommand.build(paths, options.toLaunch())
                } catch (e: DayZLaunchCommand.Problem) {
                    throw ExecutionException(e.message)
                }
                val line = GeneralCommandLine(command).withWorkDirectory(java.io.File(command.first()).parentFile)
                val handler = KillableColoredProcessHandler(line)
                ProcessTerminatedListener.attach(handler)
                return handler
            }
        }
}
