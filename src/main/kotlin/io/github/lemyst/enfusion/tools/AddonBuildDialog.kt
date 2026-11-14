package io.github.lemyst.enfusion.tools

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.lemyst.enfusion.tools.detect.AddonBuilderCommand
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Addon Builder's options for one addon, as its own window has them. What is entered here is saved
 * for the addon, so the next *Build PBO* runs without asking.
 */
class AddonBuildDialog(
    private val project: Project,
    private val entry: AddonBuildState,
) : DialogWrapper(project) {

    private lateinit var form: DialogPanel

    init {
        title = "Build PBO: ${Path.of(entry.source!!).fileName}"
        setOKButtonText("Build")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val source = Path.of(entry.source!!)
        val detectedPrefix = AddonBuilderCommand.prefixOf(source)
        form = panel {
            row("Addon folder:") { label(source.toString()) }
            row("Output folder:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir().withTitle("Output Folder"), project)
                    .columns(COLUMNS_LARGE).align(AlignX.FILL)
                    .bindText({ entry.destination ?: "" }, { entry.destination = it.trim().ifEmpty { null } })
                    .comment("Usually the Addons folder of the @Mod you load in the game.")
                    .validationOnApply { if (it.text.isBlank()) error("Choose where the PBO goes") else null }
            }
            row("Prefix:") {
                val field = textField().columns(COLUMNS_LARGE).align(AlignX.FILL)
                    .bindText({ entry.prefix ?: "" }, { entry.prefix = it.trim().ifEmpty { null } })
                (field.component as JBTextField).emptyText.text = detectedPrefix?.let { "From \$PBOPREFIX\$: $it" } ?: "Worked out by Addon Builder"
            }
            row("Signing key:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile().withExtensionFilter("biprivatekey").withTitle("Private Key"),
                    project,
                ).columns(COLUMNS_LARGE).align(AlignX.FILL)
                    .bindText({ entry.signKey ?: "" }, { entry.signKey = it.trim().ifEmpty { null } })
                    .comment("Optional .biprivatekey. Only its path is saved.")
                    .validationOnApply {
                        val text = it.text.trim()
                        if (text.isNotEmpty() && !Files.isRegularFile(Path.of(text))) error("Key file not found") else null
                    }
            }
            row { checkBox("Pack only (no binarisation)").bindSelected({ entry.packOnly }, { entry.packOnly = it }) }
            row { checkBox("Clear the temp folder first").bindSelected({ entry.clear }, { entry.clear = it }) }
            collapsibleGroup("More Options") {
                row("Temp folder:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir().withTitle("Temp Folder"), project)
                        .columns(COLUMNS_LARGE).align(AlignX.FILL)
                        .bindText({ entry.temp ?: "" }, { entry.temp = it.trim().ifEmpty { null } })
                }
                row("Exclude list:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Exclude List"), project)
                        .columns(COLUMNS_LARGE).align(AlignX.FILL)
                        .bindText({ entry.exclude ?: "" }, { entry.exclude = it.trim().ifEmpty { null } })
                        .comment("A file of patterns left out of the PBO, like DayZ Tools' exclude.lst.")
                }
                row("Include list:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Include List"), project)
                        .columns(COLUMNS_LARGE).align(AlignX.FILL)
                        .bindText({ entry.include ?: "" }, { entry.include = it.trim().ifEmpty { null } })
                        .comment("A file of patterns copied into the PBO as they are.")
                }
            }
        }
        return form
    }

    override fun doOKAction() {
        form.apply()
        super.doOKAction()
    }
}
