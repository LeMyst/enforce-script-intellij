package io.github.lemyst.enfusion.tools

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.lemyst.enfusion.tools.detect.AddonBuilderCommand
import java.nio.file.Path
import javax.swing.JComponent

/**
 * The include and exclude lists of one addon, opened from a release build's PBO table: the only
 * Addon Builder options a release build takes from the addon, the rest being its own. They are
 * saved with the addon's *Build PBO* options, so both builds pack the addon alike.
 */
class AddonListsDialog(private val project: Project, private val entry: AddonBuildState) : DialogWrapper(project) {

    private lateinit var form: DialogPanel

    init {
        title = "Addon Builder Lists: ${Path.of(entry.source!!).fileName}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        form = panel {
            row("Include list:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Include List"), project)
                    .columns(COLUMNS_LARGE).align(AlignX.FILL)
                    .bindText({ entry.include ?: "" }, { entry.include = it.trim().ifEmpty { null } })
                    .comment(
                        "A file of patterns copied into the PBO as they are, separated by ; or ,. Empty: " +
                            AddonBuilderCommand.DEFAULT_INCLUDE.joinToString(";"),
                    )
            }
            row("Exclude list:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Exclude List"), project)
                    .columns(COLUMNS_LARGE).align(AlignX.FILL)
                    .bindText({ entry.exclude ?: "" }, { entry.exclude = it.trim().ifEmpty { null } })
                    .comment("A file of patterns left out of the PBO, like DayZ Tools' exclude.lst.")
            }
            row {
                comment("Saved for this addon in .idea/enfusion-builds.xml, where Build PBO keeps its options too.")
            }
        }
        return form
    }

    override fun doOKAction() {
        form.apply()
        super.doOKAction()
    }

    companion object {
        /** Shows the lists of the addon at [folder], and saves them when confirmed. */
        fun edit(project: Project, folder: Path) {
            val builds = AddonBuilds.getInstance(project)
            val entry = AddonBuildState()
            val saved = builds.get(folder)
            saved?.let { entry.copyFrom(it) }
            // The saved path stays: it is the folder Build PBO builds from.
            entry.source = saved?.source ?: folder.toString()
            if (AddonListsDialog(project, entry).showAndGet()) builds.save(entry)
        }
    }
}
