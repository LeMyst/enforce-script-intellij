package io.github.lemyst.enfusion.tools

import com.intellij.ide.IdeView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import io.github.lemyst.enfusion.index.EnfusionConfigIndexes
import io.github.lemyst.enfusion.stringtable.format.StringTableCsv
import io.github.lemyst.enfusion.tools.detect.DayZModTemplates
import io.github.lemyst.enfusion.tools.detect.WorkDriveLink
import java.nio.file.Files
import java.nio.file.Path
import java.time.Year

/**
 * What the *New DayZ* dialogs start from, read before they open: the disk and the index are not
 * read on the EDT.
 */
internal class NewDayZContext(
    /** The prefix of the folder the new addon goes into (`MyMod`), empty at the work drive's root. */
    val parentPrefix: String,
    /** The `CfgPatches` classes the project indexes, vanilla's and the dependency mods'. */
    val addons: List<String>,
) {
    /** The prefix of a new addon folder [folder] in that folder: `MyMod\Scripts`. */
    fun prefixOf(folder: String): String = listOf(parentPrefix, folder.trim()).filter { it.isNotEmpty() }.joinToString("\\")

    companion object {
        /** Addons a DayZ mod can always require, indexed or not: the game always has them. */
        val ALWAYS = listOf("DZ_Data", "DZ_Scripts")

        /** Reads what the dialogs need for [dir], under a modal progress. */
        fun read(project: Project, dir: VirtualFile): NewDayZContext =
            ProgressManager.getInstance().runProcessWithProgressSynchronously<NewDayZContext, RuntimeException>(
                { NewDayZContext(parentPrefix(project, dir), knownAddons(project)) },
                "Reading the Work Drive",
                true,
                project,
            )

        /**
         * The prefix of [dir]: its path on the work drive, through the drive's link to the project
         * when it is not on the drive itself (`P:\MyMod` → the checkout), as *Rescan Project Addons*
         * works it out. Without a link, its path from the folder holding the project, since a
         * checkout is usually named after its mod.
         */
        fun parentPrefix(project: Project, dir: VirtualFile): String {
            val path = dir.toNioPathOrNull() ?: return dir.name
            val letter = WorkDrive.getInstance().letter(DayZInstallation.getInstance().paths(project))
            val drive = Path.of("$letter:\\")
            if (Files.isDirectory(drive)) {
                val entries = runCatching { Files.list(drive).use { s -> s.filter { Files.isDirectory(it) }.toList() } }.getOrDefault(emptyList())
                val link = WorkDriveLink.linkOf(path, drive, entries) { a, b -> runCatching { Files.isSameFile(a, b) }.getOrDefault(false) }
                val onDrive = link?.let { WorkDriveLink.onDrive(path, it) }
                if (onDrive != null) return drive.relativize(onDrive).joinToString("\\")
            }
            val base = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }
            val holder = base?.parent
            if (holder != null && path.startsWith(base)) return holder.relativize(path).joinToString("\\")
            return dir.name
        }

        /** The `CfgPatches` classes some indexed config declares, with [ALWAYS] first. */
        fun knownAddons(project: Project): List<String> {
            if (DumbService.isDumb(project)) return ALWAYS
            val indexed = ReadAction.nonBlocking<List<String>> {
                val index = FileBasedIndex.getInstance()
                val scope = GlobalSearchScope.allScope(project)
                val names = ArrayList<String>()
                index.processAllKeys(EnfusionConfigIndexes.ADDONS, { names += it; true }, project)
                // processAllKeys may still list a key whose file is gone.
                names.filter { index.getContainingFiles(EnfusionConfigIndexes.ADDONS, it, scope).isNotEmpty() }
            }.executeSynchronously()
            return ALWAYS + indexed.filter { it !in ALWAYS }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        }

        private fun VirtualFile.toNioPathOrNull(): Path? = runCatching { toNioPath() }.getOrNull()
    }
}

/** Writes the files of a new mod or addon, as one undoable command. */
internal object NewDayZFiles {

    /** A new addon: its folder, `config.cpp`, `$PBOPREFIX$` and a first script per module. */
    class AddonSpec(
        /** The folder created in the chosen one, such as `Scripts`. */
        val folder: String,
        val addon: DayZModTemplates.Addon,
        val pboPrefix: Boolean,
        /** The folders and files it starts with besides `config.cpp`. */
        val kind: DayZModTemplates.AddonKind = DayZModTemplates.AddonKind.SCRIPTS,
    )

    /** A new mod: its folder (the chosen one itself when empty), `mod.cpp` and the templates ticked. */
    class ModSpec(
        val folder: String,
        val info: DayZModTemplates.ModInfo,
        val license: DayZModTemplates.License,
        val readme: Boolean,
        val gitignore: Boolean,
    )

