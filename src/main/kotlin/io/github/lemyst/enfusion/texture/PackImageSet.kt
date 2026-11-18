package io.github.lemyst.enfusion.texture

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.lemyst.enfusion.texture.format.EddsWriter
import io.github.lemyst.enfusion.tools.DayZInstallation
import io.github.lemyst.enfusion.tools.WorkDrive
import io.github.lemyst.enfusion.texture.format.ImageSetPacking
import io.github.lemyst.enfusion.texture.format.TextureFormatException
import io.github.lemyst.enfusion.texture.format.TgaReader
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * *Pack into Imageset…* on a folder of pictures: one `.edds` atlas and its `.imageset`, the
 * pictures placed by [ImageSetPacking] and the atlas written by [EddsWriter].
 */
class PackImageSetAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val dir = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && dir != null && dir.isDirectory && dir.isInLocalFileSystem
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isDirectory } ?: return
        val sources = PackImageSet.sourcesIn(dir)
        if (sources.isEmpty()) {
            Messages.showInfoMessage(project, "${dir.name} holds no ${PackImageSet.EXTENSIONS.joinToString(", ") { ".$it" }} picture.", "Pack into Imageset")
            return
        }
        val parent = dir.parent ?: dir
        // Packing over an imageset, as after extracting it to this folder, keeps what layouts and
        // scripts know it by rather than renaming it after the folder.
        val existing = parent.findChild(dir.name + ".imageset")
            ?.let { ImageSetPacking.existing(VfsUtilCore.loadText(it)) }
        val options = PackImageSet.Settings(
            name = existing?.name ?: dir.name,
            outputFolder = parent.path,
            fileName = dir.name,
            texturePath = existing?.texturePath ?: PackImageSet.texturePath(project, parent, dir.name + ".edds"),
        )
        if (!PackImageSetDialog(project, options, sources.size).showAndGet()) return
        PackImageSet.run(project, dir, sources, options)
    }
}

internal object PackImageSet {

    val EXTENSIONS = listOf("png", "tga", "bmp", "gif", "jpg", "jpeg", "tif", "tiff")

    class Settings(
        var name: String,
        var outputFolder: String,
        var fileName: String,
        var texturePath: String,
        var padding: Int = 0,
        var maxSide: Int = 4096,
        var mips: Boolean = true,
        var extrude: Boolean = false,
        var groups: Boolean = false,
        var format: EddsWriter.Format = EddsWriter.Format.BGRA8,
        var lz4: Boolean = false,
    )

    /** The pictures under [dir], any depth, in path order. */
    fun sourcesIn(dir: VirtualFile): List<VirtualFile> {
        val found = ArrayList<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(dir, null) { file ->
            if (!file.isDirectory && file.extension?.lowercase() in EXTENSIONS) found += file
            true
        }
        return found.sortedBy { it.path.lowercase() }
    }

    /**
     * The game's path to a file [fileName] in [folder]: the prefix of the addon holding it (its
     * `$PBOPREFIX$`, else [gamePrefix], else its folder's name) and the path inside the addon, with
     * forward slashes. Without an addon, the path from the folder holding the project.
     */
    fun texturePath(project: Project, folder: VirtualFile, fileName: String): String {
        var addon: VirtualFile? = folder
        while (addon != null && addon.findChild("config.cpp") == null && addon.findChild("\$PBOPREFIX\$") == null) addon = addon.parent
        if (addon != null) {
            val prefix = declaredPrefix(addon) ?: gamePrefix(addon.path, workDriveLetter(), project.basePath) ?: addon.name
            val inside = VfsUtilCore.getRelativePath(folder, addon, '/').orEmpty()
            return listOf(prefix.replace('\\', '/').trim('/'), inside, fileName).filter { it.isNotEmpty() }.joinToString("/")
        }
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val holder = base?.parent
        val inside = holder?.let { VfsUtilCore.getRelativePath(folder, it, '/') }
        return listOfNotNull(inside?.takeIf { it.isNotEmpty() }, fileName).joinToString("/")
    }

