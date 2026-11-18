package io.github.lemyst.enfusion.tools

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.HttpRequests
import io.github.lemyst.enfusion.tools.detect.SteamCmdInstall
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Downloads SteamCMD from Valve into its own folder and records that folder in the DayZ settings,
 * for every project on this machine. Publishing to the Workshop needs it, and few modders have it.
 */
object SteamCmdDownload {

    /**
     * Asks first, naming the source and the folder, then downloads with a modal progress. Returns
     * the folder holding `steamcmd.exe`, or null when the user declined, cancelled, or it failed
     * (the error is shown). [folder] defaults to the one detection also looks in.
     */
    fun run(project: Project?, parent: JComponent?, folder: Path? = null): Path? {
        val target = folder ?: SteamCmdInstall.defaultFolder(DayZInstallation.localAppData())
        if (target == null) {
            error(project, parent, "No folder to install SteamCMD into: LOCALAPPDATA is not set.")
            return null
        }
        val host = URI(SteamCmdInstall.DOWNLOAD_URL).host
        val shown = FileUtil.toSystemDependentName(target.toString())
        val message = "Download SteamCMD, Valve's command-line Steam client, from $host into\n$shown?\n\n" +
            "On its first start, while publishing, SteamCMD updates itself in its own window."
        val confirmed = MessageDialogBuilder.okCancel(TITLE, message).yesText("Download")
            .let { if (parent != null) it.ask(parent) else it.ask(project) }
        if (!confirmed) return null

        val task = object : Task.WithResult<Path, IOException>(project, parent, "Downloading SteamCMD", true) {
            override fun compute(indicator: ProgressIndicator): Path {
                indicator.isIndeterminate = false
                Files.createDirectories(target)
                val archive = Files.createTempFile(target, "steamcmd", ".zip")
                try {
                    HttpRequests.request(SteamCmdInstall.DOWNLOAD_URL).productNameAsUserAgent().saveToFile(archive, indicator)
                    indicator.checkCanceled()
                    Files.newInputStream(archive).use { SteamCmdInstall.extract(it, target) }
                } finally {
                    Files.deleteIfExists(archive)
                }
                return target
            }
        }
        val installed = try {
            ProgressManager.getInstance().run(task)
        } catch (_: ProcessCanceledException) {
            return null
        } catch (e: IOException) {
            error(project, parent, "SteamCMD could not be downloaded: ${e.message ?: e.javaClass.simpleName}")
            return null
        }
        DayZInstallation.getInstance().state.steamCmd = installed.toString()
        return installed
    }

    private fun error(project: Project?, parent: JComponent?, text: String) {
        if (parent != null) Messages.showErrorDialog(parent, text, TITLE) else Messages.showErrorDialog(project, text, TITLE)
    }

    private const val TITLE = "Download SteamCMD"
}
