package io.github.lemyst.enfusion.stringtable

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.indexing.FileBasedIndex
import io.github.lemyst.enfusion.index.StringTableIndex
import io.github.lemyst.enfusion.stringtable.format.StringTableCsv
import io.github.lemyst.enfusion.stringtable.format.StringTableKeys
import java.awt.Dimension
import javax.swing.JComponent

/**
 * *Add Key*: the key, then one field per language. The key starts with the prefix most of the
 * file's keys share, and must be new in every indexed stringtable, since the same key in two
 * addons makes which translation wins depend on load order.
 */
class AddKeyDialog(private val project: Project, private val csv: StringTableCsv) : DialogWrapper(project) {

    private val keyField = JBTextField(StringTableKeys.commonPrefix(csv.dataRows.map { it.key }))
    private val fields = LinkedHashMap<Int, JBTextField>()

    val key: String get() = keyField.text.trim()

    init {
        title = "Add Stringtable Key"
        init()
    }

    fun values(): Map<Int, String> = fields.mapValues { it.value.text }.filterValues { it.isNotEmpty() }

    override fun createCenterPanel(): JComponent {
        val form = panel {
            row("Key:") { cell(keyField).align(AlignX.FILL).focused() }
            for ((index, name) in csv.columns.withIndex()) {
                if (index == 0) continue
                val field = JBTextField()
                fields[index] = field
                row("$name:") { cell(field).align(AlignX.FILL) }
            }
        }
        return JBScrollPane(form).apply {
            border = null
            preferredSize = Dimension(560, 420)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = keyField

    override fun doValidate(): ValidationInfo? {
        val key = key
        StringTableKeys.problem(key)?.let { return ValidationInfo(it, keyField) }
        if (csv.rowOf(key) != null) return ValidationInfo("This stringtable already has '$key'", keyField)
        if (DumbService.isDumb(project)) return null
        val elsewhere = FileBasedIndex.getInstance().getContainingFiles(StringTableIndex.NAME, key, GlobalSearchScope.allScope(project))
        if (elsewhere.isNotEmpty()) {
            val where = elsewhere.first().parent?.name ?: elsewhere.first().path
            return ValidationInfo("'$key' already exists in the stringtable of $where", keyField)
        }
        return null
    }
}
