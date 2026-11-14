package io.github.lemyst.enfusion.tools

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import io.github.lemyst.enfusion.settings.EnfusionProjectSettings
import io.github.lemyst.enfusion.tools.detect.DayZDetector
import io.github.lemyst.enfusion.tools.detect.DayZPaths
import io.github.lemyst.enfusion.tools.detect.SteamLibraries
import java.nio.file.Path

class DayZPathsState : BaseState() {
    var game by string()
    var server by string()
    var tools by string()
    var workDriveSource by string()
    var workDriveLetter by string()
    var clientProfile by string()
    var serverProfile by string()
}

/**
 * Where DayZ, its server and DayZ Tools are on this machine, for every tooling feature beyond
 * navigation: mounting the work drive, building and publishing PBOs, launching the game, reading
 * its logs.
 *
 * The paths are per machine, so they live at application level in a file that does not roam
 * (`RoamingType.DISABLED`), never in `.idea/enfusion.xml`, which a cloned repository carries to
 * another machine. Only the server profile can be overridden per project, since it is the one
 * folder a project chooses.
 *
 * A field left empty falls back to the detected location. Detection reads Steam's records and
 * DayZ Tools' `settings.ini`, so it follows a game moved to another library without a settings
 * change. It runs once per session, off the EDT, and again when the settings page asks.
 */
@Service(Service.Level.APP)
@State(name = "EnfusionDayZPaths", storages = [Storage("enfusion-dayz.xml", roamingType = RoamingType.DISABLED)])
class DayZInstallation : SimplePersistentStateComponent<DayZPathsState>(DayZPathsState()) {

    @Volatile
    private var detected: DayZPaths? = null

    /** The paths the user set, with nothing detected filled in. */
    val configured: DayZPaths
        get() = DayZPaths(
            game = path(state.game),
            server = path(state.server),
            tools = path(state.tools),
            workDriveSource = path(state.workDriveSource),
            workDriveLetter = state.workDriveLetter?.trim()?.trimEnd(':')?.uppercase()?.takeIf { it.isNotEmpty() },
            clientProfile = path(state.clientProfile),
            serverProfile = path(state.serverProfile),
        )

    /** The last detection's result, or null when detection has not run in this session yet. */
    val lastDetected: DayZPaths? get() = detected

    /**
     * Detects again and remembers the result. Reads the disk and starts `reg.exe`, so never on the
     * EDT.
     */
    fun detect(): DayZPaths {
        val result = runDetection()
        detected = result
        return result
    }

    /**
     * The paths to use: the ones set in the settings, else the detected ones, with the project's
     * server profile over both. Detects on first use, so not on the EDT either.
     *
     * The project override is read only once the project is trusted: it comes from
     * `.idea/enfusion.xml`, which a cloned repository can ship, and a UNC path in it would make
     * Windows contact that host when a log viewer opens it (see `EnfusionRoots.configuredRoots`).
     */
    fun paths(project: Project?): DayZPaths {
        val base = configured.orElse(detected ?: detect())
        val override = project
            ?.takeIf { TrustedProjects.isProjectTrusted(it) }
            ?.let { path(EnfusionProjectSettings.getInstance(it).serverProfile) }
        return if (override != null) base.copy(serverProfile = override) else base
    }

    companion object {
        fun getInstance(): DayZInstallation = service()

        private val LOG = logger<DayZInstallation>()

        private fun path(value: String?): Path? =
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }

        /** DayZ and DayZ Tools exist for Windows only; elsewhere nothing is detected. */
        private fun runDetection(): DayZPaths {
            if (!SystemInfo.isWindows) return DayZPaths()
            val roots = DayZDetector.steamRootCandidates(
                registrySteamPath = registrySteamPath(),
                programFilesX86 = System.getenv("ProgramFiles(x86)"),
                programFiles = System.getenv("ProgramFiles"),
            )
            val localAppData = System.getenv("LOCALAPPDATA")?.let { runCatching { Path.of(it) }.getOrNull() }
            return DayZDetector.detect(roots, localAppData)
        }

        /**
         * Steam's folder from `HKCU\Software\Valve\Steam`. Read with `reg.exe` because the
         * platform's registry reader is internal API.
         */
        private fun registrySteamPath(): String? = try {
            val command = GeneralCommandLine("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath")
            val output = CapturingProcessHandler(command).runProcess(5_000)
            if (output.exitCode == 0 && !output.isTimeout) SteamLibraries.parseRegQuery(output.stdout, "SteamPath") else null
        } catch (e: Exception) {
            LOG.info("Could not read Steam's path from the registry", e)
            null
        }
    }
}