    /** Creates [spec] in [parent] and returns its `config.cpp`. */
    fun createAddon(project: Project, parent: VirtualFile, spec: AddonSpec): VirtualFile =
        WriteCommandAction.writeCommandAction(project).withName("New DayZ Addon").compute<VirtualFile, Exception> {
            val dir = VfsUtil.createDirectoryIfMissing(parent, spec.folder) ?: error("Could not create ${spec.folder}")
            val config = write(dir, "config.cpp", DayZModTemplates.configCpp(spec.addon))
            if (spec.pboPrefix) write(dir, "\$PBOPREFIX\$", DayZModTemplates.pboPrefix(spec.addon))
            for (module in DayZModTemplates.Module.entries.filter { it in spec.addon.modules }) {
                val moduleDir = VfsUtil.createDirectoryIfMissing(dir, module.folder) ?: continue
                if (moduleDir.children.isEmpty()) write(moduleDir, "${spec.addon.name}.c", DayZModTemplates.moduleScript(module))
            }
            spec.kind.subfolders.forEach { VfsUtil.createDirectoryIfMissing(dir, it) }
            if (spec.kind.stringTable && dir.findChild("stringtable.csv") == null) {
                write(dir, "stringtable.csv", StringTableCsv.headerLine(StringTableCsv.DEFAULT_COLUMNS) + "\n")
            }
            config
        }

    /** Creates [spec] in [parent] and returns the mod's folder. */
    fun createMod(project: Project, parent: VirtualFile, spec: ModSpec): VirtualFile =
        WriteCommandAction.writeCommandAction(project).withName("New DayZ Mod").compute<VirtualFile, Exception> {
            val dir = if (spec.folder.isBlank()) parent else VfsUtil.createDirectoryIfMissing(parent, spec.folder) ?: error("Could not create ${spec.folder}")
            write(dir, "mod.cpp", DayZModTemplates.modCpp(spec.info))
            when (spec.license) {
                DayZModTemplates.License.NONE -> Unit
                DayZModTemplates.License.MIT -> write(dir, "LICENSE", DayZModTemplates.mit(Year.now().value, spec.info.author.ifBlank { spec.info.name }))
                DayZModTemplates.License.GPL3 -> gplText()?.let { write(dir, "LICENSE", it) }
            }
            if (spec.readme) write(dir, "README.md", DayZModTemplates.readme(spec.info))
            if (spec.gitignore) write(dir, ".gitignore", DayZModTemplates.gitignore())
            dir
        }

    /**
     * The GPL-3.0 text the plugin carries for its own licence, or null where it is not packed (a
     * test run). Checked, since another jar's `META-INF/LICENSE` could answer the lookup.
     */
    fun gplText(): String? {
        val text = NewDayZFiles::class.java.getResource("/META-INF/LICENSE")?.readText() ?: return null
        return text.takeIf { it.contains("GNU GENERAL PUBLIC LICENSE") && it.contains("Version 3") }
    }

    private fun write(dir: VirtualFile, name: String, text: String): VirtualFile {
        val file = dir.findChild(name) ?: dir.createChildData(this, name)
        VfsUtil.saveText(file, text)
        return file
    }
}

private fun chosenDirectory(e: AnActionEvent): Pair<IdeView, VirtualFile>? {
    val view = e.getData(LangDataKeys.IDE_VIEW) ?: return null
    val dir = view.orChooseDirectory ?: return null
    return view to dir.virtualFile
}

/** *File | New | DayZ Addon*: a folder of the mod with a `config.cpp` that is right the first time. */
class NewDayZAddonAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(LangDataKeys.IDE_VIEW)?.directories?.isNotEmpty() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (view, dir) = chosenDirectory(e) ?: return
        newAddon(project, dir, view)
    }

    companion object {
        /** Asks for the addon, creates it in [dir] and opens its `config.cpp`. */
        fun newAddon(project: Project, dir: VirtualFile, view: IdeView? = null, modName: String = "") {
            val context = NewDayZContext.read(project, dir)
            val dialog = NewDayZAddonDialog(project, dir, context, modName)
            if (!dialog.showAndGet()) return
            val config = NewDayZFiles.createAddon(project, dir, dialog.spec())
            PsiManager.getInstance(project).findFile(config)?.let { view?.selectElement(it) }
            OpenFileDescriptor(project, config).navigate(true)
        }
    }
}

/** *File | New | DayZ Mod*: `mod.cpp` and the usual files around it, then optionally a first addon. */
class NewDayZModAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(LangDataKeys.IDE_VIEW)?.directories?.isNotEmpty() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (view, dir) = chosenDirectory(e) ?: return
        val dialog = NewDayZModDialog(project, dir)
        if (!dialog.showAndGet()) return
        val spec = dialog.spec()
        val mod = NewDayZFiles.createMod(project, dir, spec)
        mod.findChild("mod.cpp")?.let { file ->
            PsiManager.getInstance(project).findFile(file)?.let { view.selectElement(it) }
            OpenFileDescriptor(project, file).navigate(true)
        }
        if (dialog.firstAddon) NewDayZAddonAction.newAddon(project, mod, view, spec.info.name)
    }
}
