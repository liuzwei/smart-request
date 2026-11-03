package dev.smartrequest.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.http.PathParameterResolver
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.AbstractCellEditor
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Path parameters are derived from `{name}` placeholders in the URL path. The key column is locked,
 * only the value can change, and values are substituted + URL-encoded by [PathParameterResolver].
 *
 * Like the header/param table the columns keep a fixed name:value ratio (3:7) and a single click
 * turns the value cell into an inline editor.
 */
class PathParameterPanel(
    private val variablesProvider: () -> Map<String, String>,
    private val onChanged: () -> Unit = {}
) : JBPanel<PathParameterPanel>(BorderLayout()) {

    private var request: HttpRequestConfig? = null
    private val model = Model()
    private val table: JBTable = object : ProportionalTable(model) {
        override fun computeWidths(tableWidth: Int): List<Int> = computeColumnWidths(tableWidth)
    }

    init {
        border = JBUI.Borders.empty(8, 6)
        val hint = JBLabel("Use {id} in the URL path. Values are URL-encoded when sent.").apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyBottom(4)
        }
        add(hint, BorderLayout.NORTH)

        table.apply {
            rowHeight = JBUI.scale(26)
            setShowGrid(false)
            intercellSpacing = Dimension(JBUI.scale(8), 0)
            fillsViewportHeight = true
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            putClientProperty("terminateEditOnFocusLost", true)
            columnModel.getColumn(NAME_COLUMN).apply {
                preferredWidth = JBUI.scale(200)
                resizable = false
            }
            columnModel.getColumn(VALUE_COLUMN).apply {
                preferredWidth = JBUI.scale(460)
                resizable = false
            }
            setDefaultRenderer(String::class.java, ValueRenderer())
            setDefaultEditor(String::class.java, ValueEditor())
            installSingleClickEditing(beginEdit = { row, viewColumn -> beginEdit(row, viewColumn) })
        }

        add(JBScrollPane(table).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun bind(request: HttpRequestConfig) {
        this.request = request
        syncFromUrl(request.url)
    }

    fun syncFromUrl(url: String) {
        val request = request ?: return
        val names = PathParameterResolver.detectNames(url)
        val previous = request.pathParams.associateBy { it.key }
        val next = names.map { name ->
            KeyValue.of(name, previous[name]?.value.orEmpty())
        }
        request.pathParams.clear()
        request.pathParams.addAll(next)
        model.setRows(next)
        onChanged()
    }

    fun count(): Int = model.rowCount

    fun commitPendingEdit() {
        if (table.isEditing) {
            table.cellEditor?.stopCellEditing()
        }
    }

    fun refreshVariables() {
        table.repaint()
        val editor = table.cellEditor
        if (editor is ValueEditor) editor.refreshVariables()
    }

    /** Widths for the name/value columns when the whole table is [totalWidth] pixels wide. */
    internal fun computeColumnWidths(totalWidth: Int): List<Int> {
        if (totalWidth <= 0) return emptyList()
        return proportionalWidths(
            totalWidth,
            COLUMN_WEIGHTS,
            COLUMN_MINIMUMS.map { JBUI.scale(it) }
        ).toList()
    }

    internal fun beginEdit(row: Int, viewColumn: Int): Boolean {
        if (viewColumn != VALUE_COLUMN) return false
        if (!model.isCellEditable(row, viewColumn)) return false
        if (table.isEditing && table.editingRow == row && table.editingColumn == viewColumn) return true
        if (!table.editCellAt(row, viewColumn)) return false
        focusCellEditor(table)
        return true
    }

    private inner class Model : AbstractTableModel() {
        private var rows: MutableList<KeyValue> = mutableListOf()

        fun setRows(newRows: List<KeyValue>) {
            rows = newRows.toMutableList()
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 2

        override fun getColumnName(column: Int): String =
            if (column == NAME_COLUMN) "Path Parameter" else "Value"

        override fun getColumnClass(column: Int): Class<*> = String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == VALUE_COLUMN

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows.getOrNull(rowIndex) ?: return ""
            return if (columnIndex == NAME_COLUMN) "{${row.key}}" else row.value
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != VALUE_COLUMN) return
            val row = rows.getOrNull(rowIndex) ?: return
            row.value = value?.toString().orEmpty()
            fireTableCellUpdated(rowIndex, columnIndex)
            onChanged()
        }
    }

    private inner class ValueRenderer : JBLabel(), TableCellRenderer {
        init {
            isOpaque = true
            border = cellPadding
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val text = value?.toString().orEmpty()
            background = if (selected) table.selectionBackground else table.background
            font = table.font
            val baseForeground = if (selected) table.selectionForeground else table.foreground
            if (column == NAME_COLUMN) {
                foreground = if (selected) table.selectionForeground else UIUtil.getContextHelpForeground()
                this.text = text
                toolTipText = "Name is derived from the URL"
            } else {
                this.text = VariableHtml.render(text, variablesProvider().keys, baseForeground, table.font)
                foreground = baseForeground
                toolTipText = text.takeIf { it.isNotEmpty() }
            }
            return this
        }
    }

    private inner class ValueEditor : AbstractCellEditor(), TableCellEditor {
        private var editRow = -1
        private var loading = false
        private val field = VariableTextField(variablesProvider, requestFocusInPopup = false)

        init {
            // Write every keystroke back to the model, so the value survives even when the editor is
            // closed by something else (for example the variable chooser taking the focus).
            field.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = store()
                override fun removeUpdate(e: DocumentEvent) = store()
                override fun changedUpdate(e: DocumentEvent) = store()

                private fun store() {
                    if (loading || editRow < 0) return
                    model.setValueAt(field.text, editRow, VALUE_COLUMN)
                }
            })
        }

        fun refreshVariables() {
            field.refreshVariables()
        }

        override fun getTableCellEditorComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            row: Int,
            column: Int
        ): Component {
            editRow = row
            loading = true
            try {
                field.text = value?.toString().orEmpty()
                field.font = Font(Font.MONOSPACED, Font.PLAIN, table.font.size)
                field.border = JBUI.Borders.empty(0, 8)
                field.refreshVariables()
            } finally {
                loading = false
            }
            return field
        }

        override fun getCellEditorValue(): Any = field.text
    }

    private companion object {
        const val NAME_COLUMN = 0
        const val VALUE_COLUMN = 1

        val COLUMN_WEIGHTS = listOf(3.0, 7.0)
        val COLUMN_MINIMUMS = listOf(80, 140)
    }
}
