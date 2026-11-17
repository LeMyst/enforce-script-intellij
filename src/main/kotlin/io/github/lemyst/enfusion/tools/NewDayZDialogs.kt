package io.github.lemyst.enfusion.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.github.lemyst.enfusion.tools.detect.DayZModTemplates
import io.github.lemyst.enfusion.tools.detect.DayZModTemplates.Module
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * *New DayZ Addon*: the folder, the `CfgPatches` class, the prefix, `requiredAddons[]` and the
 * script modules. The class and the prefix follow the folder's name until they are edited.
 */
internal class NewDayZAddonDialog(
    project: Project,
    private val parent: VirtualFile,
    private val context: NewDayZContext,
    modName: String,
) : DialogWrapper(project) {

    private val modFolder = context.parentPrefix.substringAfterLast('\\').ifEmpty { parent.name }
    private val folderField = JBTextField(if (parent.findChild("Scripts") == null) "Scripts" else "")
    private val nameField = JBTextField()
    private val prefixField = JBTextField()
    private val modNameField = JBTextField(modName)
    private val definesField = JBTextField()
    private val pboPrefix = JBCheckBox("Write a \$PBOPREFIX\$ file", true)
    private val moduleBoxes = Module.entries.associateWith { JBCheckBox(it.folder, it in DEFAULT_MODULES) }
    private val search = SearchTextField(false)
    private val required = CheckBoxList<String>()

    /** The addons ticked, kept while the list is filtered. */
    private val ticked = LinkedHashSet<String>()
    private var nameEdited = false
    private var prefixEdited = false
    private var updating = false

    init {
        title = "New DayZ Addon"
        ticked += "DZ_Data"
        if (moduleBoxes.any { it.value.isSelected }) ticked += "DZ_Scripts"
        follow()
        folderField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = follow()
        })
        nameField.document.addDocumentListener(edited { nameEdited = true })
        prefixField.document.addDocumentListener(edited { prefixEdited = true })
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = fill()
        })
        required.setCheckBoxListListener { index, value ->
            val item = required.getItemAt(index) ?: return@setCheckBoxListListener
            if (value) ticked += item else ticked -= item
        }
        // A script addon needs the vanilla scripts: ticking its first module ticks DZ_Scripts.
        moduleBoxes.values.forEach { box ->
            box.addItemListener {
                if (box.isSelected && "DZ_Scripts" !in ticked) {
                    ticked += "DZ_Scripts"
                    fill()
                }
            }
        }
        fill()
        init()
        initValidation()
    }

    private fun edited(mark: () -> Unit) = object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
            if (!updating) mark()
        }
    }

    /** The class and the prefix, from the folder, unless they were edited. */
    private fun follow() {
        updating = true
        try {
            val folder = folderField.text.trim()
            if (!nameEdited) nameField.text = DayZModTemplates.addonName(modFolder, folder)
            if (!prefixEdited) prefixField.text = context.prefixOf(folder)
        } finally {
            updating = false
        }
    }

    /** The addons matching the search, ticked ones first. */
    private fun fill() {
        val filter = search.text.trim()
        val shown = (ticked.toList() + context.addons.filter { it !in ticked })
            .filter { filter.isEmpty() || it.contains(filter, ignoreCase = true) }
        required.clear()
        for (name in shown) required.addItem(name, name, name in ticked)
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Folder:") {
            cell(folderField).columns(COLUMNS_LARGE).align(AlignX.FILL)
                .comment("Created in ${parent.presentableUrl}")
        }
        row("CfgPatches class:") { cell(nameField).columns(COLUMNS_LARGE).align(AlignX.FILL) }
        row("Prefix:") {
            cell(prefixField).columns(COLUMNS_LARGE).align(AlignX.FILL)
                .comment("The addon's path in the game's files. The script modules' files[] start with it.")
        }
        row { cell(pboPrefix) }
        group("Script Modules") {
            row { moduleBoxes.values.forEach { cell(it) } }
            row { comment("Each ticked module gets its folder, an entry under CfgMods, and a first script.") }
        }
        group("requiredAddons[]") {
            row { cell(search).align(AlignX.FILL) }
            row {
                val scroll = JBScrollPane(required)
                scroll.preferredSize = Dimension(JBUI.scale(420), JBUI.scale(150))
                cell(scroll).align(AlignX.FILL)
            }
            row { comment("The addons loaded before this one: vanilla's DZ_Data and DZ_Scripts, and the mods it builds on.") }
        }
        collapsibleGroup("CfgMods") {
            row("Mod name:") {
                cell(modNameField).columns(COLUMNS_LARGE).align(AlignX.FILL)
                    .comment("Shown by the launcher; the class name when empty.")
            }
            row("Defines:") {
                cell(definesField).columns(COLUMNS_LARGE).align(AlignX.FILL)
                    .comment("Optional #ifdef flags the mod sets, separated by spaces or commas.")
            }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = folderField

    override fun doValidate(): ValidationInfo? {
        val folder = folderField.text.trim()
        if (folder.isEmpty()) return ValidationInfo("Enter the addon's folder", folderField)
        if (folder.contains('/') || folder.contains('\\')) return ValidationInfo("One folder name, without slashes", folderField)
        parent.findChild(folder)?.findChild("config.cpp")?.let { return ValidationInfo("$folder already has a config.cpp", folderField) }
        DayZModTemplates.classNameProblem(nameField.text.trim())?.let { return ValidationInfo(it, nameField) }
        DayZModTemplates.prefixProblem(prefixField.text.trim())?.let { return ValidationInfo(it, prefixField) }
        definesField.text.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }
            .firstOrNull { DayZModTemplates.classNameProblem(it) != null }
            ?.let { return ValidationInfo("'$it' is not a define name", definesField) }
        return null
    }

    fun spec(): NewDayZFiles.AddonSpec = NewDayZFiles.AddonSpec(
        folder = folderField.text.trim(),
        addon = DayZModTemplates.Addon(
            name = nameField.text.trim(),
            prefix = prefixField.text.trim().replace('/', '\\').trim('\\'),
            requiredAddons = ticked.toList(),
            modules = moduleBoxes.filterValues { it.isSelected }.keys,
            modName = modNameField.text.trim(),
            defines = definesField.text.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() },
        ),
        pboPrefix = pboPrefix.isSelected,
    )

    private companion object {
        val DEFAULT_MODULES = setOf(Module.GAME, Module.WORLD, Module.MISSION)
    }
}

