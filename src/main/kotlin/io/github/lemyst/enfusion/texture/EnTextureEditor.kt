package io.github.lemyst.enfusion.texture

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.lemyst.enfusion.texture.format.Texture
import io.github.lemyst.enfusion.texture.format.TextureFormatException
import io.github.lemyst.enfusion.texture.format.TextureReader
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import java.beans.PropertyChangeListener
import java.io.IOException
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Opens `.edds` files as images, replacing the binary-file placeholder.
 *
 * A provider of its own rather than an ImageIO plug-in for the bundled Images editor: that editor
 * finds readers through its own internals, and leaning on them would break the plugin's
 * no-internal-API rule.
 */
class EnTextureEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        FileTypeRegistry.getInstance().isFileOfType(file, EnTextureFileType)

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = EnTextureEditor(file)

    override fun getEditorTypeId(): String = "enfusion-texture-viewer"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * The viewer: zoom, fit to window, a transparency checkerboard, and a mip picker.
 *
 * Decoding runs off the EDT. A 4096² DXT5 mip takes long enough to freeze the UI, and the file
 * may be re-exported while open, in which case it is decoded again.
 */
class EnTextureEditor(private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val canvas = Canvas()

    /** Centres the canvas when it is smaller than the window; a bare viewport pins it top-left. */
    private val centred = JPanel(GridBagLayout()).apply { add(canvas) }
    private val scroll = JBScrollPane(centred)
    private val info = JBLabel()
    private val mipModel = DefaultComboBoxModel<String>()
    private val mipBox = ComboBox(mipModel)
    private val root = JPanel(BorderLayout())

    @Volatile
    private var disposed = false
    private var texture: Texture? = null
    private var level = 0
    private var updatingMips = false

    /** Bumped on every load, so a slow decode that finishes after a newer one is dropped. */
    private var generation = 0

    init {
        val group = DefaultActionGroup(
            action("Zoom In", AllIcons.Graph.ZoomIn) { canvas.zoomBy(ZOOM_STEP) },
            action("Zoom Out", AllIcons.Graph.ZoomOut) { canvas.zoomBy(1 / ZOOM_STEP) },
            action("Actual Size", AllIcons.Graph.ActualZoom) { canvas.setZoom(1.0) },
            action("Fit to Window", AllIcons.General.FitContent) { canvas.fit() },
            object : ToggleAction("Show Transparency", "Show the alpha channel over a checkerboard", AllIcons.Gutter.Colors) {
                override fun isSelected(e: AnActionEvent): Boolean = canvas.showAlpha
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    canvas.showAlpha = state
                    canvas.repaint()
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("EnforceTextureViewer", group, true)
        toolbar.targetComponent = root

        val south = JPanel(BorderLayout())
        south.border = JBUI.Borders.empty(2, 8)
        south.add(info, BorderLayout.CENTER)
        south.add(mipBox, BorderLayout.EAST)
        mipBox.isVisible = false
        mipBox.addActionListener {
            if (!updatingMips && mipBox.selectedIndex >= 0 && mipBox.selectedIndex != level) decodeLevel(mipBox.selectedIndex)
        }

        root.add(toolbar.component, BorderLayout.NORTH)
        root.add(scroll, BorderLayout.CENTER)
        root.add(south, BorderLayout.SOUTH)

        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = canvas.viewportResized()
        })
        canvas.addMouseWheelListener { e ->
            if (e.isControlDown || e.isMetaDown) {
                canvas.zoomBy(if (e.preciseWheelRotation < 0) ZOOM_STEP else 1 / ZOOM_STEP)
                e.consume()
            } else {
                // Without this the canvas swallows the wheel and the scroll pane never scrolls.
                scroll.dispatchEvent(SwingUtilities.convertMouseEvent(canvas, e, scroll))
            }
        }

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.file == file }) load()
                }
            })
        load()
    }

    private fun action(text: String, icon: javax.swing.Icon, run: () -> Unit) =
        object : DumbAwareAction(text, null, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    private fun load() {
        val token = ++generation
        canvas.show(null, "Loading…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome: Outcome = try {
                if (file.length > TextureReader.MAX_FILE_BYTES) {
                    Outcome.Failed("${file.name} is too large to open (${file.length / (1024 * 1024)} MB)")
                } else {
                    val texture = TextureReader.read(file.contentsToByteArray())
                    decode(texture, texture.levelWithin(MAX_SHOWN_PIXELS))
                }
            } catch (e: TextureFormatException) {
                Outcome.Failed(e.message ?: "unreadable texture")
            } catch (e: IOException) {
                Outcome.Failed("Cannot read ${file.name}: ${e.message}")
            }
            onEdt(token) { show(outcome) }
        }
    }

    private fun decodeLevel(newLevel: Int) {
        val current = texture ?: return
        val token = ++generation
        canvas.show(null, "Decoding…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = decode(current, newLevel)
            onEdt(token) { show(outcome) }
        }
    }

    private fun decode(texture: Texture, level: Int): Outcome = try {
        Outcome.Decoded(texture, level, texture.decode(level), null)
    } catch (e: TextureFormatException) {
        Outcome.Decoded(texture, level, null, e.message)
    }

    private fun onEdt(token: Int, run: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && token == generation) run()
        }, ModalityState.any())
    }

    private fun show(outcome: Outcome) {
        when (outcome) {
            is Outcome.Failed -> {
                texture = null
                mipBox.isVisible = false
                info.text = ""
                canvas.show(null, outcome.message)
            }

            is Outcome.Decoded -> {
                val t = outcome.texture
                if (texture !== t) {
                    texture = t
                    updatingMips = true
                    mipModel.removeAllElements()
                    t.mips.forEachIndexed { i, mip -> mipModel.addElement("Mip $i: ${mip.width} × ${mip.height}") }
                    updatingMips = false
                }
                level = outcome.level
                updatingMips = true
                mipBox.selectedIndex = level
                updatingMips = false
                mipBox.isVisible = t.mips.size > 1

                val mipCount = t.mips.size
                info.text = "${t.width} × ${t.height}  ·  ${t.formatName}  ·  $mipCount mip${if (mipCount == 1) "" else "s"}" +
                    "  ·  ${t.container}, ${t.compression}"
                canvas.show(outcome.image, outcome.error)
            }
        }
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = canvas
    override fun getName(): String = "Texture"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        disposed = true
    }

    private sealed class Outcome {
        class Failed(val message: String) : Outcome()
        class Decoded(val texture: Texture, val level: Int, val image: BufferedImage?, val error: String?) : Outcome()
    }

    /** Paints the image centred over a checkerboard, at a fixed zoom or fitted to the viewport. */
    private inner class Canvas : JComponent() {

        private var image: BufferedImage? = null
        private var opaque: BufferedImage? = null
        private var message: String? = null
        private var zoom = 1.0
        private var fitting = true
        var showAlpha = true

        init {
            isFocusable = true
        }

        fun show(image: BufferedImage?, message: String?) {
            this.image = image
            this.opaque = null
            this.message = message
            revalidate()
            repaint()
        }

        fun zoomBy(factor: Double) = setZoom(effectiveZoom() * factor)

        fun setZoom(value: Double) {
            fitting = false
            zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
            revalidate()
            repaint()
        }

        fun fit() {
            fitting = true
            revalidate()
            repaint()
        }

        fun viewportResized() {
            if (fitting) {
                revalidate()
                repaint()
            }
        }

        /** Fitting only ever shrinks: a 32-pixel icon blown up to fill the window helps nobody. */
        private fun effectiveZoom(): Double {
            val img = image ?: return zoom
            if (!fitting) return zoom
            val view = scroll.viewport.extentSize
            if (view.width <= 0 || view.height <= 0) return 1.0
            return minOf(1.0, view.width.toDouble() / img.width, view.height.toDouble() / img.height)
        }

        override fun getPreferredSize(): Dimension {
            // With nothing to show, fill the window so the message is centred in it.
            val img = image ?: return scroll.viewport.extentSize.let { Dimension(maxOf(1, it.width), maxOf(1, it.height)) }
            val z = effectiveZoom()
            return Dimension(maxOf(1, (img.width * z).toInt()), maxOf(1, (img.height * z).toInt()))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.color = UIUtil.getPanelBackground()
                g2.fillRect(0, 0, width, height)
                val img = image
                if (img == null) {
                    paintMessage(g2)
                    return
                }
                val z = effectiveZoom()
                val w = maxOf(1, (img.width * z).toInt())
                val h = maxOf(1, (img.height * z).toInt())
                val x = 0
                val y = 0
                if (showAlpha) paintCheckerboard(g2, x, y, w, h)
                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    if (z >= 1.0) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR else RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                g2.drawImage(if (showAlpha) img else opaqueImage(img), x, y, w, h, null)
                message?.let { paintMessage(g2) }
            } finally {
                g2.dispose()
            }
        }

        private fun paintMessage(g2: Graphics2D) {
            val text = message ?: return
            val label = JBLabel(text, SwingConstants.CENTER)
            label.foreground = UIUtil.getContextHelpForeground()
            label.setSize(width, height)
            label.paint(g2)
        }

        private fun paintCheckerboard(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
            val cell = JBUI.scale(8)
            val clip = g2.clip
            g2.clipRect(x, y, w, h)
            var row = 0
            var cy = y
            while (cy < y + h) {
                var cx = x
                var col = 0
                while (cx < x + w) {
                    g2.color = if ((row + col) % 2 == 0) CHECKER_LIGHT else CHECKER_DARK
                    g2.fillRect(cx, cy, cell, cell)
                    cx += cell
                    col++
                }
                cy += cell
                row++
            }
            g2.clip = clip
        }

        /** The same pixels with alpha forced to opaque, for textures that store data in alpha. */
        private fun opaqueImage(img: BufferedImage): BufferedImage = opaque ?: run {
            val argb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            for (i in argb.indices) argb[i] = argb[i] or (0xFF shl 24)
            BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB).also {
                it.setRGB(0, 0, img.width, img.height, argb, 0, img.width)
                opaque = it
            }
        }
    }

    private companion object {
        const val ZOOM_STEP = 1.25
        const val MIN_ZOOM = 1.0 / 64
        const val MAX_ZOOM = 64.0

        /** 4096²: what the viewer decodes by default. Larger mips are one click away in the picker. */
        const val MAX_SHOWN_PIXELS = 4096L * 4096L

        val CHECKER_LIGHT = JBColor(0xFFFFFF, 0x3C3F41)
        val CHECKER_DARK = JBColor(0xD8D8D8, 0x2B2B2B)
    }
}