    private fun declaredPrefix(addon: VirtualFile): String? {
        val file = addon.children.firstOrNull { it.name.startsWith("\$PBOPREFIX\$", ignoreCase = true) } ?: return null
        val line = runCatching { VfsUtilCore.loadText(file) }.getOrNull()?.lines()?.firstOrNull { it.isNotBlank() } ?: return null
        return line.trim().removePrefix("prefix=").trim().trim('\\', '/').takeIf { it.isNotEmpty() }
    }

    /**
     * The game path of the addon folder [addonPath] (as the VFS writes it, with `/`) when it declares
     * none, or null: its path on the work drive [workDrive] (`P:/MyMod/GUI` → `MyMod/GUI`), else,
     * under the project's folder [projectBase], that folder's name and the path inside it, which
     * is what the work drive's link to a mod checkout gives (`C:/Dev/MyMod/Scripts` → `MyMod/Scripts`).
     *
     * Only the work drive counts. Any drive letter used to: in the sandbox review a scripted menu in a
     * checkout on `C:` was given the layout path `Users/.../Scripts/Client/GUI/layouts/...`.
     */
    internal fun gamePrefix(addonPath: String, workDrive: Char, projectBase: String?): String? {
        val path = addonPath.replace('\\', '/').trimEnd('/')
        if (path.length > 3 && path[0].equals(workDrive, ignoreCase = true) && path[1] == ':' && path[2] == '/') {
            return path.substring(3)
        }
        val base = projectBase?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val name = base.substringAfterLast('/')
        return when {
            path.equals(base, ignoreCase = true) -> name
            path.startsWith("$base/", ignoreCase = true) -> name + "/" + path.substring(base.length + 1)
            else -> null
        }
    }

    private fun workDriveLetter(): Char {
        val installation = DayZInstallation.getInstance()
        return WorkDrive.getInstance().letter(installation.configured.orElse(installation.lastDetected ?: installation.configured))
    }

    fun run(project: Project, dir: VirtualFile, files: List<VirtualFile>, settings: Settings) {
        val result = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<Pair<ByteArray, String>?, Exception>(
                {
                    val sources = files.map { read(dir, it, settings.groups) }
                    val packed = ImageSetPacking.pack(sources, ImageSetPacking.Options(settings.padding, settings.maxSide, settings.extrude))
                    // DXT1 keeps alpha as on or off: soft edges and shadows would turn hard.
                    if (settings.format == EddsWriter.Format.DXT1 && EddsWriter.hasPartialAlpha(packed.argb) && !confirmLossyAlpha(project)) {
                        return@runProcessWithProgressSynchronously null
                    }
                    val edds = EddsWriter.write(packed.argb, packed.width, packed.height, settings.mips, settings.format, settings.lz4)
                    edds to ImageSetPacking.imageSet(settings.name, settings.texturePath, packed)
                },
                "Packing ${files.size} Pictures",
                true,
                project,
            )
        } catch (e: ImageSetPacking.Problem) {
            Messages.showErrorDialog(project, e.message, "Pack into Imageset")
            return
        } catch (e: TextureFormatException) {
            Messages.showErrorDialog(project, e.message, "Pack into Imageset")
            return
        }
        val (edds, imageSet) = result ?: return
        val written = WriteCommandAction.writeCommandAction(project).withName("Pack into Imageset").compute<VirtualFile, Exception> {
            val out = VfsUtil.createDirectoryIfMissing(settings.outputFolder) ?: error("Cannot create ${settings.outputFolder}")
            val texture = out.findChild(settings.fileName + ".edds") ?: out.createChildData(this, settings.fileName + ".edds")
            texture.setBinaryContent(edds)
            val descriptor = out.findChild(settings.fileName + ".imageset") ?: out.createChildData(this, settings.fileName + ".imageset")
            VfsUtil.saveText(descriptor, imageSet)
            descriptor
        }
        OpenFileDescriptor(project, written).navigate(true)
        NotificationGroupManager.getInstance().getNotificationGroup("Enforce Script")
            .createNotification(
                "Pack into Imageset",
                StringUtil.escapeXmlEntities("Packed ${files.size} pictures into ${settings.fileName}.edds (${edds.size / 1024} KB) and ${settings.fileName}.imageset."),
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    /** Asked from the packing thread, on the EDT: DXT1 would lose the pictures' partial alpha. */
    private fun confirmLossyAlpha(project: Project): Boolean {
        var answer = false
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait({
            answer = Messages.showOkCancelDialog(
                project,
                "Some pictures have partly transparent pixels, which DXT1 stores as either opaque or transparent: soft edges and shadows turn hard. DXT5 keeps them.",
                "Pack into Imageset",
                "Pack as DXT1",
                Messages.getCancelButton(),
                Messages.getWarningIcon(),
            ) == Messages.OK
        }, com.intellij.openapi.application.ModalityState.any())
        return answer
    }

    /** One picture: its image name, its group (its first folder under [dir], when asked), its pixels. */
    private fun read(dir: VirtualFile, file: VirtualFile, groups: Boolean): ImageSetPacking.Source {
        ProgressManager.checkCanceled()
        val bytes = file.contentsToByteArray()
        val name = ImageSetPacking.imageName(file.name)
        val relative = VfsUtilCore.getRelativePath(file, dir, '/').orEmpty()
        val group = if (groups && relative.contains('/')) relative.substringBefore('/') else null
        if (file.extension.equals("tga", ignoreCase = true)) {
            val tga = TgaReader.read(bytes)
            return ImageSetPacking.Source(name, group, tga.width, tga.height, tga.argb)
        }
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw TextureFormatException("${file.name} is not a picture ImageIO can read")
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return ImageSetPacking.Source(name, group, image.width, image.height, argb)
    }
}

/** The options of a pack: names, where it goes, and how the atlas is built. */
private class PackImageSetDialog(private val project: Project, private val settings: PackImageSet.Settings, count: Int) : DialogWrapper(project) {

