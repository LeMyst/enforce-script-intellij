package io.github.lemyst.enfusion.texture

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.index.EnfusionObjectIndexes
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import io.github.lemyst.enfusion.texture.format.ImageSetExtraction
import io.github.lemyst.enfusion.texture.format.TextureReader
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * *Extract Images…* on an `.imageset`: cuts every image out of its atlas into a PNG, one folder per
 * `ImageSetGroupClass`, named as the image is. An old atlas whose sources were lost gets a source
 * folder back, which *Pack into Imageset…* turns into the same imageset again.
 *
 * The images are cut from the sharpest texture the set names, as the hover and the gutter show
 * them, at that texture's own resolution.
 */
class ExtractImageSetAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && !file.isDirectory &&
            file.extension.equals("imageset", ignoreCase = true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project, "Wait for indexing to finish: the textures are found through the index.", "Extract Images")
            return
        }
        val images = ExtractImageSet.imagesOf(project, file)
        if (images.isEmpty()) {
            Messages.showInfoMessage(project, "No image of ${file.name} lies on a texture the project can read.", "Extract Images")
            return
        }
        val parent = file.parent ?: return
        val chosen = FileChooser.chooseFile(
            FileChooserDescriptorFactory.singleDir().withTitle("Extract ${images.size} Images Into"),
            project,
            parent,
        ) ?: return
        val target = Path.of(chosen.path).resolve(file.nameWithoutExtension)
        val clashes = images.count { Files.exists(target.resolve(it.relativePath)) }
        if (clashes > 0 && Messages.showOkCancelDialog(
                project,
                "$clashes of the ${images.size} pictures already exist in $target and will be replaced.",
                "Extract Images",
                "Replace",
                Messages.getCancelButton(),
                Messages.getWarningIcon(),
            ) != Messages.OK
        ) {
            return
        }
        ExtractImageSet.run(project, images, target)
    }
}

internal object ExtractImageSet {

    /** One image to write: where it lies, and where it goes under the target folder. */
    class Image(val texture: VirtualFile, val region: EnTexturePreviews.Region, val relativePath: String)

    fun imagesOf(project: Project, file: VirtualFile): List<Image> = ReadAction.compute<List<Image>, RuntimeException> {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute emptyList()
        val defs = PsiTreeUtil.findChildrenOfType(psi, EnObjDeclMixin::class.java).filter(EnImageSetTextures::isImageDef)
        val used = HashSet<String>()
        defs.mapNotNull { def ->
            val entry = EnImageSetTextures.entryFor(def) ?: return@mapNotNull null
            val group = groupOf(def)
            val name = ImageSetExtraction.fileName(entry.name)
            val relative = ImageSetExtraction.unique(listOfNotNull(group?.let(ImageSetExtraction::fileName), "$name.png").joinToString("/"), used)
            Image(entry.texture, entry.region, relative)
        }
    }

    /** The `ImageSetGroupClass` an image is listed in, if any. */
    private fun groupOf(def: EnObjDeclMixin): String? {
        var current = def.parent
        while (current != null) {
            if (current is EnObjDeclMixin && current.getClassName() == "ImageSetGroupClass") return current.declaredName()
            if (current is EnObjDeclMixin && current.getClassName() == EnfusionObjectIndexes.IMAGE_SET_CLASS) return null
            current = current.parent
        }
        return null
    }

    fun run(project: Project, images: List<Image>, target: Path) {
        val written = ProgressManager.getInstance().runProcessWithProgressSynchronously<Int, Exception>(
            {
                val indicator = ProgressManager.getInstance().progressIndicator
                val decoded = HashMap<VirtualFile, BufferedImage?>()
                var count = 0
                for ((index, image) in images.withIndex()) {
                    indicator?.checkCanceled()
                    indicator?.fraction = index / images.size.toDouble()
                    val atlas = decoded.getOrPut(image.texture) {
                        runCatching { TextureReader.read(image.texture.contentsToByteArray()).decode(0) }.getOrNull()
                    } ?: continue
                    val fractions = image.region.fractions(atlas.width, atlas.height) ?: continue
                    val rect = ImageSetExtraction.pixelRect(fractions.x, fractions.y, fractions.w, fractions.h, atlas.width, atlas.height)
                        ?: continue
                    val out = target.resolve(image.relativePath)
                    Files.createDirectories(out.parent)
                    ImageIO.write(ImageSetExtraction.crop(atlas, rect), "png", out.toFile())
                    count++
                }
                count
            },
            "Extracting ${images.size} Images",
            true,
            project,
        )
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.refresh(false, true)
        NotificationGroupManager.getInstance().getNotificationGroup("Enforce Script")
            .createNotification("Extracted $written of ${images.size} images into $target", NotificationType.INFORMATION)
            .notify(project)
    }
}
