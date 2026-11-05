package dev.smartrequest.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.extract.ExtractionReport
import dev.smartrequest.extract.ExtractionResult
import dev.smartrequest.extract.ResponseExtractorEngine
import dev.smartrequest.model.ResponseExtractor
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.AbstractCellEditor
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Editable table behind the Extract tab: one rule per row, plus the outcome of the last run.
 *
 * The source is written as a small path expression (`data.token`, `data.items[0].id`, `headers.X-Request-Id`).
 * The variable name is filled in from the last path segment while it is still empty, so the common
 * case only needs one column to be typed. Clicking the variable cell opens a list of the variables
 * that already exist in the active environment, so re-using one does not mean typing it again.
 */
class ExtractorTablePanel(
    private val onChanged: () -> Unit = {},
    private val onRun: () -> Unit = {},
    private val variableNamesProvider: () -> List<String> = { emptyList() },
    private val showRunButton: Boolean = true,
    hintText: String = HINT,
    hintToolTip: String = STORAGE_HINT,
    /**
     * Scenario nodes can mark a rule optional: a failed optional rule does not fail the step.
     * The single-request Extract tab keeps the compact four-column layout.
     */
    private val showRequired: Boolean = false
) : JBPanel<ExtractorTablePanel>(BorderLayout()) {

    private var target: MutableList<ResponseExtractor>? = null
    private var results: Map<String, ExtractionResult> = emptyMap()
    private val model = Model()

    private val requiredViewColumn: Int get() = if (showRequired) 1 else -1
    private val variableViewColumn: Int get() = if (showRequired) 2 else 1
    private val sourceViewColumn: Int get() = if (showRequired) 3 else 2
    private val resultViewColumn: Int get() = if (showRequired) 4 else 3

    private val addButton = flatButton("+", "Add extractor")
    private val removeButton = flatButton("−", "Remove selected extractor")
    private val runButton = flatButton("Run now", "Evaluate the rules against the last response")
    private val hintLabel = JBLabel(hintText).apply {
        foreground = UIUtil.getContextHelpForeground()
        toolTipText = hintToolTip
    }

    private val table: JBTable = object : ProportionalTable(model) {
        override fun computeWidths(tableWidth: Int): List<Int> {
            if (tableWidth <= 0) return emptyList()
            val checkWidth = JBUI.scale(CHECK_WIDTH)
            val requiredWidth = if (showRequired) JBUI.scale(REQUIRED_WIDTH) else 0
            val rest = proportionalWidths(
                tableWidth - checkWidth - requiredWidth,
                listOf(1.7, 2.7, 1.3),
                listOf(JBUI.scale(130), JBUI.scale(170), JBUI.scale(96))
            )
            return if (showRequired) {
                listOf(checkWidth, requiredWidth) + rest.toList()
            } else {
                listOf(checkWidth) + rest.toList()
            }
        }
    }

    init {
        border = JBUI.Borders.empty()

        addButton.addActionListener { addRow() }
        removeButton.addActionListener { removeSelectedRows() }
        runButton.addActionListener { commitPendingEdit(); onRun() }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(addButton)
            add(removeButton)
            if (showRunButton) add(runButton)
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
            if (showRequired) {
                columnModel.getColumn(requiredViewColumn).apply {
                    preferredWidth = JBUI.scale(REQUIRED_WIDTH)
                    minWidth = JBUI.scale(REQUIRED_WIDTH)
                    maxWidth = JBUI.scale(REQUIRED_WIDTH)
                    resizable = false
                }
            }
            setDefaultRenderer(java.lang.Boolean::class.javaObjectType, CheckRenderer())
            setDefaultRenderer(String::class.java, TextRenderer())
            columnModel.getColumn(resultViewColumn).cellRenderer = ResultRenderer()
            columnModel.getColumn(variableViewColumn).cellEditor = VariableCellEditor()
            selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) updateButtons() }
            installSingleClickEditing(
                onClick = { row, viewColumn ->
                    when {
                        viewColumn == CHECK_COLUMN -> toggleEnabled(row)
                        showRequired && viewColumn == requiredViewColumn -> toggleRequired(row)
                        else -> false
                    }
                },
                beginEdit = { row, viewColumn -> beginEdit(row, viewColumn) }
            )
        }

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(table).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(hintLabel.apply { border = JBUI.Borders.emptyTop(4) }, BorderLayout.SOUTH)
        updateButtons()
    }

    fun bind(target: MutableList<ResponseExtractor>) {
        this.target = target
        // Results are keyed by extractor id, so results of another request never leak into the
        // table: their ids simply do not match and the rows read as "Not run".
        model.rebuild()
        updateButtons()
    }

    /** Shows the outcome of the last run. Passing null clears the result column. */
    fun setResults(report: ExtractionReport?) {
        results = report?.results.orEmpty().associateBy { it.extractorId }
        table.repaint()
    }

    fun setEnabledState(enabled: Boolean) {
        table.isEnabled = enabled
        addButton.isEnabled = enabled
        removeButton.isEnabled = enabled && table.selectedRows.isNotEmpty()
        runButton.isEnabled = enabled
        hintLabel.isEnabled = enabled
    }

    fun commitPendingEdit() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
    }

    internal fun targetSizeForTest(): Int = target?.size ?: 0

    fun addRow() {
        val target = target ?: return
        val extractor = ResponseExtractor()
        target.add(extractor)
        model.rebuild()
        val row = target.lastIndex
        if (row >= 0) {
            table.setRowSelectionInterval(row, row)
            table.scrollRowIntoView(row)
            beginEdit(row, sourceViewColumn)
        }
        updateButtons()
        onChanged()
    }

    fun removeSelectedRows() {
        val target = target ?: return
        val selected = table.selectedRows.distinct().sortedDescending()
        if (selected.isEmpty()) return
        selected.forEach { index ->
            if (index in target.indices) target.removeAt(index)
        }
        model.rebuild()
        updateButtons()
        onChanged()
    }

    // Test hooks: drive the table the way a user would, without dispatching input events.

    internal fun tableView(): JBTable = table

    internal fun setRowSource(row: Int, source: String) {
        model.setValueAt(source, row, sourceViewColumn)
    }

    internal fun setRowVariable(row: Int, variable: String) {
        model.setValueAt(variable, row, variableViewColumn)
    }

    internal fun beginEdit(row: Int, viewColumn: Int): Boolean {
        if (viewColumn == CHECK_COLUMN || viewColumn == resultViewColumn) return false
        if (showRequired && viewColumn == requiredViewColumn) return false
        if (!table.isEnabled) return false
        val modelColumn = table.convertColumnIndexToModel(viewColumn)
        if (!model.isCellEditable(row, modelColumn)) return false
        if (table.isEditing && table.editingRow == row && table.editingColumn == viewColumn) return true
        if (!table.editCellAt(row, viewColumn)) return false
        focusCellEditor(table)
        return true
    }

    internal fun toggleEnabled(row: Int): Boolean {
        val extractor = model.rowAt(row) ?: return false
        model.setValueAt(!extractor.enabled, row, CHECK_COLUMN)
        return true
    }

    internal fun toggleRequired(row: Int): Boolean {
        if (!showRequired) return false
        val extractor = model.rowAt(row) ?: return false
        model.setValueAt(!extractor.required, row, requiredViewColumn)
        return true
    }

    internal fun requiredColumnForTest(): Int = requiredViewColumn

    internal fun requiredForTest(row: Int): Boolean? = model.rowAt(row)?.required

    internal fun editingCell(): Pair<Int, Int>? =
        if (table.isEditing) table.editingRow to table.editingColumn else null

    /** The text field of the open variable editor, or null when it is not the edited cell. */
    internal fun variableEditorField(): JTextField? =
        (table.cellEditor as? VariableCellEditor)?.takeIf { table.isEditing }?.textField()

    /** Applies [name] to [row] the way choosing it from the variable list does. */
    internal fun chooseVariable(row: Int, name: String) {
        if (!beginEdit(row, variableViewColumn)) return
        (table.cellEditor as? VariableCellEditor)?.choose(name)
    }

    private fun updateButtons() {
        removeButton.isEnabled = table.isEnabled && table.selectedRows.isNotEmpty()
        addButton.isEnabled = target != null
    }

    private inner class Model : AbstractTableModel() {

        fun rowAt(rowIndex: Int): ResponseExtractor? = target?.getOrNull(rowIndex)

        fun rebuild() {
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = target?.size ?: 0

        override fun getColumnCount(): Int = if (showRequired) 5 else 4

        override fun getColumnName(column: Int): String = when {
            column == CHECK_COLUMN -> ""
            showRequired && column == requiredViewColumn -> "Required"
            column == variableViewColumn -> "Variable"
            column == sourceViewColumn -> "Source"
            else -> "Last run"
        }

        override fun getColumnClass(column: Int): Class<*> = when {
            column == CHECK_COLUMN -> java.lang.Boolean::class.javaObjectType
            showRequired && column == requiredViewColumn -> java.lang.Boolean::class.javaObjectType
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
            columnIndex == variableViewColumn || columnIndex == sourceViewColumn

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val extractor = rowAt(rowIndex) ?: return ""
            return when (columnIndex) {
                CHECK_COLUMN -> extractor.enabled
                variableViewColumn -> extractor.variable
                sourceViewColumn -> extractor.source
                else -> ""
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val extractor = rowAt(rowIndex) ?: return
            when {
                columnIndex == CHECK_COLUMN -> extractor.enabled = value == true
                showRequired && columnIndex == requiredViewColumn -> extractor.required = value == true
                columnIndex == variableViewColumn ->
                    extractor.variable = normalizeVariableInput(value?.toString().orEmpty())
                columnIndex == sourceViewColumn -> {
                    extractor.source = value?.toString()?.trim().orEmpty()
                    // While the name is still empty, follow the path so a path alone is enough.
                    if (extractor.variable.isBlank()) {
                        extractor.variable = ResponseExtractorEngine.deriveVariableName(extractor.source)
                    }
                }
            }
            fireTableRowsUpdated(rowIndex, rowIndex)
            onChanged()
        }
    }

    /**
     * Cell editor for the variable column: a text field plus a small chooser that lists the
     * variables already defined in the active environment. The chooser opens as soon as the cell is
     * edited, so reusing `x-code` is one click instead of retyping it; typing dismisses the list and
     * creates a new name as before.
     */
    private inner class VariableCellEditor : AbstractCellEditor(), TableCellEditor {

        private val field = JBTextField()
        private val component = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(field, BorderLayout.CENTER)
            add(
                JButton("▾").apply {
                    toolTipText = "Choose a variable from the active environment"
                    isFocusPainted = false
                    isContentAreaFilled = false
                    margin = Insets(0, 0, 0, 0)
                    border = JBUI.Borders.empty(0, 2)
                    preferredSize = Dimension(JBUI.scale(20), JBUI.scale(20))
                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    addActionListener { showChooser() }
                },
                BorderLayout.EAST
            )
        }
        private var popup: JBPopup? = null
        private var editRow = -1
        private var loading = false

        init {
            field.border = BorderFactory.createEmptyBorder(0, JBUI.scale(6), 0, JBUI.scale(6))
            field.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = changed()
                override fun removeUpdate(e: DocumentEvent) = changed()
                override fun changedUpdate(e: DocumentEvent) = changed()

                private fun changed() {
                    if (loading || editRow < 0) return
                    // Write through so the value survives when the editor is closed by something
                    // else, and get out of the way as soon as a new name is typed.
                    hideChooser()
                    model.setValueAt(field.text, editRow, VARIABLE_COLUMN)
                }
            })
            field.addActionListener {
                if (!loading) stopCellEditing()
            }
            field.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when {
                        e.keyCode == KeyEvent.VK_ESCAPE -> cancelCellEditing()
                        e.keyCode == KeyEvent.VK_SPACE && e.isControlDown -> {
                            e.consume()
                            showChooser()
                        }
                        e.keyCode == KeyEvent.VK_DOWN && e.isAltDown -> {
                            e.consume()
                            showChooser()
                        }
                    }
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
            loading = true
            try {
                field.text = value?.toString().orEmpty()
                field.font = table.font
            } finally {
                loading = false
            }
            SwingUtilities.invokeLater {
                if (!component.isShowing) return@invokeLater
                field.requestFocusInWindow()
                field.selectAll()
                showChooser()
            }
            return component
        }

        override fun getCellEditorValue(): Any = normalizeVariableInput(field.text)

        override fun cancelCellEditing() {
            hideChooser()
            super.cancelCellEditing()
        }

        override fun stopCellEditing(): Boolean {
            hideChooser()
            return super.stopCellEditing()
        }

        /** Applies [name] exactly like choosing it in the list would. */
        fun choose(name: String) {
            // Called from the chooser callback, which closes the popup itself; dropping the reference
            // first avoids cancelling it while it is still dispatching the callback.
            popup = null
            field.text = name
            model.setValueAt(name, editRow, VARIABLE_COLUMN)
            stopCellEditing()
        }

        fun textField(): JTextField = field

        private fun showChooser() {
            if (popup?.isVisible == true) return
            val names = variableNamesProvider().distinct().sorted()
            if (names.isEmpty() || !field.isShowing) return
            val created = JBPopupFactory.getInstance()
                .createPopupChooserBuilder(names)
                .setTitle("Environment variables")
                .setMovable(false)
                .setResizable(false)
                .setRequestFocus(false)
                .setItemChosenCallback { name -> choose(name) }
                .createPopup()
            popup = created
            created.showUnderneathOf(field)
        }

        private fun hideChooser() {
            popup?.cancel()
            popup = null
        }
    }

    private inner class CheckRenderer : JCheckBox(), TableCellRenderer {
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
            isSelected = value == true
            isEnabled = table.isEnabled
            background = if (selected) table.selectionBackground else table.background
            return this
        }
    }

    private inner class TextRenderer : JLabel(), TableCellRenderer {
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
            val raw = value?.toString().orEmpty()
            background = if (selected) table.selectionBackground else table.background
            foreground = if (selected) table.selectionForeground else table.foreground
            font = if (column == SOURCE_COLUMN) {
                Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
            } else {
                table.font
            }
            text = raw
            toolTipText = when {
                raw.isEmpty() && column == SOURCE_COLUMN -> "Example: data.token, data.items[0].id, headers.X-Request-Id"
                raw.isEmpty() -> null
                else -> raw
            }
            return this
        }
    }

    private inner class ResultRenderer : JLabel(), TableCellRenderer {
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
            val extractor = model.rowAt(row)
            val result = extractor?.let { results[it.id] }
            background = if (selected) table.selectionBackground else table.background
            font = table.font
            if (extractor == null) {
                text = ""
                toolTipText = null
                return this
            }
            if (result == null) {
                text = when {
                    extractor.source.isBlank() && extractor.variable.isBlank() -> ""
                    extractor.variable.isBlank() -> "Name is empty"
                    extractor.source.isBlank() -> "Source is empty"
                    else -> "Not run"
                }
                foreground = if (selected) table.selectionForeground else UIUtil.getInactiveTextColor()
                toolTipText = if (text.isEmpty()) null else "Run the request or click \"Run now\" to evaluate this rule."
                return this
            }
            text = when {
                !result.successful && !extractor.required -> "Optional failed"
                !result.successful -> result.error ?: "Failed"
                result.added -> "Added"
                result.reEnabled -> "Updated + enabled"
                else -> "Updated"
            }
            foreground = when {
                selected -> table.selectionForeground
                result.successful -> SUCCESS_COLOR
                !extractor.required -> OPTIONAL_COLOR
                else -> ERROR_COLOR
            }
            toolTipText = if (result.successful) {
                buildString {
                    append(result.value.orEmpty())
                    result.previous?.takeIf { it != result.value }?.let { append("\nwas: ").append(it) }
                }
            } else {
                result.error
            }
            return this
        }
    }

    private fun flatButton(text: String, tooltip: String): JButton = JButton(text).apply {
        this.toolTipText = tooltip
        isFocusPainted = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty(2, 8)
        font = font.deriveFont(Font.BOLD)
        preferredSize = Dimension(JBUI.scale(if (text.length > 2) 76 else 32), JBUI.scale(26))
    }

    internal companion object {
        const val CHECK_COLUMN = 0
        const val VARIABLE_COLUMN = 1
        const val SOURCE_COLUMN = 2
        const val RESULT_COLUMN = 3

        private const val CHECK_WIDTH = 34
        private const val REQUIRED_WIDTH = 70

        private const val HINT =
            "Sources: data.token · data.items[0].id · headers.X-Request-Id · status"

        private const val STORAGE_HINT =
            "Values are saved to the active environment in .idea/smart-request.xml as plain text."

        private val SUCCESS_COLOR = JBColor(0x1A7F37, 0x73BD79)
        private val ERROR_COLOR = JBColor(0xC7222D, 0xFF6B68)
        private val OPTIONAL_COLOR = JBColor(0xA15C00, 0xD6A26A)

        /**
         * Accepts the `{{name}}` form as well as a bare name: typing the braces is a natural
         * mistake, and they can never be part of a variable name.
         */
        internal fun normalizeVariableInput(raw: String): String {
            var text = raw.trim()
            while (text.startsWith("{{") && text.endsWith("}}") && text.length > 4) {
                text = text.substring(2, text.length - 2).trim()
            }
            return text
        }
    }
}
