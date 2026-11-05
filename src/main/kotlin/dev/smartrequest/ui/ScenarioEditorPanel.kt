package dev.smartrequest.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.http.HttpTransport
import dev.smartrequest.http.VariableResolver
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.scenario.NodeRunState
import dev.smartrequest.scenario.RunEnvironment
import dev.smartrequest.scenario.ScenarioGraph
import dev.smartrequest.scenario.ScenarioHistory
import dev.smartrequest.scenario.ScenarioRunEvent
import dev.smartrequest.scenario.ScenarioRunHandle
import dev.smartrequest.scenario.ScenarioRunResult
import dev.smartrequest.scenario.ScenarioRunner
import dev.smartrequest.scenario.ScenarioTransport
import dev.smartrequest.scenario.ScenarioValidator
import dev.smartrequest.service.SmartRequestProjectService
import dev.smartrequest.ui.dialogs.RequestPickerDialog
import dev.smartrequest.ui.dialogs.ScenarioRunDialog
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.http.HttpClient
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JToggleButton
import javax.swing.KeyStroke

/**
 * Editor tab of one scenario: header with name/environment/run, the steps table, the selected step's
 * inspector and the run panel.
 *
 * A run edits nothing in the project: the runner works on a snapshot and captured values stay in the
 * run scope. The panel only forwards user intent; validation is always recomputed from the model.
 */
