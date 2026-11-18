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
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.SystemInfo
import io.github.lemyst.enfusion.tools.DayZInstallation
import io.github.lemyst.enfusion.tools.SteamCmdDownload
import io.github.lemyst.enfusion.tools.detect.WorkshopPublish
import java.nio.file.Files
import java.nio.file.Path

/**
 * *Run | Edit Configurations | DayZ Workshop Publish*: uploads a built `@MyMod` folder to the
 * Steam Workshop through SteamCMD's `workshop_build_item`.
 *
 * Its own configuration rather than a step of *DayZ Mod Build*: a build runs many times for each
 * upload, and an upload cannot be undone, so it is never one click away from a build. To build
 * first, add the mod's *DayZ Mod Build* under *Before launch*.
 *
 * Nothing runs before a confirmation that names the item, the folder and its size, the visibility
 * and the change note. SteamCMD runs in a console window of its own, where its password and
 * Steam Guard prompts are answered the first time; the plugin stores only the account name.
 */
class DayZWorkshopPublishConfigurationType : ConfigurationTypeBase(
    ID,
    "DayZ Workshop Publish",
    "Upload a built mod to the Steam Workshop with SteamCMD",
    NotNullLazyValue.createValue { AllIcons.Actions.Upload },
) {
    init {
        addFactory(DayZWorkshopPublishConfigurationFactory(this))
    }

    companion object {
        const val ID = "EnfusionDayZWorkshopPublish"
    }
}

class DayZWorkshopPublishConfigurationFactory(type: DayZWorkshopPublishConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = DayZWorkshopPublishConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        DayZWorkshopPublishConfiguration(project, this, "Publish Mod")

    override fun getOptionsClass(): Class<out BaseState> = DayZWorkshopPublishOptions::class.java
}

class DayZWorkshopPublishOptions : RunConfigurationOptions() {
    /** The built `@MyMod` folder, uploaded as the item's content. */
    var contentFolder by string()
    /** The Steam account SteamCMD logs in with. Only its name: never a password. */
    var account by string()
    /** Empty to create a new item, filled in with the new ID after the first upload. */
    var itemId by string()
    var title by string()
    var itemDescription by string()
    /** A [WorkshopPublish.Visibility] code, or -1 to keep the item's. */
    var visibility by property(-1)
    var previewFile by string()
    var changeNote by string()
    /** A changelog whose first section is the change note, when [changeNote] is empty. */
    var changelogFile by string()

    fun path(text: String?): Path? = text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }

    /** The change note: the field, else the first section of the changelog file, else empty. */
    fun resolvedChangeNote(): String {
        changeNote?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val file = path(changelogFile)?.takeIf { Files.isRegularFile(it) } ?: return ""
        return WorkshopPublish.changeNoteFrom(Files.readString(file))
    }

    fun toItem(): WorkshopPublish.Item? {
        val content = path(contentFolder) ?: return null
        return WorkshopPublish.Item(
            publishedFileId = itemId?.trim()?.ifEmpty { null },
            contentFolder = content,
            previewFile = path(previewFile),
            visibility = WorkshopPublish.Visibility.ofCode(visibility),
            title = title?.trim()?.ifEmpty { null },
            description = itemDescription?.ifEmpty { null },
            changeNote = resolvedChangeNote(),
        )
    }
}

class DayZWorkshopPublishConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<DayZWorkshopPublishOptions>(project, factory, name) {

    public override fun getOptions(): DayZWorkshopPublishOptions = super.getOptions() as DayZWorkshopPublishOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = DayZWorkshopPublishEditor(project)

    override fun checkConfiguration() {
        if (!SystemInfo.isWindows) throw RuntimeConfigurationError("Publishing runs SteamCMD's Windows console.")
        val o = options
        val content = o.path(o.contentFolder) ?: throw RuntimeConfigurationError("Set the built mod folder to upload, such as P:\\Mods\\@MyMod.")
        if (!Files.isDirectory(content)) throw RuntimeConfigurationError("$content does not exist: build the mod first.")
        if (!Files.isDirectory(content.resolve("addons")) && !Files.isDirectory(content.resolve("Addons"))) {
            throw RuntimeConfigurationError("$content has no addons folder: build the mod first.")
        }
        // A path of this machine, kept in the DayZ settings rather than here: a run configuration
        // can be shared through the project, and another machine's SteamCMD is elsewhere.
        val steamCmd = DayZInstallation.getInstance().steamCmdExe()
        if (steamCmd == null || !Files.isRegularFile(steamCmd)) {
            throw RuntimeConfigurationError(
                "SteamCMD not found. It is Valve's command-line Steam client: Fix downloads it, or set its folder in " +
                    "Settings | Languages & Frameworks | Enforce Script | DayZ Installation.",
            ).apply { setQuickFix(Runnable { SteamCmdDownload.run(project, null) }) }
        }
        val account = o.account?.trim().orEmpty()
        if (!WorkshopPublish.isAccountName(account)) throw RuntimeConfigurationError("Set the Steam account name SteamCMD logs in with.")
        if (o.itemId.isNullOrBlank() && o.title.isNullOrBlank()) throw RuntimeConfigurationError("A new Workshop item needs a title.")
        o.path(o.previewFile)?.let { if (!Files.isRegularFile(it)) throw RuntimeConfigurationError("The preview image $it does not exist.") }
        if (o.resolvedChangeNote().isBlank()) throw RuntimeConfigurationWarning("No change note: the item's Change Notes tab gets an empty entry.")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val item = options.toItem() ?: throw ExecutionException("Set the built mod folder to upload.")
        val (files, bytes) = WorkshopPublish.contentSize(item.contentFolder)
        // An upload cannot be undone: nothing runs before this is answered.
        var confirmed = false
        ApplicationManager.getApplication().invokeAndWait({
            confirmed = Messages.showOkCancelDialog(
                project,
                WorkshopPublish.summary(item, bytes, files),
                "Publish to the Steam Workshop",
                "Publish",
                Messages.getCancelButton(),
                Messages.getWarningIcon(),
            ) == Messages.OK
        }, ModalityState.any())
        if (!confirmed) return null
        return PublishState(environment, item)
    }