/** *New DayZ Mod*: the folder, what `mod.cpp` says, and the files to start the repository with. */
internal class NewDayZModDialog(project: Project, private val parent: VirtualFile) : DialogWrapper(project) {

    private val folderField = JBTextField(if (parent.findChild("mod.cpp") == null && parent.children.isEmpty()) "" else "MyMod")
    private val nameField = JBTextField()
    private val authorField = JBTextField()
    private val versionField = JBTextField("1.0")
    private val tooltipField = JBTextField()
    private val overviewField = JBTextArea(3, 40).apply { lineWrap = true; wrapStyleWord = true }
    private val actionField = JBTextField()
    private val licenses = DayZModTemplates.License.entries.filter { it != DayZModTemplates.License.GPL3 || NewDayZFiles.gplText() != null }
    private val licenseBox = ComboBox(licenses.toTypedArray())
    private val readme = JBCheckBox("README.md", true)
    private val gitignore = JBCheckBox(".gitignore, for built PBOs, private keys, logs and the IDE's folder", true)
    private val firstAddonBox = JBCheckBox("Then create its first addon", true)

    init {
        title = "New DayZ Mod"
        nameField.emptyText.text = "The folder's name"
        actionField.emptyText.text = "https://…"
        init()
        initValidation()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Folder:") {
            cell(folderField).columns(COLUMNS_LARGE).align(AlignX.FILL)
                .comment("Created in ${parent.presentableUrl}. Leave it empty to use that folder itself.")
        }
        group("mod.cpp") {
            row("Name:") { cell(nameField).columns(COLUMNS_LARGE).align(AlignX.FILL) }
            row("Author:") { cell(authorField).columns(COLUMNS_LARGE).align(AlignX.FILL) }
            row("Version:") { cell(versionField).columns(COLUMNS_LARGE).align(AlignX.FILL) }
            row("Tooltip:") { cell(tooltipField).columns(COLUMNS_LARGE).align(AlignX.FILL) }
            row("Overview:") { cell(JBScrollPane(overviewField)).align(AlignX.FILL) }
            row("Action:") {
                cell(actionField).columns(COLUMNS_LARGE).align(AlignX.FILL)
                    .comment("The link the launcher opens, such as the mod's page.")
            }
            row { comment("No logo or picture: add them by hand.") }
        }
        group("Also Create") {
            row("Licence:") { cell(licenseBox) }
            row { cell(readme) }
            row { cell(gitignore) }
            row { cell(firstAddonBox) }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = if (folderField.text.isEmpty()) nameField else folderField

    override fun doValidate(): ValidationInfo? {
        val folder = folderField.text.trim()
        if (folder.contains('/') || folder.contains('\\')) return ValidationInfo("One folder name, without slashes", folderField)
        val target = if (folder.isEmpty()) parent else parent.findChild(folder)
        if (target?.findChild("mod.cpp") != null) return ValidationInfo("${target.name} already has a mod.cpp", folderField)
        if (modName().isEmpty()) return ValidationInfo("Enter the mod's name", nameField)
        return null
    }

    private fun modName(): String = nameField.text.trim().ifEmpty { folderField.text.trim().ifEmpty { parent.name } }

    val firstAddon: Boolean get() = firstAddonBox.isSelected

    fun spec(): NewDayZFiles.ModSpec = NewDayZFiles.ModSpec(
        folder = folderField.text.trim(),
        info = DayZModTemplates.ModInfo(
            name = modName(),
            author = authorField.text.trim(),
            tooltip = tooltipField.text.trim(),
            overview = overviewField.text.trim(),
            action = actionField.text.trim(),
            version = versionField.text.trim(),
        ),
        license = licenseBox.selectedItem as? DayZModTemplates.License ?: DayZModTemplates.License.NONE,
        readme = readme.isSelected,
        gitignore = gitignore.isSelected,
    )
}