class ScenarioEditorPanel(
    private val project: Project,
    private val scenarioId: String
) : JBPanel<ScenarioEditorPanel>(BorderLayout()), Disposable {

    private val service = project.service<SmartRequestProjectService>()
    private var scenario: ScenarioConfig? = service.scenario(scenarioId)
    private var disposed = false
    private var refreshingHeader = false
    private var mutating = false

    private var activeRun: ScenarioRunHandle? = null
    private var runClient: HttpClient? = null
    private var lastRun: ScenarioRunResult? = null
    private var validationReport = ScenarioValidator.Report()

    private val history = ScenarioHistory()
    private var currentNodeId: String? = null
    private var syncingSelection = false
    private var showingGraph = false
    private var graphEverShown = false

    /** Test seam: when set, a run sends through this transport instead of real HTTP. */
    internal var testTransport: ScenarioTransport? = null

    private val nameField = JBTextField().apply {
        columns = 22
        font = font.deriveFont(Font.BOLD, font.size2D + 1f)
        toolTipText = "Scenario name"
    }
    private val environmentCombo = JComboBox<EnvironmentConfig>().apply {
        preferredSize = Dimension(JBUI.scale(140), preferredSize.height)
        maximumRowCount = 12
    }
    private val runButton = JButton("Run", AllIcons.Actions.Execute).apply {
        toolTipText = "Run this scenario (validated first)"
    }
    private val cancelButton = JButton("Cancel", AllIcons.Actions.Cancel).apply {
        isVisible = false
        toolTipText = "Stop the run; in-flight steps are cancelled"
    }
    private val validationLabel = JBLabel().apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.emptyRight(8)
    }

    private val stepsTable = ScenarioStepsTable(
        requestProvider = service::findRequest,
        onSelectionChanged = { nodeId -> onStepsSelection(nodeId) },
        onAddStep = { addStep() },
        onRemoveStep = { removeStep(it) },
        onOpenRequest = { openRequest(it) }
    )
    private val inspector = ScenarioInspectorPanel(
        allRequests = { allRequests() },
        variableNamesProvider = { nodeId -> captureSuggestions(nodeId) },
        onChanged = { modelChanged() },
        onStructureChanged = { structureChanged() },
        onMessage = { /* the inspector shows its own message label */ },
        onBeforeStructureChange = { recordHistory() },
        onStructureChangeRejected = { discardLastHistory() }
    )
    private val runPanel = ScenarioRunPanel(
        requestProvider = service::findRequest,
        onCancel = { cancelRun() },
        onSelectNode = { nodeId -> selectFromRunPanel(nodeId) }
    )
    private val responsePanel = ResponsePanel(project)

    private val stepsToggle = JToggleButton("Steps", true).apply {
        isFocusPainted = false
        margin = JBUI.insets(2, 10)
        toolTipText = "Edit the flow as a table"
    }
    private val graphToggle = JToggleButton("Graph", false).apply {
        isFocusPainted = false
        margin = JBUI.insets(2, 10)
        toolTipText = "Edit the flow on a canvas"
    }
    private val viewCards = JPanel(CardLayout())
    private val graphHint = JBLabel(GRAPH_HINT_SHORT).apply {
        foreground = UIUtil.getContextHelpForeground()
        toolTipText = GRAPH_HINT
        border = JBUI.Borders.empty(2, 6)
    }
    private val undoButton = JButton(AllIcons.Actions.Undo).apply {
        toolTipText = "Undo (Ctrl+Z)"
        isFocusPainted = false
    }
    private val redoButton = JButton(AllIcons.Actions.Redo).apply {
        toolTipText = "Redo (Ctrl+Y)"
        isFocusPainted = false
    }
    private val autoLayoutButton = JButton("Auto layout", AllIcons.Actions.Refresh).apply {
        toolTipText = "Lay the flow out from left to right"
        isFocusPainted = false
    }
    private val fitButton = JButton("Fit", null).apply {
        toolTipText = "Fit the whole flow into the view"
        isFocusPainted = false
    }
    private val graphCanvas = ScenarioGraphCanvas(
        scenarioProvider = { scenario },
        requestProvider = service::findRequest,
        onSelectionChanged = { nodeId, edgeId -> onCanvasSelection(nodeId, edgeId) },
        onOpenRequest = { openRequest(it) },
        onAddStep = { addStep() },
        onBeforeEdit = { recordHistory() },
        onGraphChanged = { structureChanged() },
        onHint = { message -> updateGraphHint(message) },
        onRequestDropped = { requestId, x, y -> addRequestAt(requestId, x, y) }
    )

    private val parametersPanel = KeyValueTablePanel(
        onChanged = { modelChanged() },
        showLegend = false,
        variablesProvider = { VariableResolver.environmentVariables(service.activeEnvironment()) }
    )
    private val bottomTabs = JTabbedPane().apply {
        addTab("Step", inspector)
        addTab("Parameters", wrapParametersPanel())
        addTab("Run", runPanel)
        addTab("Response", responsePanel)
    }

    private val stateListener: () -> Unit = {
        if (!disposed && !mutating) {
            val current = service.scenario(scenarioId)
            if (current != null) {
                scenario = current
                syncHeader()
                stepsTable.refreshNames()
                inspector.refreshRequestChoices()
                graphCanvas.refresh()
                refreshValidation(validateScenario())
                updateGraphButtons()
            }
        }
    }

    init {
        border = JBUI.Borders.empty()
        add(buildHeader(), BorderLayout.NORTH)
        add(buildBody(), BorderLayout.CENTER)
        installNameEditing()
        environmentCombo.addActionListener {
            if (refreshingHeader) return@addActionListener
            val environment = environmentCombo.selectedItem as? EnvironmentConfig ?: return@addActionListener
            service.selectEnvironment(environment.id)
        }
        runButton.addActionListener { runScenario() }
        cancelButton.addActionListener { cancelRun() }
        ButtonGroup().apply {
            add(stepsToggle)
            add(graphToggle)
        }
        stepsToggle.addActionListener {
            if (!stepsToggle.isSelected) return@addActionListener
            showView(graph = false)
        }
        graphToggle.addActionListener {
            if (!graphToggle.isSelected) return@addActionListener
            showView(graph = true)
        }
        undoButton.addActionListener { undo() }
        redoButton.addActionListener { redo() }
        autoLayoutButton.addActionListener { graphCanvas.autoLayout() }
        fitButton.addActionListener { graphCanvas.fitToContent() }
        installUndoShortcuts()
        validationLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1) showValidationPopup()
            }
        })

        service.addChangeListener(stateListener)
        syncHeader()
        stepsTable.bind(scenario)
        scenario?.let { parametersPanel.bind(it.parameters) }
        refreshValidation(validateScenario())
        updateGraphButtons()
    }

    private fun buildHeader(): JComponent {
        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10)
            background = UIUtil.getPanelBackground()
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JBLabel("Scenario").apply { font = font.deriveFont(Font.BOLD) })
            add(nameField)
            add(JBLabel("Env").apply { font = font.deriveFont(Font.BOLD) })
            add(environmentCombo)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyLeft(10)
                    add(stepsToggle)
                    add(graphToggle)
                }
            )
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(validationLabel)
            add(runButton)
            add(cancelButton)
        }
        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)
        return header
    }

    private fun buildBody(): JComponent {
        viewCards.add(stepsTable, CARD_STEPS)
        viewCards.add(buildGraphCard(), CARD_GRAPH)
        val split = OnePixelSplitter(true, 0.6f).apply {
            firstComponent = viewCards
            secondComponent = bottomTabs
            dividerWidth = 1
        }
        stepsTable.minimumSize = Dimension(JBUI.scale(200), JBUI.scale(140))
        graphCanvas.minimumSize = Dimension(JBUI.scale(200), JBUI.scale(160))
        return split
    }

    private fun buildGraphCard(): JComponent = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 6)
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(autoLayoutButton)
            add(fitButton)
            add(undoButton)
            add(redoButton)
        }
        add(toolbar, BorderLayout.NORTH)
        add(graphCanvas, BorderLayout.CENTER)
        add(graphHint, BorderLayout.SOUTH)
    }

    private fun wrapParametersPanel(): JComponent = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
        border = JBUI.Borders.empty(8, 10)
        add(
            JBLabel("Defaults for this scenario. The Run dialog can override them for a single run.").apply {
                foreground = UIUtil.getContextHelpForeground()
            },
            BorderLayout.NORTH
        )
        add(parametersPanel, BorderLayout.CENTER)
    }

    private fun installNameEditing() {
        nameField.addActionListener { commitName() }
        nameField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(event: FocusEvent) = commitName()
        })
    }

    private fun commitName() {
        val current = scenario ?: return
        val requested = nameField.text.trim()
        if (requested.isEmpty() || requested == current.name) {
            nameField.text = current.name
            return
        }
        if (!service.renameScenario(scenarioId, requested)) {
            nameField.text = current.name
            return
        }
        nameField.text = current.name
        scenario = service.scenario(scenarioId)
    }

    private fun syncHeader() {
        refreshingHeader = true
        try {
            val current = scenario
            if (current != null && !nameField.hasFocus() && nameField.text != current.name) {
                nameField.text = current.name
            }
            val environments = service.state().environments
            val model = DefaultComboBoxModel(environments.toTypedArray())
            environmentCombo.model = model
            val active = service.activeEnvironment()
            if (active != null) environmentCombo.selectedItem = active
            environmentCombo.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): java.awt.Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is EnvironmentConfig) {
                        text = value.name
                        toolTipText = value.baseUrl
                    }
                    return this
                }
            }
        } finally {
            refreshingHeader = false
        }
        updateRunButtons()
    }

    private fun showNode(nodeId: String?, switchTab: Boolean = true) {
        currentNodeId = nodeId
        if (disposed) return
        inspector.bind(scenario, nodeId)
        updateResponseView(nodeId)
        if (nodeId != null && switchTab) bottomTabs.selectedIndex = 0
    }

    /** Shows the retained response of [nodeId] in the Response tab, when the last run has one. */
    private fun updateResponseView(nodeId: String?) {
        val response = nodeId?.let { lastRun?.nodes?.get(it)?.response }
        if (response != null) {
            responsePanel.show(response)
        } else {
            responsePanel.showEmptyState()
        }
    }

    private fun onStepsSelection(nodeId: String?) {
        if (syncingSelection || disposed) return
        showNode(nodeId)
        graphCanvas.selectNodeSilently(nodeId)
        updateGraphHint(null)
    }

    private fun onCanvasSelection(nodeId: String?, edgeId: String?) {
        if (syncingSelection || disposed) return
        selectFromEverywhere(
            nodeId = nodeId,
            skipCanvas = true,
            switchTab = nodeId != null
        )
        when {
            edgeId != null -> updateGraphHint("Dependency selected. Press Delete to remove it.")
            nodeId != null -> updateGraphHint(null)
            else -> updateGraphHint(null)
        }
    }

    private fun selectFromRunPanel(nodeId: String) {
        if (syncingSelection || disposed) return
        selectFromEverywhere(nodeId, switchTab = false)
        updateGraphHint(null)
    }

    private fun selectFromEverywhere(
        nodeId: String?,
        skipSteps: Boolean = false,
        skipCanvas: Boolean = false,
        switchTab: Boolean = true
    ) {
        showNode(nodeId, switchTab)
        syncingSelection = true
        try {
            if (!skipSteps) {
                if (nodeId != null) {
                    stepsTable.selectNode(nodeId)
                } else {
                    stepsTable.clearSelection()
                }
            }
            if (!skipCanvas) {
                graphCanvas.selectNodeSilently(nodeId)
            }
        } finally {
            syncingSelection = false
        }
    }

    private fun showView(graph: Boolean) {
        if (disposed) return
        showingGraph = graph
        (viewCards.layout as CardLayout).show(viewCards, if (graph) CARD_GRAPH else CARD_STEPS)
        stepsToggle.isSelected = !graph
        graphToggle.isSelected = graph
        if (graph) {
            if (!graphEverShown) {
                graphEverShown = true
                graphCanvas.fitToContent()
            }
            graphCanvas.selectNodeSilently(currentNodeId)
            graphCanvas.refresh()
        } else {
            val nodeId = currentNodeId
            if (nodeId != null) {
                stepsTable.selectNode(nodeId)
            } else {
                stepsTable.clearSelection()
            }
            stepsTable.focusTable()
        }
        updateGraphButtons()
    }

    private fun updateGraphHint(message: String?) {
        graphHint.text = message ?: GRAPH_HINT_SHORT
        graphHint.foreground = UIUtil.getContextHelpForeground()
        graphHint.toolTipText = GRAPH_HINT
    }

    private fun updateGraphButtons() {
        undoButton.isEnabled = history.canUndo
        redoButton.isEnabled = history.canRedo
        autoLayoutButton.isEnabled = scenario?.nodes?.isNotEmpty() == true
        fitButton.isEnabled = scenario?.nodes?.isNotEmpty() == true
    }

    private fun recordHistory() {
        scenario?.let { history.record(it) }
        updateGraphButtons()
    }

    private fun discardLastHistory() {
        history.discardLast()
        updateGraphButtons()
    }

    private fun undo() {
        val current = scenario ?: return
        if (!history.undo(current)) return
        afterHistoryChange("Undo")
    }

    private fun redo() {
        val current = scenario ?: return
        if (!history.redo(current)) return
        afterHistoryChange("Redo")
    }

    private fun afterHistoryChange(message: String) {
        notifyChanged()
        stepsTable.bind(scenario, keepRunState = activeRun != null)
        graphCanvas.refresh()
        graphCanvas.selectNodeSilently(currentNodeId)
        refreshValidation(validateScenario())
        updateGraphButtons()
        updateGraphHint(message)
    }

    private fun installUndoShortcuts() {
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK),
            "scenarioUndo"
        )
        actionMap.put("scenarioUndo", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = undo()
        })
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            "scenarioRedo"
        )
        actionMap.put("scenarioRedo", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = redo()
        })
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK),
            "scenarioRedoKey"
        )
        actionMap.put("scenarioRedoKey", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = redo()
        })
    }

    private fun addStep() {
        val current = scenario ?: return
        if (allRequests().isEmpty()) {
            notifyUser("Create a request first, then add it as a step.", NotificationType.WARNING)
            return
        }
        val lastRequestId = ScenarioGraph.requestNodes(current).lastOrNull()?.requestId
        val dialog = RequestPickerDialog(project, service, lastRequestId)
        if (!dialog.showAndGet()) return
        val request = dialog.selectedRequest() ?: return
        addRequestNode(request)
    }

    private fun addRequestNode(request: HttpRequestConfig) {
        val current = scenario ?: return
        recordHistory()
        val node = ScenarioGraph.addRequestNode(
            scenario = current,
            requestId = request.id,
            afterNodeId = ScenarioGraph.requestNodes(current).lastOrNull()?.id
        )
        if (node.outputs.isEmpty()) {
            node.outputs.addAll(request.extractors.map { it.deepCopy() })
        }
        structureChanged()
        stepsTable.selectNode(node.id)
    }

    /** Adds a step dropped from the request tree at the canvas coordinates. */
    private fun addRequestAt(requestId: String, x: Int, y: Int) {
        val current = scenario ?: return
        val request = service.findRequest(requestId)
        if (request == null) {
            updateGraphHint("That request no longer exists.")
            return
        }
        recordHistory()
        val node = ScenarioGraph.addRequestNode(current, request.id, afterNodeId = null)
        node.x = x
        node.y = y
        if (node.outputs.isEmpty()) {
            node.outputs.addAll(request.extractors.map { it.deepCopy() })
        }
        structureChanged()
        stepsTable.selectNode(node.id)
        graphCanvas.selectNodeSilently(node.id)
        showNode(node.id)
        updateGraphHint("Step added")
    }

    private fun removeStep(nodeId: String) {
        val current = scenario ?: return
        if (ScenarioGraph.node(current, nodeId)?.isRequestNode() != true) return
        recordHistory()
        if (!ScenarioGraph.removeNode(current, nodeId)) return
        structureChanged()
    }

    private fun openRequest(requestId: String) {
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Smart Request")
        toolWindow?.show()
        project.messageBus.syncPublisher(dev.smartrequest.scenario.ScenarioTopics.OPEN_REQUEST)
            .openRequest(requestId)
    }

    private fun allRequests(): List<HttpRequestConfig> =
        service.state().groups.flatMap { it.requests }

    /** Variables an upstream step captures, so the capture editor can suggest them. */
    private fun captureSuggestions(nodeId: String): List<String> {
        val current = scenario ?: return emptyList()
        val upstream = ScenarioGraph.ancestors(current, nodeId)
        val names = LinkedHashSet<String>()
        names.addAll(VariableResolver.environmentVariables(service.activeEnvironment()).keys)
        current.nodes.filter { it.id in upstream }.forEach { node ->
            ScenarioValidator.captureNames(node).forEach(names::add)
        }
        names.remove(dev.smartrequest.extract.ResponseExtractorEngine.RESERVED_VARIABLE)
        return names.toList().sorted()
    }

    private fun modelChanged() {
        notifyChanged()
        stepsTable.refreshNames()
        graphCanvas.refresh()
        refreshValidation(validateScenario())
        updateGraphButtons()
    }

    private fun structureChanged() {
        notifyChanged()
        stepsTable.bind(scenario, keepRunState = activeRun != null)
        graphCanvas.refresh()
        refreshValidation(validateScenario())
        updateGraphButtons()
    }

    private fun notifyChanged() {
        mutating = true
        try {
            service.notifyChanged()
        } finally {
            mutating = false
        }
    }

    private fun defaultParameters(): Map<String, String> {
        val current = scenario ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        current.parameters.filter { it.enabled && it.key.isNotBlank() }.forEach {
            result[it.key.trim()] = it.value
        }
        return result
    }

    private fun validateScenario(
        parameters: Map<String, String> = defaultParameters(),
        environment: EnvironmentConfig? = service.activeEnvironment()
    ): ScenarioValidator.Report {
        val current = scenario ?: return ScenarioValidator.Report()
        return ScenarioValidator.validate(
            scenario = current,
            requestSource = service::findRequest,
            environmentVariables = VariableResolver.environmentVariables(environment),
            parameters = parameters
        )
    }

    private fun refreshValidation(report: ScenarioValidator.Report) {
        validationReport = report
        val error = report.errors.firstOrNull()
        validationLabel.text = when {
            report.requestNodeCount == 0 -> "Add a step"
            error != null -> "${report.errors.size} error(s)"
            report.warnings.isNotEmpty() -> "✓ Valid · ${report.warnings.size} warning(s)"
            else -> "✓ Valid"
        }
        validationLabel.toolTipText = when {
            error != null -> "${error.message}\nClick for all checks"
            report.warnings.isNotEmpty() -> report.warnings.first().message
            report.requestNodeCount == 0 -> "A scenario needs at least one request step"
            else -> "All checks passed"
        }
        validationLabel.foreground = when {
            error != null -> VALIDATION_ERROR
            report.requestNodeCount == 0 -> UIUtil.getInactiveTextColor()
            report.warnings.isNotEmpty() -> VALIDATION_WARNING
            else -> VALIDATION_OK
        }
        updateRunButtons()
    }

    private fun showValidationPopup() {
        if (disposed) return
        val report = validationReport
        val problems = report.problems
        if (problems.isEmpty()) {
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(listOf("All checks passed"))
                .setTitle("Scenario checks")
                .setMovable(false)
                .setResizable(false)
                .createPopup()
                .showUnderneathOf(validationLabel)
            return
        }
        val labels = problems.map { problem ->
            (if (problem.severity == ScenarioValidator.Severity.ERROR) "Error: " else "Warning: ") +
                    problem.message
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labels)
            .setTitle("Scenario checks")
            .setMovable(false)
            .setResizable(false)
            .setItemChosenCallback { chosen ->
                val problem = problems[labels.indexOf(chosen)]
                val nodeId = problem.nodeId ?: problem.edgeId?.let { edgeId ->
                    scenario?.edges?.firstOrNull { it.id == edgeId }?.to
                }
                if (nodeId != null) stepsTable.selectNode(nodeId)
            }
            .createPopup()
            .showUnderneathOf(validationLabel)
    }

    private fun updateRunButtons() {
        val running = activeRun != null
        // Keep Run clickable while a run is active: it focuses the run panel instead of starting a
        // second run, which is the same rule the list uses.
        runButton.isEnabled = scenario != null
        runButton.text = if (running) "Running…" else "Run"
        cancelButton.isVisible = running
        cancelButton.isEnabled = running && cancelButton.text == "Cancel"
    }

    private fun runScenario() {
        if (activeRun != null) {
            bottomTabs.selectedIndex = 2
            return
        }
        val current = scenario ?: return
        if (ScenarioGraph.requestNodes(current).isEmpty()) {
            notifyUser("Add at least one step before running the scenario.", NotificationType.WARNING)
            showValidationPopup()
            return
        }
        val report = validateScenario()
        refreshValidation(report)
        val hasParameters = current.parameters.any { it.enabled && it.key.isNotBlank() }
        if (report.canRun && !hasParameters && report.missingVariables.isEmpty()) {
            startRun(
                RunEnvironment.from(
                    service.activeEnvironment(),
                    service.state().timeoutMs,
                    service.state().followRedirects
                ),
                emptyMap()
            )
            return
        }

        val dialog = ScenarioRunDialog(project, current, service, service.activeEnvironment())
        if (!dialog.showAndGet()) {
            refreshValidation(validateScenario())
            return
        }
        val environment = dialog.selectedEnvironment() ?: return
        if (environment.id != service.activeEnvironment()?.id) {
            service.selectEnvironment(environment.id)
        }
        val parameters = dialog.parameters()
        val finalReport = validateScenario(parameters, environment)
        if (!finalReport.canRun) {
            refreshValidation(finalReport)
            showValidationPopup()
            return
        }
        startRun(
            RunEnvironment.from(environment, service.state().timeoutMs, service.state().followRedirects),
            parameters
        )
    }

    private fun startRun(environment: RunEnvironment, parameters: Map<String, String>) {
        val current = scenario ?: return
        if (activeRun != null) return
        if (!validateScenario(parameters, environment.environmentConfig()).canRun) {
            refreshValidation(validateScenario(parameters, environment.environmentConfig()))
            showValidationPopup()
            return
        }

        // Snapshot every request the flow references, so edits made while it runs only affect the
        // next run.
        val snapshot = LinkedHashMap<String, HttpRequestConfig>()
        current.nodes.filter { it.isRequestNode() }.forEach { node ->
            val requestId = node.requestId ?: return@forEach
            val request = service.findRequest(requestId) ?: return@forEach
            snapshot[requestId] = request.deepCopy()
        }
        val fake = testTransport
        val client = if (fake == null) HttpTransport.createClient(environment.followRedirects) else null
        val http = if (fake == null) HttpTransport() else null
        val transport = fake ?: ScenarioTransport { prepared ->
            http!!.send(prepared, client!!)
        }
        val runner = ScenarioRunner(
            transport = transport,
            requests = { id -> snapshot[id] },
            listener = { event ->
                ApplicationManager.getApplication().invokeLater { handleRunEvent(event) }
            }
        )

        lastRun = null
        updateResponseView(currentNodeId)
        stepsTable.clearResults()
        graphCanvas.startRun(current)
        runPanel.startRun(current)
        bottomTabs.selectedIndex = 2
        val handle = try {
            runner.start(current, environment, parameters)
        } catch (e: Exception) {
            if (client != null) client.shutdownNow()
            runClient = null
            runPanel.reset()
            stepsTable.clearResults()
            graphCanvas.clearRunState()
            bottomTabs.selectedIndex = 0
            notifyUser(
                "The scenario could not start: ${e.message ?: e.javaClass.simpleName}",
                NotificationType.ERROR
            )
            return
        }
        activeRun = handle
        runClient = client
        updateRunButtons()
    }

    private fun handleRunEvent(event: ScenarioRunEvent) {
        if (disposed) return
        val handle = activeRun ?: return
        if (event.runId != handle.runId) return
        when (event) {
            is ScenarioRunEvent.RunStarted -> Unit
            is ScenarioRunEvent.NodeStarted -> {
                stepsTable.setNodeState(event.nodeId, NodeRunState.RUNNING)
                runPanel.setNodeState(event.nodeId, NodeRunState.RUNNING)
                graphCanvas.setRunState(event.nodeId, NodeRunState.RUNNING)
            }

            is ScenarioRunEvent.NodeFinished -> {
                stepsTable.updateNodeResult(event.result)
                runPanel.updateNode(event.result)
                graphCanvas.setRunResult(event.result)
            }

            is ScenarioRunEvent.RunFinished -> finishRun(event.result)
        }
    }

    private fun finishRun(result: ScenarioRunResult) {
        lastRun = result
        activeRun = null
        stepsTable.setResults(result.nodes)
        updateResponseView(currentNodeId)
        runPanel.finish(result)
        graphCanvas.setRunResults(result.nodes)
        val client = runClient
        runClient = null
        if (client != null) {
            if (result.state == dev.smartrequest.scenario.ScenarioRunState.CANCELLED ||
                result.state == dev.smartrequest.scenario.ScenarioRunState.FAILED
            ) {
                client.shutdownNow()
            } else {
                client.close()
            }
        }
        cancelButton.text = "Cancel"
        cancelButton.isEnabled = true
        updateRunButtons()
        refreshValidation(validateScenario())
    }

    private fun cancelRun() {
        val handle = activeRun ?: return
        runPanel.cancelRequested()
        handle.cancel()
    }

    private fun notifyUser(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Smart Request")
            .createNotification("Scenarios", message, type)
            .notify(project)
    }

    fun preferredFocusComponent(): JComponent = nameField

    fun isAlive(): Boolean = !disposed && scenario != null && service.scenario(scenarioId) != null

    override fun dispose() {
        if (disposed) return
        disposed = true
        service.removeChangeListener(stateListener)
        activeRun?.cancel()
        activeRun = null
        runClient?.shutdownNow()
        runClient = null
    }

    // Test hooks. They drive the panel the way the UI does, without dispatch events.

    internal fun stepsTableForTest(): ScenarioStepsTable = stepsTable

    internal fun inspectorForTest(): ScenarioInspectorPanel = inspector

    internal fun runPanelForTest(): ScenarioRunPanel = runPanel

    internal fun responsePanelForTest(): ResponsePanel = responsePanel

    internal fun graphCanvasForTest(): ScenarioGraphCanvas = graphCanvas

    internal fun showGraphForTest() {
        showView(graph = true)
    }

    internal fun showStepsForTest() {
        showView(graph = false)
    }

    internal fun isGraphViewForTest(): Boolean = showingGraph

    internal fun graphHintForTest(): String = graphHint.text

    internal fun undoForTest() {
        undo()
    }

    internal fun redoForTest() {
        redo()
    }

    internal fun canUndoForTest(): Boolean = history.canUndo

    internal fun canRedoForTest(): Boolean = history.canRedo

    internal fun parametersPanelForTest(): KeyValueTablePanel = parametersPanel

    internal fun validationTextForTest(): String = validationLabel.text

    internal fun validationReportForTest(): ScenarioValidator.Report = validationReport

    internal fun addStepForTest(requestId: String): Boolean {
        val request = service.findRequest(requestId) ?: return false
        addRequestNode(request)
        return true
    }

    internal fun removeStepForTest(nodeId: String) {
        removeStep(nodeId)
    }

    internal fun selectNodeForTest(nodeId: String) {
        stepsTable.selectNode(nodeId)
    }

    internal fun startRunForTest(parameters: Map<String, String> = emptyMap()): Boolean {
        val current = scenario ?: return false
        val environment = RunEnvironment.from(
            service.activeEnvironment(),
            service.state().timeoutMs,
            service.state().followRedirects
        )
        if (!validateScenario(parameters, environment.environmentConfig()).canRun) return false
        if (ScenarioGraph.requestNodes(current).isEmpty()) return false
        startRun(environment, parameters)
        return true
    }

    internal fun lastRunForTest(): ScenarioRunResult? = lastRun

    internal fun isRunningForTest(): Boolean = activeRun != null

    internal fun scenarioForTest(): ScenarioConfig? = scenario

    companion object {
        private const val CARD_STEPS = "steps"
        private const val CARD_GRAPH = "graph"
        private const val GRAPH_HINT =
            "Drag nodes · drag a right port to a left port to connect · Ctrl+wheel zoom · drag empty space to pan · Delete removes the selection"
        private const val GRAPH_HINT_SHORT =
            "Drag nodes · connect ports · Ctrl+wheel zoom · Delete removes"

        private val VALIDATION_OK = com.intellij.ui.JBColor(0x1A7F37, 0x73BD79)
        private val VALIDATION_WARNING = com.intellij.ui.JBColor(0xA15C00, 0xD6A26A)
        private val VALIDATION_ERROR = com.intellij.ui.JBColor(0xC7222D, 0xFF6B68)
    }
}
