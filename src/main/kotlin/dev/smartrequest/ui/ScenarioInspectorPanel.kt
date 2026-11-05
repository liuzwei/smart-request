package dev.smartrequest.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.extract.ResponseExtractorEngine
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioNode
import dev.smartrequest.model.SmartRequestState
import dev.smartrequest.scenario.ScenarioGraph
import dev.smartrequest.scenario.StatusExpectation
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Properties of the selected step: its request, the steps it runs after, the expected status, the
 * timeout and the values captured from its response.
 *
 * The panel mutates the scenario directly; [onChanged] only asks the editor to refresh the steps
 * table and the validation banner, so typing never rebuilds the field that has focus.
 */
class ScenarioInspectorPanel(
    private val allRequests: () -> List<HttpRequestConfig>,
    private val variableNamesProvider: (String) -> List<String>,
    private val onChanged: () -> Unit,
    private val onStructureChanged: () -> Unit,
    private val onMessage: (String?) -> Unit,
    private val onBeforeStructureChange: () -> Unit = {},
    private val onStructureChangeRejected: () -> Unit = {}
) : JBPanel<ScenarioInspectorPanel>(CardLayout()) {

    private var scenario: ScenarioConfig? = null
    private var node: ScenarioNode? = null
    private var binding = false
    private var dependencyBoxes: List<Pair<String, JCheckBox>> = emptyList()

    private val requestCombo = JComboBox<RequestChoice>().apply {
        maximumRowCount = 16
        preferredSize = Dimension(JBUI.scale(260), preferredSize.height)
    }
    private val expectField = JBTextField().apply { columns = 10 }
    private val timeoutField = JBTextField().apply { columns = 7 }
    private val expectHint = JBLabel(EXPECT_HINT).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = font.deriveFont(font.size2D - 1f)
    }
    private val timeoutHint = JBLabel(TIMEOUT_HINT).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = font.deriveFont(font.size2D - 1f)
    }
    private val dependenciesPanel = JPanel().apply {
        isOpaque = false
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
    }
    private val outputsPanel = ExtractorTablePanel(
        onChanged = {
            onChanged()
            setMessage(null)
        },
        variableNamesProvider = { node?.let { variableNamesProvider(it.id) }.orEmpty() },
        showRunButton = false,
        hintText = OUTPUTS_HINT,
        hintToolTip = "Captured values live in the run scope only; a scenario run never writes the environment."
    )
    private val messageLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyTop(4)
    }
    private val emptyLabel = JBLabel("Select a step to edit its request, dependencies and captures.").apply {
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = JLabel.CENTER
    }

    init {
        border = JBUI.Borders.empty(8, 10)
        add(emptyLabel, CARD_EMPTY)
        add(buildContent(), CARD_CONTENT)
        showCard(CARD_EMPTY)

        requestCombo.addActionListener {
            if (!binding) applySelectedRequest()
        }
        expectField.document.addDocumentListener(simpleListener { onExpectStatusEdited() })
        timeoutField.document.addDocumentListener(simpleListener { onTimeoutEdited() })
    }

    fun bind(scenario: ScenarioConfig?, nodeId: String?) {
        this.scenario = scenario
        val selected = if (scenario != null && nodeId != null) {
            ScenarioGraph.node(scenario, nodeId)?.takeIf { it.isRequestNode() }
        } else {
            null
        }
        node = selected
        if (selected == null) {
            showCard(CARD_EMPTY)
            return
        }
        showCard(CARD_CONTENT)
        binding = true
        try {
            bindRequest(selected)
            expectField.text = selected.expectStatus
            updateExpectHint(selected.expectStatus)
            timeoutField.text = selected.timeoutMs?.toString().orEmpty()
            updateTimeoutHint(selected.timeoutMs)
            rebuildDependencies(selected)
            outputsPanel.bind(selected.outputs)
        } finally {
            binding = false
        }
        setMessage(null)
    }

    fun refreshRequestChoices() {
        val current = node ?: return
        binding = true
        try {
            bindRequest(current)
        } finally {
            binding = false
        }
    }

    private fun bindRequest(node: ScenarioNode) {
        val requests = allRequests()
        val choices = requests.map { RequestChoice(it.id, "${it.method}  ${it.name}") }.toMutableList()
        if (node.requestId != null && choices.none { it.id == node.requestId }) {
            choices.add(RequestChoice(node.requestId, "(deleted request)"))
        }
        requestCombo.model = DefaultComboBoxModel(choices.toTypedArray())
        requestCombo.selectedItem = choices.firstOrNull { it.id == node.requestId } ?: choices.firstOrNull()
    }

    private fun applySelectedRequest() {
        val current = node ?: return
        val choice = requestCombo.selectedItem as? RequestChoice ?: return
        val requestId = choice.id ?: return
        if (requestId == current.requestId) return
        current.requestId = requestId
        val request = allRequests().firstOrNull { it.id == requestId }
        if (request != null && current.outputs.all { it.isBlank() }) {
            current.outputs.addAll(request.extractors.map { it.deepCopy() })
            outputsPanel.bind(current.outputs)
        }
        setMessage(if (request == null) "This request no longer exists; choose another one." else null, error = request == null)
        onChanged()
    }

    private fun onExpectStatusEdited() {
        if (binding) return
        val current = node ?: return
        current.expectStatus = expectField.text.trim()
        updateExpectHint(current.expectStatus)
        onChanged()
    }

    private fun updateExpectHint(expression: String) {
        val error = StatusExpectation.error(expression)
        if (error != null) {
            expectHint.text = error
            expectHint.foreground = JBColor.RED
        } else {
            expectHint.text = if (expression.isBlank()) {
                EXPECT_HINT
            } else {
                "Passes on ${StatusExpectation.describe(expression)}"
            }
            expectHint.foreground = UIUtil.getContextHelpForeground()
        }
    }

    private fun onTimeoutEdited() {
        if (binding) return
        val current = node ?: return
        val text = timeoutField.text.trim()
        if (text.isEmpty()) {
            current.timeoutMs = null
            updateTimeoutHint(null)
            onChanged()
            return
        }
        val value = text.toIntOrNull()
        if (value == null || value !in SmartRequestState.MIN_TIMEOUT_MS..SmartRequestState.MAX_TIMEOUT_MS) {
            timeoutHint.text = "Use ${SmartRequestState.MIN_TIMEOUT_MS}–${SmartRequestState.MAX_TIMEOUT_MS} ms"
            timeoutHint.foreground = JBColor.RED
            return
        }
        current.timeoutMs = value
        updateTimeoutHint(value)
        onChanged()
    }

    private fun updateTimeoutHint(value: Int?) {
        timeoutHint.text = if (value == null) TIMEOUT_HINT else "${value} ms for this step"
        timeoutHint.foreground = UIUtil.getContextHelpForeground()
    }

    private fun rebuildDependencies(node: ScenarioNode) {
        val scenario = scenario ?: return
        dependenciesPanel.removeAll()
        val others = ScenarioGraph.displayOrder(scenario).filter { it.id != node.id }
        val boxes = ArrayList<Pair<String, JCheckBox>>()
        if (others.isEmpty()) {
            dependenciesPanel.add(JBLabel("No other steps yet").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(2, 2)
            })
        }
        others.forEach { other ->
            val label = ScenarioGraph.label(
                other,
                other.requestId?.let { id -> allRequests().firstOrNull { it.id == id }?.name }
            )
            val box = JCheckBox("${requestMethod(other)}  $label".trim()).apply {
                isOpaque = false
                isSelected = ScenarioGraph.requiresDependency(scenario, node.id, other.id)
                border = JBUI.Borders.empty(1, 2)
                addActionListener {
                    if (binding) return@addActionListener
                    toggleDependency(other.id, isSelected)
                }
            }
            dependenciesPanel.add(box)
            boxes.add(other.id to box)
        }
        dependencyBoxes = boxes
        dependenciesPanel.revalidate()
        dependenciesPanel.repaint()
    }

    private fun toggleDependency(otherNodeId: String, enabled: Boolean) {
        val scenario = scenario ?: return
        val current = node ?: return
        onBeforeStructureChange()
        val result = if (enabled) {
            ScenarioGraph.addDependency(scenario, otherNodeId, current.id)
        } else {
            ScenarioGraph.removeDependency(scenario, otherNodeId, current.id)
        }
        if (result.ok) {
            setMessage(null)
            onStructureChanged()
        } else {
            onStructureChangeRejected()
            setMessage(result.error, error = true)
            // Put the checkbox back: the model did not change.
            binding = true
            try {
                rebuildDependencies(current)
            } finally {
                binding = false
            }
        }
    }

    private fun requestMethod(node: ScenarioNode): String =
        node.requestId?.let { id -> allRequests().firstOrNull { it.id == id }?.method }.orEmpty()

    private fun buildContent(): Component {
        val content = JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply { isOpaque = false }

        val form = JBPanel<JBPanel<*>>(GridBagLayout()).apply { isOpaque = false }
        val gbc = GridBagConstraints()
        gbc.insets = Insets(0, 0, JBUI.scale(4), JBUI.scale(8))
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.HORIZONTAL

        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        form.add(JBLabel("Request"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        form.add(requestCombo, gbc)

        gbc.gridx = 2
        gbc.weightx = 0.0
        gbc.insets = Insets(0, JBUI.scale(12), JBUI.scale(4), JBUI.scale(8))
        form.add(JBLabel("Expect status"), gbc)
        gbc.gridx = 3
        gbc.weightx = 0.35
        gbc.insets = Insets(0, 0, JBUI.scale(4), JBUI.scale(8))
        form.add(expectField, gbc)

        gbc.gridx = 4
        gbc.weightx = 0.0
        gbc.insets = Insets(0, JBUI.scale(12), JBUI.scale(4), JBUI.scale(8))
        form.add(JBLabel("Timeout"), gbc)
        gbc.gridx = 5
        gbc.weightx = 0.25
        gbc.insets = Insets(0, 0, JBUI.scale(4), 0)
        form.add(timeoutField, gbc)

        val hints = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(expectHint)
            add(JBLabel("·").apply { foreground = UIUtil.getContextHelpForeground() })
            add(timeoutHint)
        }
        val formWithHints = JBPanel<JBPanel<*>>(BorderLayout(0, 2)).apply {
            isOpaque = false
            add(form, BorderLayout.NORTH)
            add(hints, BorderLayout.CENTER)
        }

        content.add(formWithHints, BorderLayout.NORTH)

        val dependencies = JBPanel<JBPanel<*>>(BorderLayout(0, 2)).apply {
            isOpaque = false
            val title = JBLabel("Runs after").apply { font = font.deriveFont(Font.BOLD) }
            add(title, BorderLayout.NORTH)
            add(JBScrollPane(dependenciesPanel).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(JBUI.scale(220), JBUI.scale(140))
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }
        val outputs = JBPanel<JBPanel<*>>(BorderLayout(0, 2)).apply {
            isOpaque = false
            val header = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(JBLabel("Captures").apply { font = font.deriveFont(Font.BOLD) })
                add(JButton("Sync from request", AllIcons.Actions.Refresh).apply {
                    toolTipText = "Replace these rules with the Extract rules of the request"
                    isFocusPainted = false
                    addActionListener { syncFromRequest() }
                })
            }
            add(header, BorderLayout.NORTH)
            add(outputsPanel, BorderLayout.CENTER)
        }

        val split = OnePixelSplitter(false, 0.42f).apply {
            firstComponent = dependencies
            secondComponent = outputs
            dividerWidth = 1
        }
        content.add(split, BorderLayout.CENTER)
        content.add(messageLabel, BorderLayout.SOUTH)
        return content
    }

    private fun syncFromRequest() {
        val current = node ?: return
        val request = current.requestId?.let { id -> allRequests().firstOrNull { it.id == id } }
        if (request == null) {
            setMessage("Choose a request before syncing its Extract rules.", error = true)
            return
        }
        current.outputs.clear()
        current.outputs.addAll(request.extractors.map { it.deepCopy() })
        outputsPanel.bind(current.outputs)
        setMessage("Copied ${current.outputs.size} capture rule(s) from the request.")
        onChanged()
    }

    private fun setMessage(message: String?, error: Boolean = false) {
        messageLabel.text = message.orEmpty()
        messageLabel.foreground = if (error) JBColor.RED else UIUtil.getContextHelpForeground()
        onMessage(message)
    }

    private fun showCard(card: String) {
        (layout as CardLayout).show(this, card)
    }

    // Test hooks: drive the inspector the way a user would.

    internal fun boundNodeId(): String? = node?.id

    internal fun chooseRequest(requestId: String) {
        val index = (0 until requestCombo.itemCount).firstOrNull { i ->
            requestCombo.getItemAt(i).id == requestId
        } ?: return
        requestCombo.selectedIndex = index
        applySelectedRequest()
    }

    internal fun setExpectStatus(text: String) {
        expectField.text = text
        onExpectStatusEdited()
    }

    internal fun expectStatusText(): String = expectField.text

    internal fun setExpectFieldTextOnly(text: String) {
        expectField.text = text
    }

    internal fun expectHintText(): String = expectHint.text

    internal fun setTimeoutText(text: String) {
        timeoutField.text = text
        onTimeoutEdited()
    }

    internal fun timeoutText(): String = timeoutField.text

    internal fun timeoutHintText(): String = timeoutHint.text

    internal fun dependencyIds(): List<String> = dependencyBoxes.map { it.first }

    internal fun isDependencyChecked(nodeId: String): Boolean =
        dependencyBoxes.firstOrNull { it.first == nodeId }?.second?.isSelected == true

    internal fun toggleDependency(nodeId: String): Boolean {
        val box = dependencyBoxes.firstOrNull { it.first == nodeId }?.second ?: return false
        box.doClick()
        return true
    }

    internal fun dependencyLabel(nodeId: String): String? =
        dependencyBoxes.firstOrNull { it.first == nodeId }?.second?.text

    internal fun outputsPanel(): ExtractorTablePanel = outputsPanel

    internal fun syncOutputsFromRequest(): Boolean {
        val current = node ?: return false
        if (current.requestId == null) return false
        syncFromRequest()
        return true
    }

    internal fun message(): String? = messageLabel.text.takeIf { it.isNotEmpty() }

    private fun simpleListener(action: () -> Unit): DocumentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = action()
        override fun removeUpdate(e: DocumentEvent) = action()
        override fun changedUpdate(e: DocumentEvent) = action()
    }

    private class RequestChoice(val id: String?, private val label: String) {
        override fun toString(): String = label
    }

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_CONTENT = "content"

        private const val EXPECT_HINT = "Empty = 200–399 · 2xx · 404 · 200-299 · 200,201"
        private const val TIMEOUT_HINT = "Empty = project default"
        private const val OUTPUTS_HINT =
            "Captures are JSONPath rules (data.token, headers.X-Request-Id); downstream steps use {{name}}."
    }
}
