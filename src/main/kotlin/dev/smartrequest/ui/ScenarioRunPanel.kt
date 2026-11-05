package dev.smartrequest.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioNode
import dev.smartrequest.scenario.CapturedVariable
import dev.smartrequest.scenario.NodeRunState
import dev.smartrequest.scenario.ScenarioGraph
import dev.smartrequest.scenario.ScenarioNodeResult
import dev.smartrequest.scenario.ScenarioRunResult
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

/**
 * Live results of the current run: one row per request step plus the final run-scope variables.
 * The panel is fed by the runner events, so it shows progress while the flow is still executing.
 */
class ScenarioRunPanel(
    private val requestProvider: (String) -> HttpRequestConfig?,
    private val onCancel: () -> Unit,
    private val onSelectNode: (String) -> Unit = {}
) : JBPanel<ScenarioRunPanel>(BorderLayout()) {

    private var scenario: ScenarioConfig? = null
    private var rows: List<ScenarioNode> = emptyList()
    private var results: Map<String, ScenarioNodeResult> = emptyMap()
    private var variables: List<CapturedVariable> = emptyList()
    private var running = false
    private var startedAtMs = 0L

    private val summaryLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD)
    }
    private val failureLabel = JBLabel("").apply {
        foreground = JBColor.RED
        border = JBUI.Borders.emptyTop(2)
    }
    private val cancelButton = JButton("Cancel", AllIcons.Actions.Cancel).apply {
        isFocusPainted = false
        isVisible = false
        addActionListener { onCancel() }
    }
    private val copyButton = JButton("Copy values", AllIcons.Actions.Copy).apply {
        isFocusPainted = false
        isEnabled = false
        addActionListener { copyVariables() }
    }
    private val emptyLabel = JBLabel("Run the scenario to see per-step results and captured values.").apply {
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = JLabel.CENTER
    }

    private val model = ResultModel()
    private val table: JBTable = object : ProportionalTable(model) {
        override fun computeWidths(tableWidth: Int): List<Int> {
            if (tableWidth <= 0) return emptyList()
            return proportionalWidths(
                tableWidth,
                listOf(3.0, 1.05, 0.75, 0.85, 3.6),
                listOf(JBUI.scale(140), JBUI.scale(64), JBUI.scale(48), JBUI.scale(54), JBUI.scale(150))
            ).toList()
        }
    }
    private val variablesModel = object : DefaultTableModel(arrayOf("Name", "Value", "Captured by"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val variablesTable = JBTable(variablesModel).apply {
        rowHeight = JBUI.scale(24)
        setShowGrid(false)
        intercellSpacing = Dimension(JBUI.scale(8), 0)
        fillsViewportHeight = true
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val cards = JBPanel<JBPanel<*>>(CardLayout())

    init {
        border = JBUI.Borders.empty(8, 10)

        table.apply {
            rowHeight = JBUI.scale(26)
            setShowGrid(false)
            intercellSpacing = Dimension(JBUI.scale(8), 0)
            fillsViewportHeight = true
            columnModel.getColumn(STATUS_COLUMN).cellRenderer = StatusRenderer()
            columnModel.getColumn(RESULT_COLUMN).cellRenderer = ResultRenderer()
            setDefaultRenderer(String::class.java, TextRenderer())
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(event: java.awt.event.MouseEvent) {
                    if (!javax.swing.SwingUtilities.isLeftMouseButton(event)) return
                    val row = rowAtPoint(event.point)
                    rows.getOrNull(row)?.let { onSelectNode(it.id) }
                }
            })
        }

        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            val left = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(summaryLabel, BorderLayout.NORTH)
                add(failureLabel, BorderLayout.CENTER)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(copyButton)
                add(cancelButton)
            }
            add(left, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
            border = JBUI.Borders.emptyBottom(6)
        }

        val resultsCard = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(JBScrollPane(table).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }
        val variables = JBPanel<JBPanel<*>>(BorderLayout(0, 2)).apply {
            isOpaque = false
            add(JBLabel("Captured values (run scope, not saved)").apply {
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.NORTH)
            add(JBScrollPane(variablesTable).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }
        val split = OnePixelSplitter(true, 0.68f).apply {
            firstComponent = resultsCard
            secondComponent = variables
            dividerWidth = 1
        }
        cards.add(emptyLabel, CARD_EMPTY)
        cards.add(split, CARD_RESULTS)
        showCard(CARD_EMPTY)

        add(header, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
        updateSummary()
    }

    fun startRun(scenario: ScenarioConfig) {
        this.scenario = scenario
        rows = ScenarioGraph.displayOrder(scenario)
        results = rows.associate { node ->
            node.id to ScenarioNodeResult(
                nodeId = node.id,
                name = nodeLabel(node),
                state = NodeRunState.PENDING,
                method = requestProvider(node.requestId.orEmpty())?.method.orEmpty()
            )
        }
        variables = emptyList()
        running = true
        startedAtMs = System.currentTimeMillis()
        failureLabel.text = ""
        cancelButton.isVisible = true
        cancelButton.isEnabled = true
        copyButton.isEnabled = false
        variablesModel.rowCount = 0
        model.fireTableDataChanged()
        showCard(CARD_RESULTS)
        updateSummary()
    }

    fun setNodeState(nodeId: String, state: NodeRunState) {
        val current = results[nodeId] ?: return
        results = results + (nodeId to current.copy(state = state))
        model.fireTableDataChanged()
        updateSummary()
    }

    fun updateNode(result: ScenarioNodeResult) {
        if (results[result.nodeId] == null && result.state == NodeRunState.PASSED) {
            // Start/End events are not part of the request rows.
            return
        }
        results = results + (result.nodeId to result)
        model.fireTableDataChanged()
        updateSummary()
    }

    fun finish(result: ScenarioRunResult) {
        running = false
        cancelButton.isVisible = false
        // Keep the rows taken when the run started: the scenario may have been edited in the
        // meantime, and the results describe the snapshot, not the current graph.
        results = rows.associate { node ->
            node.id to (result.nodes[node.id] ?: ScenarioNodeResult(
                nodeId = node.id,
                name = nodeLabel(node),
                state = NodeRunState.SKIPPED
            ))
        }
        variables = result.variables
        model.fireTableDataChanged()
        failureLabel.text = result.failure.orEmpty()
        copyButton.isEnabled = variables.isNotEmpty()
        showVariables()
        updateSummary()
    }

    fun reset() {
        scenario = null
        rows = emptyList()
        results = emptyMap()
        variables = emptyList()
        running = false
        failureLabel.text = ""
        summaryLabel.text = ""
        cancelButton.isVisible = false
        copyButton.isEnabled = false
        variablesModel.rowCount = 0
        model.fireTableDataChanged()
        showCard(CARD_EMPTY)
    }

    fun cancelRequested() {
        cancelButton.isEnabled = false
        cancelButton.text = "Cancelling…"
    }

    private fun updateSummary() {
        if (running) {
            val done = results.values.count { it.state == NodeRunState.PASSED }
            summaryLabel.text = "Running… $done/${rows.size}"
            summaryLabel.foreground = UIUtil.getLabelForeground()
            return
        }
        if (rows.isEmpty()) {
            summaryLabel.text = ""
            return
        }
        val passed = results.values.count { it.state == NodeRunState.PASSED }
        val failed = results.values.count { it.state == NodeRunState.FAILED }
        val skipped = results.values.count { it.state == NodeRunState.SKIPPED }
        val cancelled = results.values.count { it.state == NodeRunState.CANCELLED }
        val duration = if (startedAtMs > 0) (System.currentTimeMillis() - startedAtMs) else 0
        val parts = mutableListOf("$passed passed")
        if (failed > 0) parts += "$failed failed"
        if (skipped > 0) parts += "$skipped skipped"
        if (cancelled > 0) parts += "$cancelled cancelled"
        summaryLabel.text = parts.joinToString(", ") + " · ${formatDuration(duration)}"
        summaryLabel.foreground = when {
            failed > 0 -> JBColor.RED
            cancelled > 0 -> UIUtil.getInactiveTextColor()
            else -> PASSED_COLOR
        }
        cancelButton.text = "Cancel"
    }

    private fun showVariables() {
        variablesModel.rowCount = 0
        variables.forEach { variable ->
            variablesModel.addRow(
                arrayOf(
                    variable.name,
                    variable.value,
                    producerLabel(variable.producerNodeId)
                )
            )
        }
    }

    private fun producerLabel(nodeId: String): String {
        val node = scenario?.nodes?.firstOrNull { it.id == nodeId } ?: return nodeId
        return nodeLabel(node)
    }

    private fun nodeLabel(node: ScenarioNode): String {
        val requestName = node.requestId?.let { id -> requestProvider(id)?.name }
        return ScenarioGraph.label(node, requestName)
    }

    private fun copyVariables() {
        if (variables.isEmpty()) return
        val text = variables.joinToString("\n") { "${it.name}=${it.value}" }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun showCard(card: String) {
        (cards.layout as CardLayout).show(cards, card)
    }

    private fun stateText(state: NodeRunState): String = when (state) {
        NodeRunState.PENDING -> "pending"
        NodeRunState.RUNNING -> "running"
        NodeRunState.PASSED -> "passed"
        NodeRunState.FAILED -> "failed"
        NodeRunState.SKIPPED -> "skipped"
        NodeRunState.CANCELLED -> "cancelled"
    }

    private fun formatDuration(ms: Long): String =
        if (ms < 1000) "$ms ms" else String.format("%.1f s", ms / 1000.0)

    private inner class ResultModel : AbstractTableModel() {
        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 5

        override fun getColumnName(column: Int): String = when (column) {
            NODE_COLUMN -> "Step"
            STATUS_COLUMN -> "Status"
            HTTP_COLUMN -> "HTTP"
            TIME_COLUMN -> "Time"
            else -> "Result"
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val node = rows.getOrNull(rowIndex) ?: return ""
            val result = results[node.id]
            return when (columnIndex) {
                NODE_COLUMN -> "${result?.method.orEmpty()}  ${nodeLabel(node)}".trim()
                STATUS_COLUMN -> stateText(result?.state ?: NodeRunState.PENDING)
                HTTP_COLUMN -> result?.statusCode?.takeIf { it > 0 }?.toString().orEmpty()
                TIME_COLUMN -> result?.durationMs?.takeIf { it > 0 }?.let { "${it} ms" }.orEmpty()
                else -> resultText(result)
            }
        }
    }

    private fun resultText(result: ScenarioNodeResult?): String {
        if (result == null) return ""
        val outputs = result.outputs.filter { it.successful }
        if (outputs.isNotEmpty()) {
            return outputs.joinToString(", ") { "${it.variable}=${it.value.orEmpty().take(48)}" }
        }
        return result.error.orEmpty()
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
            val state = node?.let { results[it.id]?.state }
            background = if (selected) table.selectionBackground else table.background
            font = table.font
            text = stateText(state ?: NodeRunState.PENDING)
            foreground = when {
                selected -> table.selectionForeground
                state == NodeRunState.PASSED -> PASSED_COLOR
                state == NodeRunState.FAILED -> FAILED_COLOR
                state == NodeRunState.RUNNING -> RUNNING_COLOR
                state == NodeRunState.SKIPPED || state == NodeRunState.CANCELLED -> UIUtil.getInactiveTextColor()
                else -> table.foreground
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
            val node = rows.getOrNull(row)
            val result = node?.let { results[it.id] }
            background = if (selected) table.selectionBackground else table.background
            font = table.font
            text = value?.toString().orEmpty()
            foreground = when {
                selected -> table.selectionForeground
                result?.state == NodeRunState.FAILED -> FAILED_COLOR
                result?.state == NodeRunState.SKIPPED || result?.state == NodeRunState.CANCELLED ->
                    UIUtil.getInactiveTextColor()

                else -> table.foreground
            }
            toolTipText = result?.error
            return this
        }
    }

    // Test hooks.

    internal fun summaryText(): String = summaryLabel.text

    internal fun failureText(): String = failureLabel.text

    internal fun statusFor(nodeId: String): String = stateText(results[nodeId]?.state ?: NodeRunState.PENDING)

    internal fun resultFor(nodeId: String): ScenarioNodeResult? = results[nodeId]

    internal fun variablesText(): String =
        (0 until variablesModel.rowCount).joinToString("\n") { row ->
            "${variablesModel.getValueAt(row, 0)}=${variablesModel.getValueAt(row, 1)}"
        }

    internal fun variableProducer(nodeId: String): String? {
        val row = (0 until variablesModel.rowCount).firstOrNull {
            variablesModel.getValueAt(it, 0) == nodeId
        } ?: return null
        return variablesModel.getValueAt(row, 2)?.toString()
    }

    internal fun rowCount(): Int = rows.size

    internal fun isCancelVisible(): Boolean = cancelButton.isVisible

    /** Simulates clicking a result row; used to verify selection sync with the graph. */
    internal fun clickNodeForTest(nodeId: String) {
        if (rows.any { it.id == nodeId }) onSelectNode(nodeId)
    }

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_RESULTS = "results"

        internal const val NODE_COLUMN = 0
        internal const val STATUS_COLUMN = 1
        internal const val HTTP_COLUMN = 2
        internal const val TIME_COLUMN = 3
        internal const val RESULT_COLUMN = 4

        private val PASSED_COLOR = JBColor(0x1A7F37, 0x73BD79)
        private val FAILED_COLOR = JBColor(0xC7222D, 0xFF6B68)
        private val RUNNING_COLOR = JBColor(0x0B5CAD, 0x6BA6E8)
    }
}