    private lateinit var form: DialogPanel

    init {
        title = "Pack $count Pictures into an Imageset"
        setOKButtonText("Pack")
        init()
    }

    override fun createCenterPanel(): JComponent {
        form = panel {
            row("Imageset name:") {
                textField().columns(COLUMNS_LARGE).align(AlignX.FILL).bindText(settings::name)
                    .comment("What \"set:<name> image:…\" names.")
                    .validationOnApply { if (it.text.isBlank()) error("Enter a name") else null }
            }
            row("Output folder:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir().withTitle("Output Folder"), project)
                    .columns(COLUMNS_LARGE).align(AlignX.FILL).bindText(settings::outputFolder)
            }
            row("File name:") {
                textField().columns(COLUMNS_LARGE).align(AlignX.FILL).bindText(settings::fileName)
                    .comment("Without extension: the .edds and the .imageset are both written, replacing any already there.")
                    .validationOnApply { if (it.text.isBlank() || it.text.any { c -> c in "\\/:*?\"<>|" }) error("Enter a file name") else null }
            }
            row("Texture path:") {
                textField().columns(COLUMNS_LARGE).align(AlignX.FILL).bindText(settings::texturePath)
                    .comment("The game's path to the .edds, written in the .imageset: the addon's prefix and the path inside it.")
            }
            row("Gap between pictures:") {
                intTextField(0..64).bindIntText(settings::padding)
                    .comment("Pixels, none along the atlas edge. DayZ's atlases are packed tight (0 or 1); 2 keeps a picture drawn smaller from blending into its neighbour.")
            }
            row("Largest atlas side:") { intTextField(16..16384).bindIntText(settings::maxSide) }
            row { checkBox("Mipmaps, as vanilla's UI atlases have").bindSelected(settings::mips) }
            row { checkBox("Repeat each picture's edge into half the gap round it").bindSelected(settings::extrude) }
            row { checkBox("Subfolders become groups").bindSelected(settings::groups) }
            row("Format:") {
                comboBox(EddsWriter.Format.entries, textListCellRenderer<EddsWriter.Format?> { it?.label }).bindItem(settings::format.toNullableProperty())
                    .comment("BGRA8 keeps every pixel, the format of most vanilla UI atlases. DXT5 is a quarter of its size and keeps smooth alpha; DXT1 is half of DXT5, for opaque or cut-out pictures.")
            }
            row { checkBox("Compress the mipmaps with LZ4, as most vanilla atlases do").bindSelected(settings::lz4) }
            row { comment("Packing the same pictures with the same options again gives the same files.") }
        }
        return form
    }

    override fun doOKAction() {
        form.apply()
        super.doOKAction()
    }
}
