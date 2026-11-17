package io.github.lemyst.enfusion.objfmt.preview

import com.intellij.ide.util.PropertiesComponent
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
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.pow
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JList
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
 * The wheel zooms around the pointer and dragging pans, as in Workbench; *Fit* fits the screen to
 * the pane again, which is also what a resize does until the view is zoomed or panned.
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

    // Smallest to largest, with 1920 × 1080 selected: the resolution the vanilla layouts are drawn for.
    private val resolutions = ComboBox(arrayOf(Resolution(1280, 720), Resolution(1920, 1080), Resolution(2560, 1440), Resolution(3840, 2160)))
        .apply { selectedIndex = 1 }
    private val showHidden = JBCheckBox("Hidden widgets")

    /** *Keys* (null) or a stringtable column, filled from the stringtables the layout's keys are in. */
    private val languages = ComboBox<String>().apply {
        // A subclass: SimpleListCellRenderer.create is deprecated on 2026.3.
        renderer = object : SimpleListCellRenderer<String?>() {
            override fun customize(list: JList<out String?>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value ?: KEYS
            }
        }
        toolTipText = "The language #key texts are shown in"
    }
    private var translations = LayoutTranslations.Table.EMPTY
    private var frames: Map<WidgetSpec, LayoutRenderer.Frame> = emptyMap()
    private val status = JBLabel()
    private val zoomLabel = JBLabel()
    private val fitButton = JButton("Fit")
    private val actualButton = JButton("100%")
    private val canvas = Canvas()
    private val root = JPanel(BorderLayout())
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private var placed: List<Placed> = emptyList()
    private var images: Map<WidgetSpec, BufferedImage?> = emptyMap()
    private var selected: WidgetSpec? = null

    /** The view: layout pixels to canvas pixels. Null while the screen is fitted to the pane. */
    private var view: View? = null

    private data class View(val scale: Double, val x: Double, val y: Double)

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2)))
        toolbar.add(resolutions)
        toolbar.add(languages)
        toolbar.add(showHidden)
        toolbar.add(fitButton)
        toolbar.add(actualButton)
        toolbar.add(zoomLabel)
        toolbar.add(status)
        root.add(toolbar, BorderLayout.NORTH)
        root.add(canvas, BorderLayout.CENTER)
        resolutions.addActionListener {
            view = null
            schedule(0)
        }
        showHidden.addActionListener { repaintPicture() }
        languages.addActionListener {
            if (!fillingLanguages) {
                PropertiesComponent.getInstance().setValue(LANGUAGE_KEY, languages.selectedItem as String? ?: KEYS)
                repaintPicture()
            }
        }
        fitButton.toolTipText = "Fit the screen to the pane"
        fitButton.addActionListener {
            view = null
            repaintPicture()
        }
        actualButton.toolTipText = "One layout pixel to one screen pixel, around the pane's centre"
        actualButton.addActionListener { zoomTo(1.0, canvas.width / 2.0, canvas.height / 2.0) }
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
        val mouse = object : MouseAdapter() {
            private var pressX = 0
            private var pressY = 0
            private var dragging = false

            override fun mousePressed(e: MouseEvent) {
                pressX = e.x
                pressY = e.y
                dragging = false
            }

            override fun mouseDragged(e: MouseEvent) {
                // A few pixels of slack, so that a click with a shaky hand still selects.
                if (!dragging && abs(e.x - pressX) + abs(e.y - pressY) < JBUI.scale(4)) return
                if (!dragging) {
                    dragging = true
                    canvas.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
                val v = currentView() ?: return
                view = v.copy(x = v.x + e.x - pressX, y = v.y + e.y - pressY)
                pressX = e.x
                pressY = e.y
                repaintPicture()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (dragging) {
                    dragging = false
                    canvas.cursor = Cursor.getDefaultCursor()
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    select(e)
                }
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val v = currentView() ?: return
                zoomTo(v.scale * 1.15.pow(-e.preciseWheelRotation), e.x.toDouble(), e.y.toDouble())
            }
        }
        canvas.addMouseListener(mouse)
        canvas.addMouseMotionListener(mouse)
        canvas.addMouseWheelListener(mouse)
        // While fitted, a resize changes the zoom shown in the toolbar.
        canvas.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = repaintPicture()
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
        ReadAction.nonBlocking<Built> {
            val psi = PsiManager.getInstance(project).findFile(file)
                ?: return@nonBlocking Built(emptyList(), emptyMap(), LayoutTranslations.Table.EMPTY, emptyMap())
            val roots = LayoutSpecs.of(psi)
            val laid = LayoutGeometry.layout(roots, res.width.toDouble(), res.height.toDouble())
            val pictures = HashMap<WidgetSpec, BufferedImage?>()
            for (p in laid) {
                if (p.spec.type == "ImageWidgetClass" && LayoutSpecs.hasImage(p.spec)) {
                    pictures[p.spec] = LayoutSpecs.image(psi, p.spec, maxOf(p.rect.w, p.rect.h).toInt().coerceIn(16, 1024))
                }
            }
            val keys = laid.mapNotNullTo(HashSet()) { p -> p.spec.text?.takeIf { it.startsWith('#') }?.substring(1) }
            Built(laid, pictures, LayoutTranslations.of(project, keys), LayoutStyles.frames(project, laid.map { it.spec }))
        }
            .withDocumentsCommitted(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { (laid, pictures, table, styled) ->
                placed = laid
                images = pictures
                translations = table
                frames = styled
                fillLanguages(table.languages)
                selected = LayoutGeometry.at(placed, textEditor.editor.caretModel.offset)?.spec
                val hidden = laid.count { it.hidden }
                status.text = "${laid.size} widgets" + if (hidden > 0) ", $hidden hidden" else ""
                repaintPicture()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private class Built(
        val placed: List<Placed>,
        val images: Map<WidgetSpec, BufferedImage?>,
        val translations: LayoutTranslations.Table,
        val frames: Map<WidgetSpec, LayoutRenderer.Frame>,
    ) {
        operator fun component1() = placed
        operator fun component2() = images
        operator fun component3() = translations
        operator fun component4() = frames
    }

    private var fillingLanguages = false

    /**
     * Offers *Keys* and every language the layout's stringtables have, keeping the remembered choice
     * selected. The choice is application-wide: someone checking French wants French in every layout.
     * Until it has been changed, English is shown when there is one.
     */
    private fun fillLanguages(found: List<String>) {
        val items = listOf<String?>(null) + found
        val current = (0 until languages.itemCount).map { languages.getItemAt(it) }
        if (current != items) {
            fillingLanguages = true
            try {
                languages.removeAllItems()
                items.forEach { languages.addItem(it) }
            } finally {
                fillingLanguages = false
            }
        }
        val wanted = PropertiesComponent.getInstance().getValue(LANGUAGE_KEY, "english")
        fillingLanguages = true
        try {
            languages.selectedItem = found.firstOrNull { it.equals(wanted, ignoreCase = true) }
        } finally {
            fillingLanguages = false
        }
        languages.isVisible = found.isNotEmpty()
    }

    private val language: String? get() = languages.selectedItem as String?

    private fun repaintPicture() {
        zoomLabel.text = currentView()?.let { "${(it.scale * 100).toInt()}%" } ?: ""
        canvas.repaint()
    }

    /** The screen fitted to the pane with a small margin, centred. */
    private fun fitted(): View? {
        val res = resolution
        val margin = JBUI.scale(8).toDouble()
        val w = canvas.width - 2 * margin
        val h = canvas.height - 2 * margin
        if (w <= 0 || h <= 0) return null
        val scale = minOf(w / res.width, h / res.height)
        return View(scale, (canvas.width - res.width * scale) / 2, (canvas.height - res.height * scale) / 2)
    }

    private fun currentView(): View? = view ?: fitted()

    /** Zooms to [scale], keeping the layout point under ([cx], [cy]) where it is. */
    private fun zoomTo(scale: Double, cx: Double, cy: Double) {
        val v = currentView() ?: return
        val s = scale.coerceIn(0.05, 8.0)
        view = View(s, cx - (cx - v.x) * s / v.scale, cy - (cy - v.y) * s / v.scale)
        repaintPicture()
    }

    /** Where the last selecting click was, in canvas pixels, so that clicking there again goes deeper. */
    private var lastClick: java.awt.Point? = null

    /**
     * Selects the smallest widget under the pointer; clicking the same spot again selects the next
     * larger one, round to the smallest, so that a panel under its children can still be reached.
     */
    private fun select(e: MouseEvent) {
        val v = currentView() ?: return
        val under = LayoutGeometry.hits(placed, (e.x - v.x) / v.scale, (e.y - v.y) / v.scale, showHidden.isSelected)
        if (under.isEmpty()) return
        val again = lastClick?.let { it.distance(e.point) < JBUI.scale(4) } == true
        lastClick = e.point
        val current = if (again) under.indexOfFirst { it.spec === selected } else -1
        val hit = under[(current + 1) % under.size]
        val editor = textEditor.editor
        editor.caretModel.moveToOffset(hit.spec.offset.coerceIn(0, editor.document.textLength))
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
        selected = hit.spec
        repaintPicture()
    }

    private inner class Canvas : JComponent() {
        override fun paintComponent(g: Graphics) {
            if (placed.isEmpty()) return
            val v = currentView() ?: return
            val res = resolution
            val g2 = g.create() as Graphics2D
            try {
                g2.translate(v.x, v.y)
                g2.scale(v.scale, v.scale)
                val lang = language
                LayoutRenderer.paint(
                    g2, placed, res.width, res.height, v.scale, showHidden.isSelected, selected,
                    text = { spec -> spec.text?.let { translations.show(it, lang) } },
                    frame = { spec -> frames[spec] },
                ) { spec, _ -> images[spec] }
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        const val KEYS = "Keys"
        const val LANGUAGE_KEY = "enfusion.layoutPreview.language"
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
