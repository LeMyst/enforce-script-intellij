package io.github.lemyst.enfusion.tools.run

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.github.lemyst.enfusion.tools.detect.DayZMods
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/**
 * *Add Mods…*: the `@` folders of each source (the configuration's mods folder, the game's
 * `!Workshop`), one checkbox each, grouped by source. Mods already in the list are shown ticked and
 * cannot be unticked here: removing is the table's job.
 */
class DayZModPickerDialog private constructor(
    project: Project,
    private val sources: List<Pair<String, List<Path>>>,
    private val listed: Set<String>,
) : DialogWrapper(project) {

    private val boxes = ArrayList<Pair<JBCheckBox, Path>>()

    init {
        title = "Add Mods"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val content = panel {
            if (sources.all { it.second.isEmpty() }) {
                row { label("No @ folder in the mods folder or the game's !Workshop.") }
            }
            for ((title, mods) in sources) {
                if (mods.isEmpty()) continue
                group(title) {
                    for (mod in mods) {
                        val already = DayZMods.key(mod.toString()) in listed
                        val box = JBCheckBox(mod.fileName.toString(), already).apply { isEnabled = !already }
                        if (!already) boxes += box to mod
                        row { cell(box) }
                    }
                }
            }
        }
        return JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            preferredSize = JBUI.size(420, 460)
        }
    }

    private fun chosen(): List<String> = boxes.filter { it.first.isSelected }.map { it.second.toString() }

    companion object {
        /**
         * The mods ticked, as paths, or null when cancelled. [sources] are a title and a folder;
         * a folder that is not there is left out.
         */
        fun choose(project: Project, sources: List<Pair<String, String>>, listed: Set<String>): List<String>? {
            val found = sources.map { (title, folder) -> title to modsIn(folder) }
            val dialog = DayZModPickerDialog(project, found, listed)
            return if (dialog.showAndGet()) dialog.chosen() else null
        }

        private fun modsIn(folder: String): List<Path> = runCatching {
            Files.list(Path.of(folder)).use { s ->
                s.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("@") }
                    .sorted(compareBy { it.fileName.toString().lowercase() })
                    .toList()
            }
        }.getOrDefault(emptyList())
    }
}
