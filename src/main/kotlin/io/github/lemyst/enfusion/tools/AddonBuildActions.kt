package io.github.lemyst.enfusion.tools

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import io.github.lemyst.enfusion.tools.detect.AddonBuild

/** Finding addons, and the saved or asked-for options of each. */
internal object AddonBuildSupport {

    /** The addon a file or folder belongs to: the nearest folder holding a `config.cpp`, inside the project. */
    fun addonOf(project: Project, file: VirtualFile?): VirtualFile? {
        val index = ProjectFileIndex.getInstance(project)
        var dir = if (file?.isDirectory == true) file else file?.parent
        while (dir != null && index.isInContent(dir)) {
            if (dir.findChild("config.cpp") != null) return dir
            dir = dir.parent
        }
        return null
    }

    /** Every addon folder of the project, outermost only: an addon is never inside another one's PBO. */
    fun allAddons(project: Project): List<VirtualFile> {
        val found = ArrayList<VirtualFile>()
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>(VirtualFileVisitor.limit(4)) {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (!file.isDirectory) return false
                    if (file.name.startsWith(".")) return false
                    if (file.findChild("config.cpp") != null) {
                        found += file
                        return false
                    }
                    return true
                }
            })
        }
        return found
    }

    /**
     * The saved options for [addon], or null when there are none yet. Saved options come from the
     * project's `.idea`, so an untrusted project's are not used: they would name the paths a
     * cloned repository chose, and the dialog is asked instead.
     */
    fun saved(project: Project, addon: VirtualFile): AddonBuild? {
        if (!TrustedProjects.isProjectTrusted(project)) return null
        return AddonBuilds.getInstance(project).get(addon.toNioPath())?.toBuild()
    }

    /** Shows the options for [addon], prefilled with what was saved, and saves what is confirmed. */
    fun ask(project: Project, addon: VirtualFile): AddonBuild? {
        val builds = AddonBuilds.getInstance(project)
        val entry = AddonBuildState()
        val trusted = TrustedProjects.isProjectTrusted(project)
        builds.get(addon.toNioPath())?.takeIf { trusted }?.let { entry.copyFrom(it) }
        entry.source = addon.toNioPath().toString()
        if (entry.destination == null) entry.destination = defaultDestination(project, addon)
        if (!AddonBuildDialog(project, entry).showAndGet()) return null
        builds.save(entry)
        return entry.toBuild()
    }

    /**
     * Where a PBO goes when nothing is saved yet: the `Addons` folder of an `@Mod` folder in the
     * project, if there is exactly one; otherwise nothing, and the dialog asks.
     */
    private fun defaultDestination(project: Project, addon: VirtualFile): String? {
        val mods = ProjectRootManager.getInstance(project).contentRoots
            .flatMap { root -> root.children.filter { it.isDirectory && it.name.startsWith("@") } }
        return mods.singleOrNull()?.let { it.toNioPath().resolve("Addons").toString() }
    }
}

/** *Build PBO*: packs the selected addon with its saved options, asking for them the first time. */
class BuildPboAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows && project != null &&
            AddonBuildSupport.addonOf(project, e.getData(CommonDataKeys.VIRTUAL_FILE)) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val addon = AddonBuildSupport.addonOf(project, e.getData(CommonDataKeys.VIRTUAL_FILE)) ?: return
        val build = AddonBuildSupport.saved(project, addon) ?: AddonBuildSupport.ask(project, addon) ?: return
        AddonBuildRunner.run(project, listOf(build))
    }
}

/** *Build PBO with Options…*: the same, always showing the options first. */
class ConfigurePboBuildAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows && project != null &&
            AddonBuildSupport.addonOf(project, e.getData(CommonDataKeys.VIRTUAL_FILE)) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val addon = AddonBuildSupport.addonOf(project, e.getData(CommonDataKeys.VIRTUAL_FILE)) ?: return
        val build = AddonBuildSupport.ask(project, addon) ?: return
        AddonBuildRunner.run(project, listOf(build))
    }
}

/**
 * *Build All PBOs*: every addon of the project, one after another. An addon with no saved options
 * is asked for them first; cancelling that dialog skips it.
 */
class BuildAllPbosAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows && e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val builds = AddonBuildSupport.allAddons(project).mapNotNull { addon ->
            AddonBuildSupport.saved(project, addon) ?: AddonBuildSupport.ask(project, addon)
        }
        AddonBuildRunner.run(project, builds)
    }
}
