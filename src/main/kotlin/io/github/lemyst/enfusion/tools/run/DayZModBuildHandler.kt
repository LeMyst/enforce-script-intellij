package io.github.lemyst.enfusion.tools.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import io.github.lemyst.enfusion.tools.detect.AddonBuild
import io.github.lemyst.enfusion.tools.detect.AddonBuilderCommand
import io.github.lemyst.enfusion.tools.detect.DayZAddonPacking
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZModRelease
import io.github.lemyst.enfusion.tools.detect.DayZModReleasePlan
import io.github.lemyst.enfusion.tools.detect.DayZPboStaging
import io.github.lemyst.enfusion.tools.detect.DsUtilsCommand
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A release build of a mod, in one *Run* tab: the addons that changed built by Addon Builder,
 * named, signed by DSSignFile, the other files copied, files no longer part of the mod removed,
 * and the signatures checked. *Stop* stops the running tool and the steps after it.
 *
 * Each addon is built into a folder of its own under [work] and then moved into `addons\` under
 * its release name: Addon Builder names a PBO after its source folder, so `Scripts\Client` and
 * `Other\Client` would both be `Client.pbo`. Signing comes after, so that the signature is made for
 * the name the mod ships.
 */
class DayZModBuildHandler(
    private val release: DayZModRelease,
    private val toolsBin: Path,
    /** The release build's own folder in the IDE's system folder: staging, temp and manifests. */
    private val work: Path,
    /** The saved *Build PBO* options of an addon folder, for its include and exclude lists. */
    private val savedOptions: (Path) -> AddonBuild?,
) : ProcessHandler() {

    @Volatile
    private var stopping = false

    @Volatile
    private var running: ProcessHandler? = null

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread(::build)
    }

    private fun build() {
        var failed = false
        try {
            failed = !buildSteps()
        } catch (e: DayZLaunchCommand.Problem) {
            error(e.message.orEmpty())
            failed = true
        } catch (e: Exception) {
            LOG.info("Release build failed", e)
            error("The build stopped: ${e.message}")
            failed = true
        } finally {
            notifyProcessTerminated(if (failed || stopping) 1 else 0)
        }
    }

    /** Returns whether everything went through. */
    private fun buildSteps(): Boolean {
        val addonsDir = release.output.resolve(DayZModReleasePlan.ADDONS)
        val keysDir = release.output.resolve(DayZModReleasePlan.KEYS)
        val manifests = work.resolve("manifests")
        Files.createDirectories(addonsDir)
        Files.createDirectories(keysDir)
        Files.createDirectories(manifests)
        say("Building ${release.output}")

        val current = release.addons.associateWith { addon ->
            DayZModReleasePlan.manifestOf(addon, DayZAddonPacking.manifest(DayZAddonPacking.files(addon.folder)), release.signKey, release.pboNameOf(addon))
        }
        val plan = DayZModReleasePlan.plan(
            release,
            manifest = { current.getValue(it) },
            previous = { runCatching { Files.readString(manifests.resolve(release.pboNameOf(it) + ".manifest")) }.getOrNull() },
            exists = { Files.exists(it) },
            inAddons = list(addonsDir),
            inKeys = list(keysDir),
            signaturesOf = DayZPboStaging::signaturesOf,
        )
        say("${plan.build.size} addon(s) to build, ${plan.upToDate.size} unchanged.")

        val failed = ArrayList<String>()
        for (addon in plan.build) {
            if (stopping) return false
            if (buildAddon(addon, addonsDir)) {
                Files.writeString(manifests.resolve(release.pboNameOf(addon) + ".manifest"), current.getValue(addon))
            } else {
                failed += release.pboNameOf(addon)
            }
        }
        if (stopping) return false

        for ((source, target) in plan.copies) {
            if (!Files.isRegularFile(source)) {
                error("$source does not exist.")
                failed += source.fileName.toString()
                continue
            }
            Files.createDirectories(target.parent)
            if (!sameFile(source, target)) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                say("Copied ${target.fileName}")
            }
        }
        for (file in plan.delete) {
            Files.deleteIfExists(file)
            say("Removed ${file.fileName}, no longer part of the mod")
        }

        if (release.signKey != null && !stopping) {
            val output = runTool(DsUtilsCommand.check(toolsBin, addonsDir, keysDir), toolsBin)
            val problems = DsUtilsCommand.problems(output)
            if (problems.isEmpty()) say("Signatures checked: all OK.") else problems.forEach { error(it) }
            if (problems.isNotEmpty()) failed += "signatures"
        }
        if (failed.isEmpty()) say("Built ${release.output}.") else error("Failed: ${failed.joinToString(", ")}")
        return failed.isEmpty()
    }

    /** Builds one addon, moves its PBO into `addons\` under its release name, and signs it. */
    private fun buildAddon(addon: DayZAddonPacking.Addon, addonsDir: Path): Boolean {
        val staging = work.resolve("staging").resolve(release.pboNameOf(addon).removeSuffix(".pbo"))
        staging.toFile().deleteRecursively()
        Files.createDirectories(staging)
        val saved = savedOptions(addon.folder)
        val build = AddonBuild(
            source = addon.folder,
            destination = staging,
            prefix = addon.prefix,
            packOnly = false,
            clear = true,
            temp = work.resolve("temp"),
            include = saved?.include,
            exclude = saved?.exclude,
        )
        say("Building ${release.pboNameOf(addon)} from ${addon.folder}")
        val output = runTool(AddonBuilderCommand.commandLine(toolsBin, build), addon.folder)
        if (AddonBuilderCommand.result(output) != AddonBuilderCommand.Result.SUCCEEDED || !Files.isRegularFile(build.pbo)) {
            error("${release.pboNameOf(addon)} failed.")
            return false
        }
        val target = addonsDir.resolve(release.pboNameOf(addon))
        Files.move(build.pbo, target, StandardCopyOption.REPLACE_EXISTING)
        val key = release.signKey ?: return true
        val signature = addonsDir.resolve(DayZModReleasePlan.signatureName(release.pboNameOf(addon), key))
        Files.deleteIfExists(signature)
        runTool(DsUtilsCommand.sign(toolsBin, key, target), toolsBin)
        if (!Files.isRegularFile(signature)) {
            error("${release.pboNameOf(addon)} was built but not signed: no ${signature.fileName}.")
            return false
        }
        return true
    }

    /** Runs a tool to its end, its output in the console, and returns that output. */
    private fun runTool(command: List<String>, directory: Path): String {
        val output = StringBuilder()
        val handler = OSProcessHandler(GeneralCommandLine(command).withWorkDirectory(directory.toFile()))
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType === ProcessOutputTypes.SYSTEM) return
                output.append(event.text)
                notifyTextAvailable(event.text, outputType)
            }
        })
        running = handler
        handler.startNotify()
        while (!handler.waitFor(250)) {
            if (stopping) handler.destroyProcess()
        }
        running = null
        return output.toString()
    }

    /** Same size and date, to the millisecond: a copy's date can keep less precision than the original's. */
    private fun sameFile(source: Path, target: Path): Boolean = DayZPboStaging.sameSizeAndDate(source, target)

    private fun list(dir: Path): List<Path> =
        if (Files.isDirectory(dir)) Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) }.toList() } else emptyList()

    private fun say(line: String) = notifyTextAvailable("$line\n", ProcessOutputTypes.SYSTEM)

    private fun error(line: String) = notifyTextAvailable("$line\n", ProcessOutputTypes.STDERR)

    override fun destroyProcessImpl() {
        stopping = true
        running?.destroyProcess()
    }

    override fun detachProcessImpl() {
        stopping = true
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null

    private companion object {
        val LOG = logger<DayZModBuildHandler>()
    }
}
