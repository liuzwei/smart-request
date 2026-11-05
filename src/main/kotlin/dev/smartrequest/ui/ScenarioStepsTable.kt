package dev.smartrequest.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioNode
import dev.smartrequest.scenario.NodeRunState
import dev.smartrequest.scenario.ScenarioGraph
import dev.smartrequest.scenario.ScenarioNodeResult
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

/**
 * The steps view: one row per request node in execution order, with its dependencies and the outcome
 * of the current (or last) run. Start and End are implied by the dependencies and are not rows, so
 * the table stays focused on what the user actually edits.
 */
class ScenarioStepsTable(
    private val requestProvider: (String) -> HttpRequestConfig?,
    private val onSelectionChanged: (String?) -> Unit,
    private val onAddStep: () -> Unit,
    private val onRemoveStep: (String) -> Unit,
    private val onOpenRequest: (String) -> Unit
) : JBPanel<ScenarioStepsTable>(BorderLayout()) {

    private var scenario: ScenarioConfig? = null
    private var rows: List<ScenarioNode> = emptyList()
    private var results: Map<String, ScenarioNodeResult> = emptyMap()
    private var states: Map<String, NodeRunState> = emptyMap()
    private var binding = false

    private val addButton = JButton("+ Add step", AllIcons.General.Add)
    private val removeButton = JButton("Remove", AllIcons.General.Remove)
    private val hint = JBLabel(HINT).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyTop(4)
    }

    private val model = Model()
    private val table: JBTable = object : ProportionalTable(model) {
        override fun computeWidths(tableWidth: Int): List<Int> {
            if (tableWidth <= 0) return emptyList()
            return proportionalWidths(
                tableWidth,
                listOf(0.35, 3.0, 2.1, 1.05, 0.75, 0.9, 3.2),
                listOf(
                    JBUI.scale(34),
                    JBUI.scale(150),
                    JBUI.scale(110),
                    JBUI.scale(62),
                    JBUI.scale(48),
                    JBUI.scale(52),
                    JBUI.scale(150)
                )
            ).toList()
        }
    }

    init {
        addButton.addActionListener { onAddStep() }
        removeButton.addActionListener { removeSelected() }
        removeButton.isEnabled = false

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(addButton)
            add(removeButton)
        }
        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            add(toolbar, BorderLayout.WEST)
        }

        table.apply {
            rowHeight = JBUI.scale(26)
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            setShowGrid(false)
            intercellSpacing = Dimension(JBUI.scale(8), 0)
            fillsViewportHeight = true
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            columnModel.getColumn(STEP_COLUMN).apply {
                minWidth = JBUI.scale(34)
                maxWidth = JBUI.scale(34)
                resizable = false
            }
            columnModel.getColumn(STATUS_COLUMN).cellRenderer = StatusRenderer()
            columnModel.getColumn(CAPTURED_COLUMN).cellRenderer = CapturedRenderer()
            columnModel.getColumn(NODE_COLUMN).cellRenderer = NodeRenderer()
            columnModel.getColumn(DEPENDS_COLUMN).cellRenderer = DependsRenderer()
            setDefaultRenderer(String::class.java, TextRenderer())
            selectionModel.addListSelectionListener {
                if (binding || it.valueIsAdjusting) return@addListSelectionListener
                removeButton.isEnabled = table.selectedRow >= 0
                onSelectionChanged(selectedNodeId())
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event)) {
                        val row = table.rowAtPoint(event.point)
                        val node = rows.getOrNull(row) ?: return
                        val requestId = node.requestId ?: return
                        onOpenRequest(requestId)
                    }
                }
            })
            installDeleteShortcut()
        }

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(table).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(hint, BorderLayout.SOUTH)
    }

    fun bind(
        scenario: ScenarioConfig?,
        results: Map<String, ScenarioNodeResult> = emptyMap(),
        keepRunState: Boolean = false
    ) {
        val previousSelection = selectedNodeId()
        this.scenario = scenario
        this.rows = scenario?.let { ScenarioGraph.displayOrder(it) }.orEmpty()
        if (!keepRunState) {
            this.results = results
            this.states = results.mapValues { it.value.state }
        } else {
            this.results = this.results.filterKeys { id -> rows.any { it.id == id } }
            this.states = this.states.filterKeys { id -> rows.any { it.id == id } }
        }
        binding = true
        try {
            model.fireTableDataChanged()
            val target = previousSelection?.takeIf { id -> rows.any { it.id == id } } ?: rows.firstOrNull()?.id
            if (target != null) {
                selectNode(target)
            } else {
                table.clearSelection()
                removeButton.isEnabled = false
                onSelectionChanged(null)
            }
        } finally {
            binding = false
        }
    }

    fun refreshNames() {
        refreshRows()
    }

    fun setResults(results: Map<String, ScenarioNodeResult>) {
        this.results = results
        this.states = results.mapValues { it.value.state }
        refreshRows()
    }

    fun clearResults() {
        results = emptyMap()
        states = emptyMap()
        refreshRows()
    }

    fun setNodeState(nodeId: String, state: NodeRunState) {
        states = states + (nodeId to state)
        val result = results[nodeId]
        if (result != null) {
            results = results + (nodeId to result.copy(state = state))
        }
        refreshRows()
    }

    fun updateNodeResult(result: ScenarioNodeResult) {
        results = results + (result.nodeId to result)
        states = states + (result.nodeId to result.state)
        refreshRows()
    }

    /**
     * Redraws the rows without losing the selection. A plain [AbstractTableModel.fireTableDataChanged]
     * resets the JTable selection, which would clear the inspector while the user is editing a step
     * or while run events arrive.
     */
    private fun refreshRows() {
        val selectedId = selectedNodeId()
        binding = true
        try {
            model.fireTableDataChanged()
            val index = rows.indexOfFirst { it.id == selectedId }
            if (index >= 0) {
                table.setRowSelectionInterval(index, index)
            }
        } finally {
            binding = false
        }
        removeButton.isEnabled = table.selectedRow >= 0
    }

    fun selectNode(nodeId: String): Boolean {
        val index = rows.indexOfFirst { it.id == nodeId }
        if (index < 0) return false
        binding = true
        try {
            table.setRowSelectionInterval(index, index)
            table.scrollRectToVisible(table.getCellRect(index, 0, true))
        } finally {
            binding = false
        }
        removeButton.isEnabled = true
        onSelectionChanged(nodeId)
        return true
    }

    fun selectedNodeId(): String? {
        val row = table.selectedRow
        return rows.getOrNull(row)?.id
    }

    private fun removeSelected() {
        val nodeId = selectedNodeId() ?: return
        onRemoveStep(nodeId)
    }

    private fun installDeleteShortcut() {
        table.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "smartRequest.removeStep")
        table.actionMap.put("smartRequest.removeStep", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = removeSelected()
        })
    }

    private fun requestName(requestId: String?): String? =
        requestId?.let { requestProvider(it)?.name }

    private fun requestMethod(requestId: String?): String? =
        requestId?.let { requestProvider(it)?.method }

    private fun nodeLabel(node: ScenarioNode): String =
        ScenarioGraph.label(node, requestName(node.requestId))

    private fun captured(node: ScenarioNode): String {
        val result = results[node.id]
        val outputs = result?.outputs.orEmpty().filter { it.successful }
        if (outputs.isNotEmpty()) {
            return outputs.joinToString(", ") { value ->
                "${value.variable}=${value.value.orEmpty().take(MAX_VALUE_LENGTH)}"
            }
        }
        if (result != null && result.state == NodeRunState.PASSED) return "—"
        return node.outputs
            .filter { it.enabled && !it.isBlank() }
            .joinToString(", ") { rule ->
                val name = dev.smartrequest.extract.ResponseExtractorEngine.resolveVariableName(
                    rule.variable,
                    rule.source
                )
                "$name ← ${rule.source}"
            }
    }

    private fun capturedToolTip(node: ScenarioNode): String? {
        val result = results[node.id]
        if (result != null && result.state != NodeRunState.PASSED) {
            return result.error ?: result.state.name.lowercase()
        }
        val outputs = result?.outputs.orEmpty()
        if (outputs.isEmpty()) return null
        return outputs.joinToString("\n") { value ->
            if (value.successful) {
                "${value.variable} = ${value.value}"
            } else {
                "${value.variable}: ${value.error}"
            }
        }
    }

    // Test hooks: read the table without relying on rendering.

    internal fun rowCount(): Int = rows.size

    internal fun nodeIdAt(row: Int): String? = rows.getOrNull(row)?.id

    internal fun requestTextAt(row: Int): String {
        val node = rows.getOrNull(row) ?: return ""
        return "${requestMethod(node.requestId).orEmpty()}  ${nodeLabel(node)}".trim()
    }

    internal fun dependsTextAt(row: Int): String {
        val node = rows.getOrNull(row) ?: return ""
        return dependsText(node)
    }

    internal fun statusTextFor(nodeId: String): String = stateText(states[nodeId])

    internal fun capturedTextFor(nodeId: String): String {
        val node = rows.firstOrNull { it.id == nodeId } ?: return ""
        return captured(node)
    }

    internal fun tableView(): JBTable = table

    internal fun addStepButton(): JButton = addButton

    internal fun removeStepButton(): JButton = removeButton

    private fun dependsText(node: ScenarioNode): String {
        val scenario = scenario ?: return ""
        val labels = ScenarioGraph.requestPredecessors(scenario, node.id).mapNotNull { id ->
            scenario.nodes.firstOrNull { it.id == id }?.let { predecessor ->
                nodeLabel(predecessor)
            }
        }
        return labels.ifEmpty { listOf("Start") }.joinToString(", ")
    }

    private fun stateText(state: NodeRunState?): String = when (state) {
        NodeRunState.PENDING -> "pending"
        NodeRunState.RUNNING -> "running"
        NodeRunState.PASSED -> "passed"
        NodeRunState.FAILED -> "failed"
        NodeRunState.SKIPPED -> "skipped"
        NodeRunState.CANCELLED -> "cancelled"
        null -> ""
    }

    private inner class Model : AbstractTableModel() {
        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 7

        override fun getColumnName(column: Int): String = when (column) {
            STEP_COLUMN -> "#"
            NODE_COLUMN -> "Step"
            DEPENDS_COLUMN -> "Runs after"
            STATUS_COLUMN -> "Status"
            HTTP_COLUMN -> "HTTP"
            TIME_COLUMN -> "Time"
            else -> "Captured"
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val node = rows.getOrNull(rowIndex) ?: return ""
            val result = results[node.id]
            return when (columnIndex) {
                STEP_COLUMN -> (rowIndex + 1).toString()
                NODE_COLUMN -> nodeLabel(node)
                DEPENDS_COLUMN -> dependsText(node)
                STATUS_COLUMN -> stateText(states[node.id])
                HTTP_COLUMN -> result?.statusCode?.takeIf { it > 0 }?.toString().orEmpty()
                TIME_COLUMN -> result?.durationMs?.takeIf { it > 0 }?.let { "${it} ms" }.orEmpty()
                else -> captured(node)
            }
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
            background = if (selected) table.selectionBackground else table.background
            foreground = if (selected) table.selectionForeground else table.foreground
            font = table.font
            text = value?.toString().orEmpty()
            toolTipText = null
            return this
        }
    }

    private inner class NodeRenderer : JLabel(), TableCellRenderer {
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
            val node = rows.getOrNull(row)
            background = if (selected) table.selectionBackground else table.background
            font = table.font
            if (node == null) {
                text = ""
                return this
            }
            val method = requestMethod(node.requestId).orEmpty()
            val color = if (selected) table.selectionForeground else HttpMethodColors.of(method)
            val rgb = String.format("%06x", color.rgb and 0xFFFFFF)
            text = if (method.isEmpty()) {
                escapeHtml(nodeLabel(node))
            } else {
                "<html><b><font color=#$rgb>${escapeHtml(method)}</font></b>  " +
                        "${escapeHtml(nodeLabel(node))}</html>"
            }
            toolTipText = node.requestId?.let { requestProvider(it)?.url }
            return this
        }
    }

    private inner class DependsRenderer : JLabel(), TableCellRenderer {
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
            background = if (selected) table.selectionBackground else table.background
            foreground = if (selected) table.selectionForeground else table.foreground
            font = table.font
            text = value?.toString().orEmpty()
            toolTipText = if (text == "Start") "Runs first, together with the other steps that follow Start" else text
            return this
        }
    }

    private inner class StatusRenderer : JLabel(), TableCellRenderer {
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
            val node = rows.getOrNull(row)
            val state = node?.let { states[it.id] }
            val result = node?.let { results[it.id] }
            background = if (selected) table.selectionBackground else table.background
            font = table.font
            text = stateText(state)
            foreground = when {
                selected -> table.selectionForeground
                state == NodeRunState.PASSED -> PASSED_COLOR
                state == NodeRunState.FAILED -> FAILED_COLOR
                state == NodeRunState.RUNNING -> RUNNING_COLOR
                state == NodeRunState.SKIPPED || state == NodeRunState.CANCELLED -> UIUtil.getInactiveTextColor()
                else -> table.foreground
            }
            toolTipText = when {
                state == NodeRunState.FAILED -> result?.error
                state == NodeRunState.SKIPPED -> "Skipped because the run stopped"
                state == NodeRunState.CANCELLED -> "Cancelled"
                state == NodeRunState.RUNNING -> "Request in flight"
                else -> null
            }
            return this
        }
    }

    private inner class CapturedRenderer : JLabel(), TableCellRenderer {
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
            val node = rows.getOrNull(row)
            background = if (selected) table.selectionBackground else table.background
            font = table.font
            text = value?.toString().orEmpty()
            foreground = when {
                selected -> table.selectionForeground
                node != null && results[node.id]?.state == NodeRunState.FAILED -> FAILED_COLOR
                text.contains(" ← ") -> UIUtil.getInactiveTextColor()
                else -> table.foreground
            }
            toolTipText = node?.let { capturedToolTip(it) } ?: text
            return this
        }
    }

    companion object {
        internal const val STEP_COLUMN = 0
        internal const val NODE_COLUMN = 1
        internal const val DEPENDS_COLUMN = 2
        internal const val STATUS_COLUMN = 3
        internal const val HTTP_COLUMN = 4
        internal const val TIME_COLUMN = 5
        internal const val CAPTURED_COLUMN = 6

        private const val MAX_VALUE_LENGTH = 48

        private const val HINT =
            "A step runs when every step checked in Runs after has passed. Start and End are automatic."

        private val PASSED_COLOR = JBColor(0x1A7F37, 0x73BD79)
        private val FAILED_COLOR = JBColor(0xC7222D, 0xFF6B68)
        private val RUNNING_COLOR = JBColor(0x0B5CAD, 0x6BA6E8)

        private fun escapeHtml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
