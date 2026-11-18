package io.github.lemyst.enfusion.templates

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import io.github.lemyst.enfusion.lang.EnfusionIcons
import io.github.lemyst.enfusion.rename.EnfusionNamesValidator
import io.github.lemyst.enfusion.resolve.EnScriptModules
import io.github.lemyst.enfusion.texture.PackImageSet

/**
 * *File → New → Enforce Script File*: a class, a `modded class` or a `UIScriptedMenu`, from the
 * templates editable under *Settings → Editor → File and Code Templates*.
 *
 * Offered only in a folder inside an addon (a `config.cpp` or `$PBOPREFIX$` above it): anywhere
 * else a `.c` file is C, not Enforce Script. A file created outside every script module is never
 * compiled, so that gets a warning.
 */
class NewEnforceScriptFileAction : CreateFileFromTemplateAction(), DumbAware {

    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Enforce Script File")
            .addKind("Class", EnfusionIcons.FILE, CLASS)
            .addKind("Modded class", EnfusionIcons.FILE, MODDED_CLASS)
            .addKind("Scripted menu", EnfusionIcons.FILE, SCRIPTED_MENU)
            .setValidator(object : InputValidatorEx {
                override fun getErrorText(inputString: String): String? =
                    if (isValidName(inputString)) null else "'$inputString' is not a valid class name"

                override fun checkInput(inputString: String): Boolean = isValidName(inputString)

                override fun canClose(inputString: String): Boolean = isValidName(inputString)
            })
    }

    override fun getActionName(directory: PsiDirectory, newName: String, templateName: String): String =
        "Create Enforce Script File $newName"

    override fun isAvailable(dataContext: DataContext): Boolean {
        if (!super.isAvailable(dataContext)) return false
        val directories = LangDataKeys.IDE_VIEW.getData(dataContext)?.directories ?: return false
        return directories.any { EnScriptFiles.isInsideAddon(it.virtualFile) }
    }

    override fun createFile(name: String, templateName: String, dir: PsiDirectory): PsiFile? {
        val template = FileTemplateManager.getInstance(dir.project).getInternalTemplate(templateName)
        val properties = mapOf("LAYOUT" to EnScriptFiles.layoutPath(dir.project, dir.virtualFile, name))
        return createFileFromTemplate(name, template, dir, null, true, emptyMap(), properties)
    }

    override fun postProcess(createdElement: PsiFile, templateName: String?, customProperties: MutableMap<String, String>?) {
        super.postProcess(createdElement, templateName, customProperties)
        val file = createdElement.virtualFile ?: return
        if (EnScriptModules.moduleOf(file, createdElement.project) != null) return
        NotificationGroupManager.getInstance().getNotificationGroup("Enforce Script")
            .createNotification(
                "${file.name} is in no script module",
                "The game compiles only the folders a script module lists: <code>1_Core</code> to " +
                    "<code>5_Mission</code>, or the <code>files[]</code> of the addon's " +
                    "<code>CfgMods</code>. Move the file into one, or the game never compiles it.",
                NotificationType.WARNING,
            )
            .notify(createdElement.project)
    }

    private fun isValidName(name: String): Boolean =
        name.isNotEmpty() && EnfusionNamesValidator().isIdentifier(name, null) && !EnfusionNamesValidator().isKeyword(name, null)

    companion object {
        const val CLASS = "Enforce Script Class"
        const val MODDED_CLASS = "Enforce Script Modded Class"
        const val SCRIPTED_MENU = "Enforce Script Scripted Menu"
    }
}

internal object EnScriptFiles {

    private const val MAX_DEPTH = 12

    /** A `config.cpp` or `$PBOPREFIX$` in the folder or above it: where a `.c` is Enforce Script. */
    fun isInsideAddon(dir: VirtualFile?): Boolean = addonOf(dir) != null

    fun addonOf(dir: VirtualFile?): VirtualFile? {
        var current = dir
        var depth = 0
        while (current != null && depth < MAX_DEPTH) {
            if (current.findChild("config.cpp") != null || current.findChild("\$PBOPREFIX\$") != null) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * Where a scripted menu's layout goes by default: `GUI/layouts/` of the addon, under its game
     * path (`MyMod/GUI/layouts/my_menu.layout`). A guess to edit, but a valid path to start from.
     */
    fun layoutPath(project: Project, dir: VirtualFile, className: String): String {
        val name = className.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_").lowercase()
        val addon = addonOf(dir) ?: return "GUI/layouts/$name.layout"
        val prefix = PackImageSet.texturePath(project, addon, "").trimEnd('/')
        return listOf(prefix, "GUI/layouts/$name.layout").filter { it.isNotEmpty() }.joinToString("/")
    }
}
