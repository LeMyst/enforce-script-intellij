package io.github.lemyst.enfusion.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.lemyst.enfusion.settings.EnfusionProjectSettings
import io.github.lemyst.enfusion.tools.detect.DayZPathChecks
import io.github.lemyst.enfusion.tools.detect.DayZPaths
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JLabel
import javax.swing.JTextField
import kotlin.reflect.KMutableProperty0

/**
 * Settings | Languages & Frameworks | Enforce Script | DayZ Installation.
 *
 * Every field may stay empty: the detected location then shows in grey and is the one used. A
 * path that does not look right gets a warning, not an error, so that a drive that is not
 * connected right now does not stop the settings from being saved.
 */
class DayZPathsConfigurable(private val project: Project) : BoundConfigurable("DayZ Installation") {

    private val installation = DayZInstallation.getInstance()

    /** Each field with what its placeholder shows once detection is done. */
    private val placeholders = ArrayList<Pair<JTextField, (DayZPaths) -> String?>>()
    private val status = JLabel(" ")

    override fun createPanel(): DialogPanel {
        placeholders.clear()
        val state = installation.state
        val panel = panel {
            row {
                text(
                    "Where DayZ and DayZ Tools are on this machine, for building, launching and the " +
                        "work drive. Leave a field empty to use the location detected from Steam and " +
                        "DayZ Tools, shown in grey. These paths belong to this machine and are not " +
                        "saved in the project."
                )
            }
            group("Installs") {
                folderRow("DayZ:", state::game, DayZPathChecks::game, { it.game }, "Contains ${DayZPaths.GAME_EXE} and ${DayZPaths.GAME_DIAG_EXE}.")
                folderRow("DayZ Server:", state::server, DayZPathChecks::server, { it.server }, "Contains ${DayZPaths.SERVER_EXE}. Optional.")
                folderRow("DayZ Tools:", state::tools, DayZPathChecks::tools, { it.tools }, "Contains the Bin folder with WorkDrive, AddonBuilder and Publisher.")
            }
            group("Work Drive") {
                folderRow("Source folder:", state::workDriveSource, DayZPathChecks::folder, { it.workDriveSource }, "The folder DayZ Tools mounts as the work drive.")
                row("Drive letter:") {
                    val field = textField()
                        .columns(3)
                        .bindText({ state.workDriveLetter ?: "" }, { state.workDriveLetter = it.trim().trimEnd(':').uppercase().ifEmpty { null } })
                        .validationOnInput { f ->
                            f.text.trim().trimEnd(':').takeIf { it.isNotEmpty() }?.let(DayZPathChecks::driveLetter)?.let { warning(it) }
                        }
                    placeholders += field.component to { it.workDriveLetter }
                }
            }
            group("Profiles") {
                folderRow("Client profile:", state::clientProfile, DayZPathChecks::folder, { it.clientProfile }, "Where the game writes script_*.log, *.RPT and crash_*.log.")
                folderRow("Server profile:", state::serverProfile, DayZPathChecks::folder, { null }, "The folder passed to the server as -profiles=.")
                val settings = EnfusionProjectSettings.getInstance(project)
                row("For this project:") {
                    val field = folderField("Server profile for this project")
                        .bindText({ settings.serverProfile ?: "" }, { settings.serverProfile = it })
                    field.comment("Overrides the server profile above for this project only. Saved in .idea/enfusion.xml.")
                }
            }
            row {
                button("Detect Again") { detectInBackground() }
                cell(status)
            }
        }
        installation.lastDetected?.let(::showDetected) ?: detectInBackground()
        return panel
    }

    private fun Panel.folderRow(
        label: String,
        property: KMutableProperty0<String?>,
        check: (Path) -> String?,
        detected: (DayZPaths) -> Path?,
        comment: String,
    ) {
        row(label) {
            val cell = folderField(label.removeSuffix(":"))
                .bindText({ property.get() ?: "" }, { property.set(it.trim().ifEmpty { null }) })
                .validationOnInput { f ->
                    val text = f.text.trim()
                    if (text.isEmpty()) return@validationOnInput null
                    val problem = try {
                        check(Path.of(text))
                    } catch (_: InvalidPathException) {
                        "Not a valid path"
                    }
                    problem?.let { warning(it) }
                }
                .comment(comment)
            placeholders += cell.component.textField to { paths -> detected(paths)?.let { FileUtil.toSystemDependentName(it.toString()) } }
        }
    }

    private fun com.intellij.ui.dsl.builder.Row.folderField(title: String) =
        textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir().withTitle(title), project)
            .columns(COLUMNS_LARGE)
            .align(AlignX.FILL)

    private fun detectInBackground() {
        status.text = "Detecting…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = installation.detect()
            ApplicationManager.getApplication().invokeLater({ showDetected(result) }, ModalityState.any())
        }
    }

    private fun showDetected(paths: DayZPaths) {
        for ((field, value) in placeholders) {
            (field as? JBTextField)?.emptyText?.text = value(paths)?.let { "Detected: $it" } ?: "Not detected"
            field.repaint()
        }
        val found = listOfNotNull(
            paths.game?.let { "DayZ" },
            paths.server?.let { "DayZ Server" },
            paths.tools?.let { "DayZ Tools" },
            paths.workDriveSource?.let { "the work drive folder" },
        )
        status.text = if (found.isEmpty()) "Nothing detected: is DayZ installed through Steam?" else "Detected ${found.joinToString(", ")}."
    }
}
