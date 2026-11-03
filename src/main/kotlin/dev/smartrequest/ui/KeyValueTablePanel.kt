package dev.smartrequest.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.KeyValue
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.AbstractCellEditor
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Editable key/value table used for params, headers, and environment variables.
 *
 * Rows supplied as [fixedRows] are rendered read-only and cannot be removed, which is how global
 * headers/params stay locked in the request editor. The key and value columns share the available
 * width 3:4 so long keys stay readable, and a single click turns a cell into an inline editor.
 */
class KeyValueTablePanel(
    private val onChanged: () -> Unit = {},
    private val showLegend: Boolean = true,
    private val variablesProvider: (() -> Map<String, String>)? = null
) : JBPanel<KeyValueTablePanel>(BorderLayout()) {

    private var target: MutableList<KeyValue>? = null
    private var fixedRows: List<KeyValue> = emptyList()
    private val model = Model()
    private val table: JBTable = object : ProportionalTable(model) {
        override fun computeWidths(tableWidth: Int): List<Int> =
            listOf(JBUI.scale(CHECK_WIDTH)) + computeColumnWidths(tableWidth)
    }

    private val addButton = flatButton("+", "Add row")
    private val removeButton = flatButton("−", "Remove selected row")

    init {
        border = JBUI.Borders.empty()
        addButton.addActionListener { addRow() }
        removeButton.addActionListener { removeSelectedRows() }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(addButton)
            add(removeButton)
        }

        table.apply {
            rowHeight = JBUI.scale(26)
            selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            setShowGrid(false)
            intercellSpacing = Dimension(JBUI.scale(8), 0)
            fillsViewportHeight = true
            putClientProperty("terminateEditOnFocusLost", true)
            columnModel.getColumn(CHECK_COLUMN).apply {
                preferredWidth = JBUI.scale(CHECK_WIDTH)
                minWidth = JBUI.scale(CHECK_WIDTH)
                maxWidth = JBUI.scale(CHECK_WIDTH)
                resizable = false
            }
            columnModel.getColumn(KEY_COLUMN).apply {
                preferredWidth = JBUI.scale(260)
                resizable = false
            }
            columnModel.getColumn(VALUE_COLUMN).apply {
                preferredWidth = JBUI.scale(340)
                resizable = false
            }
            setDefaultRenderer(java.lang.Boolean::class.javaObjectType, BooleanRenderer())
            setDefaultRenderer(String::class.java, ValueRenderer())
            setDefaultEditor(String::class.java, ValueEditor())
            selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) updateButtons() }
            installSingleClickEditing(
                onClick = { row, viewColumn -> viewColumn == CHECK_COLUMN && toggleEnabled(row) },
                beginEdit = { row, viewColumn -> beginEdit(row, viewColumn) }
            )
        }

        val center = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(table).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }
        add(center, BorderLayout.CENTER)

        if (showLegend) {
            val legend = JBLabel(GLOBAL_LEGEND)
                .apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyTop(4)
                }
            add(legend, BorderLayout.SOUTH)
        }

        updateButtons()
    }

    fun bind(target: MutableList<KeyValue>, fixed: List<KeyValue> = emptyList()) {
        this.target = target
        this.fixedRows = fixed
        model.rebuild()
        updateButtons()
    }

    fun refreshFixed(fixed: List<KeyValue>) {
        fixedRows = fixed
        model.rebuild()
        updateButtons()
    }

    fun fixedCount(): Int = fixedRows.size

    fun commitPendingEdit() {
        if (table.isEditing) {
            table.cellEditor?.stopCellEditing()
        }
    }

    fun refreshVariableHighlighting() {
        table.repaint()
        if (table.isEditing) {
            (table.editorComponent as? VariableTextField)?.refreshVariables()
        }
    }

    fun addRow() {
        val target = target ?: return
        val value = KeyValue()
        target.add(value)
        model.rebuild()
        val row = fixedRows.size + target.size - 1
        if (row >= 0) {
            table.setRowSelectionInterval(row, row)
            table.scrollRowIntoView(row)
            beginEdit(row, KEY_COLUMN)
        }
        updateButtons()
        onChanged()
    }

    fun removeSelectedRows() {
        val target = target ?: return
        val selected = table.selectedRows
            .filter { it >= fixedRows.size }
            .map { it - fixedRows.size }
            .distinct()
            .sortedDescending()
        if (selected.isEmpty()) return
        selected.forEach { index ->
            if (index in target.indices) target.removeAt(index)
        }
        model.rebuild()
        updateButtons()
        onChanged()
    }

    /**
     * Widths for the key/value columns when the whole table is [totalWidth] pixels wide. The
     * checkbox column keeps a fixed width, the rest is split 3:4.
     *
     * JTable carves the intercell spacing out of every column instead of adding it between columns,
     * so the widths have to fill the table exactly or a dead strip is left on the right.
     */
    internal fun computeColumnWidths(totalWidth: Int): List<Int> {
        if (totalWidth <= 0) return emptyList()
        return proportionalWidths(
            totalWidth - JBUI.scale(CHECK_WIDTH),
            COLUMN_WEIGHTS,
            COLUMN_MINIMUMS.map { JBUI.scale(it) }
        ).toList()
    }

    /** Current pixel width of every column except the checkbox column. */
    internal fun appliedColumnWidths(): List<Int> =
        (1 until table.columnModel.columnCount).map { table.columnModel.getColumn(it).width }

    internal fun tableView(): JBTable = table

    /** The cell that is currently in edit mode, or null when no editor is open. */
    internal fun editingCell(): Pair<Int, Int>? =
        if (table.isEditing) table.editingRow to table.editingColumn else null

    /** Starts the inline editor on [row]; [viewColumn] is a view index, not a model index. */
    internal fun beginEdit(row: Int, viewColumn: Int): Boolean {
        if (viewColumn <= CHECK_COLUMN) return false
        val modelColumn = table.convertColumnIndexToModel(viewColumn)
        if (!model.isCellEditable(row, modelColumn)) return false
        if (table.isEditing && table.editingRow == row && table.editingColumn == viewColumn) return true
        if (!table.editCellAt(row, viewColumn)) return false
        focusCellEditor(table)
        return true
    }

    /** Flips the enabled flag of [row], which is what a click on the checkbox column does. */
    internal fun toggleEnabled(row: Int): Boolean {
        val data = model.rowAt(row) ?: return false
        if (data.fixed) return false
        model.setValueAt(!data.value.enabled, row, CHECK_COLUMN)
        return true
    }

    /** Simulates editing a row, used by tests that exercise the surrounding panels. */
    internal fun setRowValues(row: Int, key: String, value: String) {
        model.setValueAt(key, row, KEY_COLUMN)
        model.setValueAt(value, row, VALUE_COLUMN)
    }

    private fun updateButtons() {
        removeButton.isEnabled = table.selectedRows.any { it >= fixedRows.size }
        addButton.isEnabled = target != null
    }

    private inner class Model : AbstractTableModel() {
        private val rows = ArrayList<Row>()

        fun rowAt(rowIndex: Int): Row? = rows.getOrNull(rowIndex)

        fun rebuild() {
            rows.clear()
            fixedRows.forEach { rows += Row(it, fixed = true) }
            target?.forEach { rows += Row(it, fixed = false) }
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = when (column) {
            CHECK_COLUMN -> ""
            KEY_COLUMN -> "Key"
            else -> "Value"
        }

        override fun getColumnClass(column: Int): Class<*> =
            if (column == CHECK_COLUMN) java.lang.Boolean::class.javaObjectType else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            // The checkbox is toggled by a single click instead of an editor.
            if (columnIndex == CHECK_COLUMN) return false
            val row = rows.getOrNull(rowIndex) ?: return false
            return !row.fixed
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                CHECK_COLUMN -> row.value.enabled
                KEY_COLUMN -> row.value.key
                else -> row.value.value
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val row = rows.getOrNull(rowIndex) ?: return
            if (row.fixed) return
            when (columnIndex) {
                CHECK_COLUMN -> row.value.enabled = value == true
                KEY_COLUMN -> row.value.key = value?.toString().orEmpty()
                else -> row.value.value = value?.toString().orEmpty()
            }
            fireTableCellUpdated(rowIndex, columnIndex)
            onChanged()
        }
    }

    private data class Row(val value: KeyValue, val fixed: Boolean)

    private inner class BooleanRenderer : JCheckBox(), TableCellRenderer {
        init {
            isOpaque = true
            horizontalAlignment = CENTER
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val fixed = row < fixedRows.size
            isSelected = value == true
            isEnabled = !fixed
            background = when {
                selected -> table.selectionBackground
                fixed -> globalBackground()
                else -> table.background
            }
            return this
        }
    }

    private inner class ValueRenderer : JLabel(), TableCellRenderer {
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
            val fixed = row < fixedRows.size
            val raw = value?.toString().orEmpty()
            background = when {
                selected -> table.selectionBackground
                fixed -> globalBackground()
                else -> table.background
            }
            foreground = when {
                selected -> table.selectionForeground
                fixed -> UIUtil.getInactiveTextColor()
                else -> table.foreground
            }
            font = if (fixed) table.font.deriveFont(Font.ITALIC) else table.font
            text = if (variablesProvider != null && raw.contains("{{")) {
                VariableHtml.render(raw, variablesProvider().keys, foreground, font)
            } else {
                raw
            }
            toolTipText = when {
                fixed && raw.isNotEmpty() -> "$raw — global value, edit it in Global Settings"
                fixed -> "Global value, edit it in Global Settings"
                raw.isNotEmpty() -> raw
                else -> null
            }
            return this
        }
    }

    private inner class ValueEditor : AbstractCellEditor(), TableCellEditor {
        private var editRow = -1
        private var editColumn = -1
        private var loading = false
        private val field: JTextField = if (variablesProvider != null) {
            VariableTextField(variablesProvider, requestFocusInPopup = false)
        } else {
            JTextField()
        }

        init {
            field.border = BorderFactory.createEmptyBorder(0, JBUI.scale(8), 0, JBUI.scale(8))
            // Write every keystroke back to the model, so the value survives even when the editor is
            // closed by something else (for example the variable chooser taking the focus).
            field.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = store()
                override fun removeUpdate(e: DocumentEvent) = store()
                override fun changedUpdate(e: DocumentEvent) = store()

                private fun store() {
                    if (loading || editRow < 0 || editColumn < 0) return
                    model.setValueAt(field.text, editRow, editColumn)
                }
            })
        }

        override fun getTableCellEditorComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            row: Int,
            column: Int
        ): Component {
            editRow = row
            editColumn = table.convertColumnIndexToModel(column)
            loading = true
            try {
                field.text = value?.toString().orEmpty()
                field.font = table.font
                if (field is VariableTextField) field.refreshVariables()
            } finally {
                loading = false
            }
            return field
        }

        override fun getCellEditorValue(): Any = field.text
    }

    private fun globalBackground(): java.awt.Color = JBColor(0xF2F4F7, 0x2B2D30)

    private fun flatButton(text: String, tooltip: String): JButton = JButton(text).apply {
        this.toolTipText = tooltip
        isFocusPainted = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty(2, 8)
        font = font.deriveFont(Font.BOLD)
        preferredSize = Dimension(JBUI.scale(32), JBUI.scale(26))
    }

    private companion object {
        const val CHECK_COLUMN = 0
        const val KEY_COLUMN = 1
        const val VALUE_COLUMN = 2

        val CHECK_WIDTH = 34

        val COLUMN_WEIGHTS = listOf(3.0, 4.0)
        val COLUMN_MINIMUMS = listOf(96, 128)

        const val GLOBAL_LEGEND = "Rows in grey are global values from Global Settings and cannot be edited here."
    }
}
