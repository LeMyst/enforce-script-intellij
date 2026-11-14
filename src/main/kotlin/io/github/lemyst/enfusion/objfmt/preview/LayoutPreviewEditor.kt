package io.github.lemyst.enfusion.objfmt.preview

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * `.layout` files open as text with a live, read-only preview beside it: *Layout* in the editor's
 * top-right switches between text, text and preview, and preview only.
 */
class LayoutPreviewEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        !file.isDirectory && file.extension.equals("layout", ignoreCase = true)

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val text = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        return TextEditorWithPreview(text, LayoutPreviewEditor(project, file, text), "Layout", TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
    }

    override fun getEditorTypeId(): String = "enfusion-layout-preview"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * The preview half: the widget tree laid out on a screen of the chosen resolution, drawn with its
 * colours, texts and imageset images. Clicking a widget puts the caret on its declaration; moving
 * the caret outlines the widget it is in.
 *
 * It is rebuilt off the EDT, in a non-blocking read action, a moment after the text stops changing,
 * and only while it is on screen.
 */
class LayoutPreviewEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val textEditor: TextEditor,
) : UserDataHolderBase(), FileEditor {

    private class Resolution(val width: Int, val height: Int) {
        override fun toString(): String = "$width × $height"
    }

    private val resolutions = ComboBox(arrayOf(Resolution(1920, 1080), Resolution(2560, 1440), Resolution(1280, 720), Resolution(3840, 2160)))
    private val showHidden = JBCheckBox("Hidden widgets")
    private val status = JBLabel()
    private val canvas = Canvas()
    private val root = JPanel(BorderLayout())
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private var placed: List<Placed> = emptyList()
    private var images: Map<WidgetSpec, BufferedImage?> = emptyMap()
    private var selected: WidgetSpec? = null
    private var picture: BufferedImage? = null

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2)))
        toolbar.add(resolutions)
        toolbar.add(showHidden)
        toolbar.add(status)
        root.add(toolbar, BorderLayout.NORTH)
        root.add(canvas, BorderLayout.CENTER)
        resolutions.addActionListener { schedule(0) }
        showHidden.addActionListener { repaintPicture() }
        textEditor.editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule(300)
        }, this)
        textEditor.editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                val now = LayoutGeometry.at(placed, textEditor.editor.caretModel.offset)?.spec
                if (now !== selected) {
                    selected = now
                    repaintPicture()
                }
            }
        }, this)
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = select(e)
        })
        schedule(0)
    }

    private val resolution: Resolution get() = resolutions.selectedItem as Resolution

    private fun schedule(delay: Int) {
        alarm.cancelAllRequests()
        alarm.addRequest({ rebuild() }, delay)
    }

    private fun rebuild() {
        if (!root.isShowing && placed.isNotEmpty()) {
            // Off screen: rebuild once it shows again.
            schedule(1000)
            return
        }
        val res = resolution
        ReadAction.nonBlocking<Pair<List<Placed>, Map<WidgetSpec, BufferedImage?>>> {
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@nonBlocking emptyList<Placed>() to emptyMap()
            val roots = LayoutSpecs.of(psi)
            val laid = LayoutGeometry.layout(roots, res.width.toDouble(), res.height.toDouble())
            val pictures = HashMap<WidgetSpec, BufferedImage?>()
            for (p in laid) {
                if (p.spec.type == "ImageWidgetClass" && p.spec.value("image0") != null) {
                    pictures[p.spec] = LayoutSpecs.image(psi, p.spec, maxOf(p.rect.w, p.rect.h).toInt().coerceIn(16, 1024))
                }
            }
            laid to pictures
        }
            .withDocumentsCommitted(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { (laid, pictures) ->
                placed = laid
                images = pictures
                selected = LayoutGeometry.at(placed, textEditor.editor.caretModel.offset)?.spec
                val hidden = laid.count { it.hidden }
                status.text = "${laid.size} widgets" + if (hidden > 0) ", $hidden hidden" else ""
                repaintPicture()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun repaintPicture() {
        val res = resolution
        picture = LayoutRenderer.render(placed, res.width, res.height, showHidden.isSelected, selected) { spec, _ -> images[spec] }
        canvas.repaint()
    }

    /** The picture's scale and offset inside the canvas: fitted, centred, never enlarged past 1:1. */
    private fun fit(): Triple<Double, Int, Int>? {
        val image = picture ?: return null
        val scale = minOf(canvas.width.toDouble() / image.width, canvas.height.toDouble() / image.height, 1.0)
        val x = ((canvas.width - image.width * scale) / 2).toInt()
        val y = ((canvas.height - image.height * scale) / 2).toInt()
        return Triple(scale, x, y)
    }

    private fun select(e: MouseEvent) {
        val (scale, ox, oy) = fit() ?: return
        val hit = LayoutGeometry.hit(placed, (e.x - ox) / scale, (e.y - oy) / scale, showHidden.isSelected) ?: return
        val editor = textEditor.editor
        editor.caretModel.moveToOffset(hit.spec.offset.coerceIn(0, editor.document.textLength))
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
        selected = hit.spec
        repaintPicture()
    }

    private inner class Canvas : JComponent() {
        override fun paintComponent(g: Graphics) {
            val image = picture ?: return
            val (scale, x, y) = fit() ?: return
            g.drawImage(image, x, y, (image.width * scale).toInt(), (image.height * scale).toInt(), null)
        }
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = canvas
    override fun getName(): String = "Preview"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = Unit
}
