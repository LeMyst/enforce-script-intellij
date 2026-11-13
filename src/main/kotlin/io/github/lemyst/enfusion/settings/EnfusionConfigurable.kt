package io.github.lemyst.enfusion.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import io.github.lemyst.enfusion.pbo.PboCache
import io.github.lemyst.enfusion.roots.EnfusionRoots
import io.github.lemyst.enfusion.roots.EnfusionVanillaConfigLibrary
import javax.swing.DefaultListModel
import javax.swing.JComponent

/**
 * Settings | Languages & Frameworks | Enforce Script.
 *
 * The list is ordered because order is meaningful: it is the tiebreak when the same class name is
 * declared in more than one root.
 */
class EnfusionConfigurable(private val project: Project) : Configurable {

    private val model = DefaultListModel<String>()
    private val list = JBList(model)
    private var component: JComponent? = null

    override fun getDisplayName(): String = "Enforce Script"

    override fun createComponent(): JComponent {
        reset()
        val toolbar = ToolbarDecorator.createDecorator(list)
            .setAddAction { chooseRoot() }
            .setRemoveAction { removeSelected() }
            .setMoveUpAction { move(-1) }
            .setMoveDownAction { move(1) }
            .createPanel()

        // The UI DSL's text() wraps at a readable line length. A plain JBLabel in a BorderLayout
        // takes its one-line preferred width and is cut off at the settings dialog's default size.
        val panel = panel {
            row {
                text(
                    "Script roots indexed for navigation &mdash; the vanilla DayZ script tree and " +
                        "any dependency mod sources, as folders or packed <code>.pbo</code> files.<br>" +
                        "Add the unpacked game data (<code>P:\\DZ</code>) too to navigate into vanilla " +
                        "configs.<br>Changing this triggers a re-index."
                )
            }
            row { cell(toolbar).align(Align.FILL) }.resizableRow()
        }
        component = panel
        return panel
    }

    /**
     * Folders, or packed `.pbo` files for dependencies that exist only as Workshop PBOs. Several at
     * once, since a mod usually ships a handful of PBOs side by side in its `addons` folder.
     */
    private fun chooseRoot() {
        val descriptor = FileChooserDescriptorFactory.multiFilesOrDirs()
            .withExtensionFilter("pbo")
            .withTitle("Select Enforce Script Source Roots or PBO Files")
        for (chosen in FileChooser.chooseFiles(descriptor, project, null)) {
            val path = chosen.path
            if (model.contains(path)) continue
            if (!chosen.isDirectory && !PboCache.isPbo(path)) continue
            if (EnfusionVanillaConfigLibrary.isGameDataTree(chosen) && !confirmGameDataTree(path)) continue
            model.addElement(path)
        }
    }

    /**
     * Asked when the folder is the unpacked game, before it reaches the list. Only its configs are
     * indexed, but finding them means walking every file in it once, and someone choosing a folder
     * cannot see that from the folder picker.
     */
    private fun confirmGameDataTree(path: String): Boolean =
        Messages.showOkCancelDialog(
            list,
            "${FileUtil.toSystemDependentName(path)} is the unpacked DayZ game data: tens of " +
                "gigabytes in more than 100,000 files.\n\n" +
                "Only its config.cpp files are indexed, so that Ctrl+Click in a config reaches " +
                "vanilla classes. But the IDE has to go through the whole folder once to find them, " +
                "so the first indexing after adding it can take a while.",
            "Add the Game Data Folder?",
            "Add Folder",
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.OK

    private fun removeSelected() {
        list.selectedIndices.sortedDescending().forEach { model.remove(it) }
    }

    private fun move(delta: Int) {
        val index = list.selectedIndex
        val target = index + delta
        if (index < 0 || target < 0 || target >= model.size()) return
        val value = model.remove(index)
        model.add(target, value)
        list.selectedIndex = target
    }

    private fun currentModel(): List<String> = (0 until model.size()).map { model.get(it) }

    override fun isModified(): Boolean {
        val settings = EnfusionProjectSettings.getInstance(project)
        return currentModel() != settings.roots
    }

    override fun apply() {
        EnfusionRoots.apply(project, currentModel())
    }

    override fun reset() {
        model.clear()
        EnfusionProjectSettings.getInstance(project).roots.forEach { model.addElement(it) }
    }

    override fun disposeUIResources() {
        component = null
    }
}
