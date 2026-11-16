package io.github.lemyst.enfusion.tools.run

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import io.github.lemyst.enfusion.tools.detect.DayZPlayer
import javax.swing.JComponent

/**
 * The clients a Client configuration starts, one row each: name, Goldberg Steam ID and profile, all
 * optional. Empty starts one client with no `-name=`.
 */
class DayZPlayersTable(private val onChange: () -> Unit) {

    private class Row(var name: String, var steamId: String, var profile: String) {
        fun toPlayer() = DayZPlayer(name.trim().ifEmpty { null }, steamId.trim().ifEmpty { null }, profile.trim().ifEmpty { null })
    }

    private fun column(title: String, get: (Row) -> String, set: (Row, String) -> Unit) = object : ColumnInfo<Row, String>(title) {
        override fun valueOf(item: Row): String = get(item)
        override fun isCellEditable(item: Row): Boolean = true
        override fun setValue(item: Row, value: String) {
            set(item, value)
            onChange()
        }
    }

    private val model = ListTableModel<Row>(
        column("Name (-name=)", { it.name }) { row, v -> row.name = v },
        column("Steam ID (empty: from the name)", { it.steamId }) { row, v -> row.steamId = v },
        column("Profile (empty: <Profiles>\\<Name>)", { it.profile }) { row, v -> row.profile = v },
    )

    private val table = TableView(model).apply {
        setShowGrid(false)
        visibleRowCount = 3
    }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction {
            model.addRow(Row("Player ${model.rowCount + 1}", "", ""))
            onChange()
        }
        .disableUpDownActions()
        .createPanel()

    init {
        model.addTableModelListener { onChange() }
    }

    var players: List<DayZPlayer>
        get() = model.items.map { it.toPlayer() }
        set(value) {
            model.items = value.mapTo(ArrayList()) { Row(it.name.orEmpty(), it.steamId.orEmpty(), it.profile.orEmpty()) }
        }
}
