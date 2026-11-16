package io.github.lemyst.enfusion.tools.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.util.xmlb.annotations.Tag
import io.github.lemyst.enfusion.tools.AddonBuilds
import io.github.lemyst.enfusion.tools.DayZInstallation
import io.github.lemyst.enfusion.tools.WorkDrive
import io.github.lemyst.enfusion.tools.detect.AddonBuilderCommand
import io.github.lemyst.enfusion.tools.detect.DayZAddonPacking
import io.github.lemyst.enfusion.tools.detect.DayZModKind
import io.github.lemyst.enfusion.tools.detect.DayZModRelease
import io.github.lemyst.enfusion.tools.detect.DayZModReleasePlan
import io.github.lemyst.enfusion.tools.detect.DsUtilsCommand
import java.nio.file.Files
import java.nio.file.Path

/**
 * *Run | Edit Configurations | DayZ Mod Build*: builds the `@MyMod` folder a mod ships, one
 * configuration per mod, since a project may ship more than one (a separate client mod, say).
 */
class DayZModBuildConfigurationType : ConfigurationTypeBase(
    ID,
    "DayZ Mod Build",
    "Build the mod for release: binarised, signed addons, keys, mod.cpp and extra files",
    NotNullLazyValue.createValue { AllIcons.Actions.Compile },
) {
    init {
        addFactory(DayZModBuildConfigurationFactory(this))
    }

    companion object {
        const val ID = "EnfusionDayZModBuild"
    }
}

class DayZModBuildConfigurationFactory(type: DayZModBuildConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = DayZModBuildConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration = DayZModBuildConfiguration(project, this, "Build Mod")

    override fun getOptionsClass(): Class<out BaseState> = DayZModBuildOptions::class.java
}

/** A file copied into the mod, as saved. */
@Tag("extra")
class DayZExtraState : BaseState() {
    var source by string()
    var target by string()
}

class DayZModBuildOptions : RunConfigurationOptions() {
    /** The `@MyMod` folder. */
    var output by string()

    /** The project's addons and third-party PBOs, as the DayZ run configuration lists them. */
    var modList by list<DayZModState>()
    var keys by list<String>()
    var signKey by string()
    var modCpp by string()
    var extras by list<DayZExtraState>()

    /**
     * The release, with each addon's prefix as the run configuration works it out: its
     * `$PBOPREFIX$`, else its path on the work drive [letter].
     */
    fun toRelease(letter: Char): DayZModRelease? {
        val output = path(output) ?: return null
        val rows = modList.mapNotNull { it.toMod() }.filter { it.enabled }
        return DayZModRelease(
            output = output,
            addons = rows.filter { it.kind == DayZModKind.ADDON }.mapNotNull { path(it.path) }
                .map { DayZAddonPacking.Addon(it, DayZAddonPacking.prefixOf(it, letter)) },
            pbos = rows.filter { it.kind == DayZModKind.PBO }.mapNotNull { path(it.path) },
            keys = keys.mapNotNull { path(it) },
            signKey = path(signKey),
            modCpp = path(modCpp),
            extras = extras.mapNotNull { e ->
                val source = path(e.source) ?: return@mapNotNull null
                DayZModRelease.Extra(source, e.target?.trim()?.ifEmpty { null } ?: source.fileName.toString())
            },
        )
    }

    private fun path(text: String?): Path? = text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }
}

class DayZModBuildConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<DayZModBuildOptions>(project, factory, name) {

    public override fun getOptions(): DayZModBuildOptions = super.getOptions() as DayZModBuildOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = DayZModBuildEditor(project)

    private fun letter(): Char {
        val installation = DayZInstallation.getInstance()
        val paths = installation.configured.orElse(installation.lastDetected ?: installation.configured)
        return WorkDrive.getInstance().letter(paths)
    }

    override fun checkConfiguration() {
        val release = options.toRelease(letter()) ?: throw RuntimeConfigurationError("Set the mod folder to build, such as P:\\Mods\\@MyMod.")
        if (release.addons.isEmpty() && release.pbos.isEmpty()) {
            throw RuntimeConfigurationError("Nothing to build: Rescan Project Addons, or add PBO files.")
        }
        release.signKey?.let { key ->
            if (!Files.isRegularFile(key)) throw RuntimeConfigurationError("The signing key $key does not exist.")
            val public = DayZModReleasePlan.publicKey(key)
            if (!Files.isRegularFile(public)) throw RuntimeConfigurationError("$public, the key servers need, is not next to the signing key.")
        }
        release.modCpp?.let { if (!Files.isRegularFile(it)) throw RuntimeConfigurationError("$it does not exist.") }
        if (release.signKey == null) {
            throw RuntimeConfigurationWarning("No signing key: servers that check signatures will refuse the mod's PBOs.")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                val paths = DayZInstallation.getInstance().paths(project)
                val toolsBin = paths.toolsBin
                if (toolsBin == null || !Files.isRegularFile(toolsBin.resolve(AddonBuilderCommand.EXE))) {
                    throw ExecutionException("Addon Builder was not found. Set the DayZ Tools folder in Settings | Languages & Frameworks | Enforce Script | DayZ Installation.")
                }
                val release = options.toRelease(WorkDrive.getInstance().letter(paths))
                    ?: throw ExecutionException("Set the mod folder to build.")
                if (release.signKey != null && !Files.isRegularFile(toolsBin.resolve(DsUtilsCommand.SIGN_FILE))) {
                    throw ExecutionException("DSSignFile was not found in DayZ Tools, so the mod cannot be signed.")
                }
                val work = Path.of(PathManager.getSystemPath(), "enfusion", "release", "${project.locationHash}-${folderName(name)}")
                val builds = AddonBuilds.getInstance(project)
                val handler = DayZModBuildHandler(release, toolsBin, work) { folder -> builds.get(folder)?.toBuild() }
                ProcessTerminatedListener.attach(handler)
                return handler
            }
        }

    private companion object {
        /** A configuration's name as a folder name. */
        fun folderName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
    }
}