    private inner class PublishState(environment: ExecutionEnvironment, private val item: WorkshopPublish.Item) : CommandLineState(environment) {

        override fun startProcess(): ProcessHandler {
            val steamCmd = DayZInstallation.getInstance().steamCmdExe() ?: throw ExecutionException("SteamCMD not found: set it in the DayZ Installation settings.")
            val account = options.account?.trim().orEmpty()
            val work = Path.of(PathManager.getSystemPath(), "enfusion", "workshop", "${project.locationHash}-${folderName(name)}")
            Files.createDirectories(work)
            val vdf = work.resolve("workshop_item.vdf")
            val result = work.resolve("result.txt")
            val batch = work.resolve("publish.bat")
            Files.deleteIfExists(result)
            Files.writeString(vdf, WorkshopPublish.vdf(item))
            Files.writeString(batch, WorkshopPublish.batch(steamCmd, account, vdf, result))
            // SteamCMD appends to its log: only what this run adds is about this upload.
            val log = WorkshopPublish.workshopLog(steamCmd)
            val logStart = runCatching { Files.size(log) }.getOrDefault(0L)

            val handler = OSProcessHandler(GeneralCommandLine(WorkshopPublish.commandLine(batch)).withWorkDirectory(work.toFile()))
            handler.addProcessListener(object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {
                    handler.notifyTextAvailable(
                        "SteamCMD is running in its own window. The first time, type the password and the Steam Guard code there;\n" +
                            "SteamCMD keeps the login after that. This tab finishes when that window is closed.\n",
                        ProcessOutputTypes.SYSTEM,
                    )
                }

                override fun processTerminated(event: ProcessEvent) {
                    val code = WorkshopPublish.exitCode(runCatching { Files.readString(result) }.getOrNull())
                    val upload = runCatching { WorkshopPublish.lastUpload(appendedSince(log, logStart)) }.getOrNull()
                    val id = upload?.itemId ?: runCatching { WorkshopPublish.publishedFileId(Files.readString(vdf)) }.getOrNull()
                    val succeeded = upload?.succeeded ?: (code == 0)
                    val message = when {
                        upload != null && upload.succeeded -> "Uploaded: https://steamcommunity.com/sharedfiles/filedetails/?id=$id\n"
                        upload != null -> "SteamCMD reported ${upload.result} for item ${upload.itemId}: see its window for why.\n"
                        code == null -> "SteamCMD did not finish: its window was closed early. Nothing is known about the upload.\n"
                        code != 0 -> "SteamCMD failed with code $code: see its window for why.\n"
                        id != null -> "Uploaded: https://steamcommunity.com/sharedfiles/filedetails/?id=$id\n"
                        else -> "SteamCMD finished, but wrote no item ID back.\n"
                    }
                    // On a line of its own: closing SteamCMD's window can leave a `^C` before it.
                    handler.notifyTextAvailable("\n" + message, if (succeeded) ProcessOutputTypes.SYSTEM else ProcessOutputTypes.STDERR)
                    // A new item: remember its ID, so the next upload updates it instead of making another.
                    if (succeeded && id != null && options.itemId.isNullOrBlank()) {
                        ApplicationManager.getApplication().invokeLater({ options.itemId = id }, ModalityState.any())
                    }
                }
            })
            return handler
        }
    }

    private companion object {
        fun folderName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)

        /** What [file] gained past [start]; all of it if it is now shorter, as after a rotation. */
        fun appendedSince(file: Path, start: Long): String {
            Files.newByteChannel(file).use { channel ->
                channel.position(if (channel.size() < start) 0 else start)
                return String(java.nio.channels.Channels.newInputStream(channel).readAllBytes(), Charsets.UTF_8)
            }
        }
    }
}
