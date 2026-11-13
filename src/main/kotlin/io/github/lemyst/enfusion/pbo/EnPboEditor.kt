package io.github.lemyst.enfusion.pbo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.testFramework.BinaryLightVirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.lemyst.enfusion.pbo.format.PboArchive
import io.github.lemyst.enfusion.pbo.format.PboEntry
import io.github.lemyst.enfusion.pbo.format.PboPacking
import java.awt.BorderLayout
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/** Opens a `.pbo` as a list of its entries, replacing the binary-file placeholder. */
class EnPboEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        FileTypeRegistry.getInstance().isFileOfType(file, EnPboFileType)

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = EnPboEditor(project, file)

    override fun getEditorTypeId(): String = "enfusion-pbo-browser"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * The browser: every entry with its size and packing, a filter, and Enter or double-click to open
 * an entry in a read-only tab.
 *
 * Only the header is read to list the entries, and an entry is read when it is opened, never the
 * whole PBO: a texture PBO can be gigabytes. The PBO is not held open between reads, so Steam can
 * replace it while the browser is showing; the list reloads when it does.
 *
 * An entry the PBO cache already unpacked (the PBO is a configured root) opens as that file, with
 * full navigation. Anything else opens as an in-memory file: scripts and configs with their
 * language, textures in the texture viewer, other text as plain text.
 */
class EnPboEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val model = EntryModel()
    private val table = JBTable(model)
    private val sorter = TableRowSorter(model)
    private val filter = SearchTextField(false)
    private val info = JBLabel()
    private val status = JBLabel()
    private val root = JPanel(BorderLayout())

    @Volatile
    private var disposed = false

    /** Bumped on every load, so a slow load that finishes after a newer one is dropped. */
    private var generation = 0

    /** The listed PBO's prefix, which places its entries in the PBO cache. */
    private var prefix: String? = null

    /** Entries opened from this PBO, so opening one again shows its tab instead of a second copy. */
    private val opened = HashMap<Int, VirtualFile>()

    init {
        table.rowSorter = sorter
        sorter.setComparator(COLUMN_SIZE, compareBy<Long> { it })
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.columnModel.getColumn(COLUMN_SIZE).cellRenderer = object : DefaultTableCellRenderer() {
            init {
                horizontalAlignment = SwingConstants.RIGHT
            }

            override fun setValue(value: Any?) {
                text = (value as? Long)?.let { StringUtil.formatFileSize(it) } ?: ""
            }
        }
        table.columnModel.getColumn(COLUMN_NAME).preferredWidth = JBUI.scale(560)
        table.columnModel.getColumn(COLUMN_SIZE).preferredWidth = JBUI.scale(90)
        table.columnModel.getColumn(COLUMN_PACKING).preferredWidth = JBUI.scale(130)
        table.emptyText.text = "Loading…"

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) openSelected()
            }
        })
        table.registerKeyboardAction({ openSelected() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)

        filter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val text = filter.text.trim()
                sorter.rowFilter = if (text.isEmpty()) {
                    null
                } else {
                    object : RowFilter<EntryModel, Int>() {
                        override fun include(entry: Entry<out EntryModel, out Int>): Boolean =
                            model.entries[entry.identifier].name.contains(text, ignoreCase = true)
                    }
                }
            }
        })

        val north = JPanel(BorderLayout(JBUI.scale(8), 0))
        north.border = JBUI.Borders.empty(4, 8)
        north.add(info, BorderLayout.CENTER)
        north.add(filter, BorderLayout.EAST)
        status.border = JBUI.Borders.empty(2, 8)

        root.add(north, BorderLayout.NORTH)
        root.add(JBScrollPane(table), BorderLayout.CENTER)
        root.add(status, BorderLayout.SOUTH)

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.file == file }) load()
                }
            })
        load()
    }

    private fun pboPath(): Path? = if (file.isInLocalFileSystem) file.toNioPath() else null

    private fun load() {
        val token = ++generation
        ApplicationManager.getApplication().executeOnPooledThread {
            val path = pboPath()
            val loaded: Loaded = if (path == null) {
                Loaded.Failed("Only a .pbo on disk can be browsed.")
            } else {
                try {
                    PboArchive.open(path).use { Loaded.Listed(it.entries, it.properties, it.prefix) }
                } catch (e: IOException) {
                    Loaded.Failed("${file.name} cannot be read: ${e.message}")
                }
            }
            onEdt(token) { show(loaded) }
        }
    }

    private fun show(loaded: Loaded) {
        opened.clear()
        prefix = (loaded as? Loaded.Listed)?.prefix
        when (loaded) {
            is Loaded.Failed -> {
                model.set(emptyList())
                info.text = ""
                table.emptyText.text = loaded.message
            }

            is Loaded.Listed -> {
                model.set(loaded.entries)
                table.emptyText.text = "No entries"
                val total = loaded.entries.sumOf { it.size }
                val encrypted = loaded.entries.count { it.packing == PboPacking.ENCRYPTED }
                info.text = buildString {
                    append(if (loaded.prefix != null) "Prefix ${loaded.prefix}" else "No prefix")
                    loaded.properties["product"]?.let { append("  ·  $it") }
                    loaded.properties["version"]?.let { append("  ·  version $it") }
                    append("  ·  ${loaded.entries.size} entries, ${StringUtil.formatFileSize(total)}")
                    if (encrypted > 0) append("  ·  $encrypted encrypted")
                }
                status.text = "Enter or double-click opens an entry, read-only."
            }
        }
    }

    private fun openSelected() {
        val rows = table.selectedRows.map { table.convertRowIndexToModel(it) }
        if (rows.isEmpty()) return
        val path = pboPath() ?: return
        val token = generation
        val prefix = prefix
        val snapshot = rows.map { it to model.entries[it] }
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = snapshot.map { (index, entry) -> index to resolve(path, prefix, index, entry) }
            onEdt(token) {
                val refused = ArrayList<Pair<Int, String>>()
                for ((index, result) in results) {
                    when (result) {
                        is Opened.Cached -> open(result.file)
                        is Opened.View -> when (val view = result.view) {
                            is PboEntryView.Unavailable -> refused += index to view.message
                            else -> open(opened.getOrPut(index) { lightFile(view) })
                        }
                    }
                }
                if (refused.isNotEmpty()) explain(refused.first().first, refused.map { it.second })
            }
        }
    }

    /**
     * Opens and focuses [file] in whichever editor its type has.
     *
     * Through a descriptor rather than `FileEditorManager.openFile`: 2026.3 deprecates every
     * blocking `openFile` in favour of `requestOpenFile`, which 2025.2 lacks in that form (there it
     * is experimental and returns `void`, so a call compiled against it fails on 2026.3). A
     * descriptor's `navigate` is stable on every supported release. It would ask to associate a file
     * type for an unknown one, but no entry reaches here as unknown: [PboEntryView] turns those
     * into plain text or refuses them.
     */
    private fun open(file: VirtualFile) {
        OpenFileDescriptor(project, file).navigate(true)
    }

    /**
     * Says why an entry did not open, in a balloon on its row: where the user is looking after a
     * double-click. A line under the table went unnoticed, since nothing else on screen changes
     * when an entry is refused.
     */
    private fun explain(modelRow: Int, messages: List<String>) {
        val viewRow = table.convertRowIndexToView(modelRow)
        val at = if (viewRow >= 0) {
            val cell = table.getCellRect(viewRow, COLUMN_NAME, true)
            table.scrollRectToVisible(cell)
            RelativePoint(table, Point(cell.x + JBUI.scale(48), cell.y + cell.height))
        } else {
            RelativePoint.getCenterOf(table)
        }
        val distinct = messages.distinct()
        val html = distinct.take(MAX_MESSAGES).joinToString("<br>") { StringUtil.escapeXmlEntities(it) } +
            if (distinct.size > MAX_MESSAGES) "<br>…and ${distinct.size - MAX_MESSAGES} more." else ""
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(html, MessageType.WARNING, null)
            .setFadeoutTime(8000)
            .setHideOnClickOutside(true)
            .createBalloon()
            .show(at, Balloon.Position.below)
    }

    /** Off the EDT: the unpacked copy if the PBO cache has one, else the entry read from the PBO. */
    private fun resolve(path: Path, prefix: String?, index: Int, entry: PboEntry): Opened {
        cachedCopy(path, prefix, entry)?.let { return Opened.Cached(it) }
        return try {
            PboArchive.open(path).use { archive ->
                // The PBO may have been replaced since it was listed; then the list reloads, and
                // this read must not return another entry's bytes.
                val current = archive.entries.getOrNull(index)
                if (current == null || current.name != entry.name || current.dataOffset != entry.dataOffset) {
                    Opened.View(PboEntryView.Unavailable("${file.name} changed on disk; open the entry again."))
                } else {
                    Opened.View(PboEntryViews.view(archive, current, file.name))
                }
            }
        } catch (e: IOException) {
            Opened.View(PboEntryView.Unavailable("${file.name} cannot be read: ${e.message}"))
        }
    }

    private fun cachedCopy(path: Path, prefix: String?, entry: PboEntry): VirtualFile? {
        val dir = PboCache.existingDir(path.toString()) ?: return null
        val relative = PboExtractor.outputPath(prefix, entry.name) ?: return null
        val cached = dir.resolve(relative)
        if (!Files.isRegularFile(cached)) return null
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(cached)
    }

    private fun lightFile(view: PboEntryView): VirtualFile = when (view) {
        is PboEntryView.Text -> LightVirtualFile(view.name, view.fileType, view.text)
        is PboEntryView.Binary -> BinaryLightVirtualFile(view.name, view.fileType, view.bytes)
        is PboEntryView.Unavailable -> error("nothing to open")
    }.apply { isWritable = false }

    private fun onEdt(token: Int, run: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && token == generation) run()
        }, ModalityState.any())
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = table
    override fun getName(): String = "PBO"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        disposed = true
    }

    private sealed interface Loaded {
        class Listed(val entries: List<PboEntry>, val properties: Map<String, String>, val prefix: String?) : Loaded
        class Failed(val message: String) : Loaded
    }

    private sealed interface Opened {
        class Cached(val file: VirtualFile) : Opened
        class View(val view: PboEntryView) : Opened
    }

    private class EntryModel : AbstractTableModel() {
        var entries: List<PboEntry> = emptyList()
            private set

        fun set(list: List<PboEntry>) {
            entries = list
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = entries.size
        override fun getColumnCount(): Int = 3
        override fun getColumnName(column: Int): String = when (column) {
            COLUMN_NAME -> "Name"
            COLUMN_SIZE -> "Size"
            else -> "Packing"
        }

        override fun getColumnClass(column: Int): Class<*> = if (column == COLUMN_SIZE) Long::class.javaObjectType else String::class.java

        override fun getValueAt(row: Int, column: Int): Any {
            val e = entries[row]
            return when (column) {
                COLUMN_NAME -> e.name
                COLUMN_SIZE -> e.size
                else -> when (e.packing) {
                    PboPacking.STORED -> "stored"
                    PboPacking.COMPRESSED ->
                        if (e.originalSize > 0) "compressed, ${100 * e.dataSize / e.originalSize}%" else "compressed"
                    PboPacking.ENCRYPTED -> "encrypted"
                    PboPacking.UNKNOWN -> "unknown"
                }
            }
        }
    }

    private companion object {
        const val COLUMN_NAME = 0
        const val COLUMN_SIZE = 1
        const val COLUMN_PACKING = 2

        /** Opening a hundred selected models at once must not produce a balloon a screen tall. */
        const val MAX_MESSAGES = 5
    }
}
