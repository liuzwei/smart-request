package dev.smartrequest.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.extract.ExtractedValue
import dev.smartrequest.extract.ExtractionReport
import dev.smartrequest.extract.ResponseExtractorEngine
import dev.smartrequest.http.CurlCommandBuilder
import dev.smartrequest.http.CurlFormat
import dev.smartrequest.http.HttpResponseData
import dev.smartrequest.http.HttpTransport
import dev.smartrequest.http.PrepareResult
import dev.smartrequest.http.RequestBuildException
import dev.smartrequest.http.RequestPreparer
import dev.smartrequest.http.VariableResolver
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.RequestGroup
import dev.smartrequest.model.RequestHistoryEntry
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.scenario.OpenRequestListener
import dev.smartrequest.scenario.ScenarioTopics
import dev.smartrequest.service.SmartRequestProjectService
import dev.smartrequest.spring.SmartRequestTopics
import dev.smartrequest.spring.SpringEndpointScanner
import dev.smartrequest.spring.SpringScanResult
import dev.smartrequest.spring.SpringRequestsImportedListener
import dev.smartrequest.ui.dialogs.EnvironmentDialog
import dev.smartrequest.ui.dialogs.FunctionsDialog
import dev.smartrequest.ui.dialogs.GlobalSettingsDialog
import dev.smartrequest.ui.dialogs.HistoryDialog
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel

class SmartRequestPanel(
    private val project: Project
) : JBPanel<SmartRequestPanel>(BorderLayout()), Disposable {

    private val service = project.service<SmartRequestProjectService>()
    private val transport = HttpTransport()
    private val environmentCombo = JComboBox<EnvironmentConfig>()
    private val editorCards = JBPanel<JBPanel<*>>(CardLayout())
    private val responsePanel = ResponsePanel(project)
    private val treePanel: RequestTreePanel
    private val editorPanel: RequestEditorPanel
    private val emptyPanel: JPanel
    private val scenarioListPanel: ScenarioListPanel
    private val contentCards = JBPanel<JBPanel<*>>(CardLayout())
    private val scenarioHost = JBPanel<JBPanel<*>>(BorderLayout())
    private var scenarioPanel: ScenarioEditorPanel? = null
    private var openScenarioId: String? = null
    private var currentContentCard: String = CARD_REQUEST

    private var inFlight: CompletableFuture<HttpResponseData>? = null
    private var activeSendId: String? = null
    private var currentRequest: HttpRequestConfig? = null
    private var refreshingEnvironment = false
    private val responseCache = LinkedHashMap<String, CachedResponse>()
    private val stateListener: () -> Unit = {
        refreshEnvironmentCombo()
        refreshGlobals()
        editorPanel.refreshVariables()
        closeScenarioIfDeleted()
    }

    init {
        treePanel = RequestTreePanel(
            service = service,
            onSelect = { openRequest(it) },
            onCreateRequest = { createRequest(it) },
            onCreateGroup = { createGroup() },
            onDeleteRequest = { deleteRequest(it) },
            onDeleteGroup = { deleteGroup(it) },
            onRenameRequest = { renameRequest(it) },
            onRenameGroup = { renameGroup(it) },
            onDuplicateRequest = { duplicateRequest(it) },
            onImportSpringIntoGroup = { importSpringEndpoints(it.id) },
            onCopyCurl = { request, format -> copyCurl(request, format) },
            onMoveRequest = { request, group -> moveRequest(request, group) },
            onMoveRequestToNewGroup = { moveRequestToNewGroup(it) }
        )
        editorPanel = RequestEditorPanel(
            onSend = { sendRequest(it) },
            onCancel = { cancelRequest() },
            onNameChanged = { treePanel.repaintTree() },
            variablesProvider = { VariableResolver.environmentVariables(service.activeEnvironment()) },
            onRunExtract = { runExtractionForCurrentRequest() }
        )
        scenarioListPanel = ScenarioListPanel(project) { openScenario(it) }
        emptyPanel = buildEmptyPanel()
        editorCards.add(emptyPanel, CARD_EMPTY)
        editorCards.add(editorPanel, CARD_EDITOR)

        add(buildToolbar(), BorderLayout.NORTH)
        add(buildSplitter(), BorderLayout.CENTER)

        service.addChangeListener(stateListener)
        project.messageBus.connect(this).subscribe(
            SmartRequestTopics.SPRING_REQUESTS_IMPORTED,
            object : SpringRequestsImportedListener {
                override fun endpointsImported(result: dev.smartrequest.spring.SpringImportResult) {
                    val firstId = result.firstRequestId
                    if (firstId != null) {
                        treePanel.rebuild(firstId)
                        service.findRequest(firstId)?.let { openRequest(it) }
                    } else {
                        treePanel.rebuild()
                    }
                }
            }
        )
        project.messageBus.connect(this).subscribe(
            ScenarioTopics.OPEN_REQUEST,
            object : OpenRequestListener {
                override fun openRequest(requestId: String) {
                    val request = service.findRequest(requestId) ?: return
                    treePanel.selectRequest(request.id)
                    this@SmartRequestPanel.openRequest(request)
                }
            }
        )
        refreshEnvironmentCombo()
        refreshGlobals()
        showEmptyState()
        treePanel.selectFirstRequest()
    }

    private fun buildToolbar(): Component {
        val toolbar = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 10)
            background = UIUtil.getPanelBackground()
        }

        environmentCombo.apply {
            preferredSize = Dimension(JBUI.scale(150), preferredSize.height)
            maximumRowCount = 12
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is EnvironmentConfig) {
                        text = value.name
                        toolTipText = value.baseUrl
                    } else {
                        text = "No environment"
                    }
                    return this
                }
            }
            addActionListener {
                if (refreshingEnvironment) return@addActionListener
                (selectedItem as? EnvironmentConfig)?.let { service.selectEnvironment(it.id) }
            }
        }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JBLabel("Env").apply { font = font.deriveFont(Font.BOLD) })
            add(environmentCombo)
            add(flatButton("", AllIcons.General.Settings) { openEnvironmentDialog() }.apply {
                toolTipText = "Manage environments"
                preferredSize = Dimension(JBUI.scale(30), JBUI.scale(28))
            })
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(flatButton("", AllIcons.Vcs.History) { openHistoryDialog() }.apply {
                toolTipText = "Request history"
                preferredSize = Dimension(JBUI.scale(30), JBUI.scale(28))
            })
            add(flatButton("Functions", null) { openFunctionsDialog() }.apply {
                toolTipText = "Define pure template functions"
            })
            add(flatButton("Settings", null) { openGlobalSettings() }.apply {
                toolTipText = "Request timeout and redirects"
            })
            add(flatButton("Spring", null) { importSpringEndpoints(null) })
        }
        toolbar.add(left, BorderLayout.WEST)
        toolbar.add(right, BorderLayout.EAST)
        return toolbar
    }

    private fun buildSplitter(): Component {
        val requestSplit = OnePixelSplitter(true, 0.58f).apply {
            firstComponent = editorCards
            secondComponent = responsePanel
            dividerWidth = 1
        }
        contentCards.add(requestSplit, CARD_REQUEST)
        contentCards.add(scenarioHost, CARD_SCENARIO)
        (contentCards.layout as CardLayout).show(contentCards, CARD_REQUEST)

        val leftSplit = OnePixelSplitter(true, 0.7f).apply {
            firstComponent = treePanel
            secondComponent = scenarioListPanel
            dividerWidth = 1
        }
        val mainSplit = OnePixelSplitter(false, 0.34f).apply {
            firstComponent = leftSplit
            secondComponent = contentCards
            dividerWidth = 1
        }
        treePanel.minimumSize = Dimension(JBUI.scale(150), JBUI.scale(120))
        scenarioListPanel.minimumSize = Dimension(JBUI.scale(150), JBUI.scale(100))
        return mainSplit
    }

    private fun buildEmptyPanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
        }
        val center = JPanel().apply {
            isOpaque = false
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        }
        val title = JBLabel("No request selected").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            font = font.deriveFont(Font.BOLD, font.size2D + 2f)
        }
        val subtitle = JBLabel("Choose a request from the tree, or create a new one.").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = UIUtil.getContextHelpForeground()
        }
        val button = JButton("New Request", AllIcons.General.Add).apply {
            alignmentX = Component.CENTER_ALIGNMENT
            addActionListener {
                val groupId = treePanel.selectedGroup()?.id ?: service.state().groups.firstOrNull()?.id
                if (groupId != null) createRequest(groupId)
            }
        }
        center.add(javax.swing.Box.createVerticalGlue())
        center.add(title)
        center.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
        center.add(subtitle)
        center.add(javax.swing.Box.createVerticalStrut(JBUI.scale(14)))
        center.add(button)
        center.add(javax.swing.Box.createVerticalGlue())
        panel.add(center, BorderLayout.CENTER)
        return panel
    }

    private fun flatButton(text: String, icon: javax.swing.Icon?, action: () -> Unit): JButton =
        JButton(text).apply {
            this.icon = icon
            isFocusPainted = false
            addActionListener { action() }
        }

    private fun refreshEnvironmentCombo() {
        refreshingEnvironment = true
        try {
            val model = DefaultComboBoxModel<EnvironmentConfig>()
            service.state().environments.forEach { model.addElement(it) }
            environmentCombo.model = model
            val active = service.activeEnvironment()
            if (active != null) environmentCombo.selectedItem = active
        } finally {
            refreshingEnvironment = false
        }
    }

    private fun refreshGlobals() {
        val environment = service.activeEnvironment()
        editorPanel.setGlobals(
            environment?.globalHeaders.orEmpty(),
            environment?.globalParams.orEmpty()
        )
    }

    private fun openRequest(request: HttpRequestConfig) {
        showRequestCard()
        if (currentRequest?.id == request.id) return
        currentRequest = request
        editorPanel.open(request)
        (editorCards.layout as CardLayout).show(editorCards, CARD_EDITOR)
        if (activeSendId == request.id) {
            editorPanel.setSending(true)
            responsePanel.showLoading()
            editorPanel.setExtractionReport(null)
        } else {
            editorPanel.setSending(false)
            showCachedResponse(request.id)
        }
    }

    private fun showCachedResponse(requestId: String) {
        val cached = responseCache[requestId]
        if (cached == null || (cached.response == null && cached.error == null)) {
            responsePanel.showEmptyState()
            editorPanel.setExtractionReport(null)
            return
        }
        if (cached.response != null) {
            responsePanel.show(cached.response)
            responsePanel.setExtractionReport(cached.report)
            editorPanel.setExtractionReport(cached.report)
        } else {
            responsePanel.showError(cached.error ?: "Request failed")
            editorPanel.setExtractionReport(cached.report)
        }
    }

    private fun cacheResponse(
        requestId: String,
        response: HttpResponseData?,
        error: String?,
        report: ExtractionReport? = null
    ) {
        responseCache.remove(requestId)
        responseCache[requestId] = CachedResponse(response, error, report)
        while (responseCache.size > RESPONSE_CACHE_SIZE) {
            val oldest = responseCache.keys.firstOrNull() ?: break
            responseCache.remove(oldest)
        }
    }

    private fun showEmptyState() {
        showRequestCard()
        currentRequest = null
        (editorCards.layout as CardLayout).show(editorCards, CARD_EMPTY)
        responsePanel.showEmptyState()
    }

    private fun showRequestCard() {
        if (currentContentCard == CARD_REQUEST) return
        currentContentCard = CARD_REQUEST
        (contentCards.layout as CardLayout).show(contentCards, CARD_REQUEST)
    }

    /** Opens [scenario] in the right pane, reusing the panel when the same scenario is selected. */
    private fun openScenario(scenario: ScenarioConfig) {
        if (openScenarioId == scenario.id && scenarioPanel != null) {
            showScenarioCard()
            return
        }
        scenarioPanel?.let { Disposer.dispose(it) }
        val panel = ScenarioEditorPanel(project, scenario.id)
        scenarioPanel = panel
        openScenarioId = scenario.id
        scenarioHost.removeAll()
        scenarioHost.add(panel, BorderLayout.CENTER)
        scenarioHost.revalidate()
        scenarioHost.repaint()
        showScenarioCard()
    }

    private fun showScenarioCard() {
        currentContentCard = CARD_SCENARIO
        (contentCards.layout as CardLayout).show(contentCards, CARD_SCENARIO)
    }

    private fun closeScenarioIfDeleted() {
        val id = openScenarioId ?: return
        if (service.scenario(id) != null) return
        scenarioPanel?.let { Disposer.dispose(it) }
        scenarioPanel = null
        openScenarioId = null
        scenarioHost.removeAll()
        showRequestCard()
    }

    private fun createRequest(groupId: String) {
        val request = service.addRequest(groupId)
        treePanel.rebuild(request.id)
        openRequest(request)
        editorPanel.focusUrl()
    }

    private fun createGroup() {
        val name = Messages.showInputDialog(
            project,
            "Group name:",
            "New Request Group",
            Messages.getQuestionIcon(),
            "",
            null
        )?.trim() ?: return
        if (name.isEmpty()) return
        service.addGroup(service.uniqueGroupName(name))
        treePanel.rebuild()
    }

    private fun deleteRequest(request: HttpRequestConfig) {
        val result = Messages.showYesNoDialog(
            project,
            "Delete request '${request.name}'?",
            "Delete Request",
            Messages.getQuestionIcon()
        )
        if (result != Messages.YES) return
        responseCache.remove(request.id)
        service.removeRequest(request.id)
        if (currentRequest?.id == request.id) showEmptyState()
        treePanel.rebuild()
    }

    private fun deleteGroup(group: RequestGroup) {
        val count = group.requests.size
        val message = if (count == 0) {
            "Delete group '${group.name}'?"
        } else {
            "Delete group '${group.name}' and its $count request(s)?"
        }
        val result = Messages.showYesNoDialog(project, message, "Delete Group", Messages.getQuestionIcon())
        if (result != Messages.YES) return
        val containedCurrent = currentRequest?.let { service.groupOf(it.id)?.id == group.id } == true
        group.requests.forEach { responseCache.remove(it.id) }
        service.removeGroup(group.id)
        if (containedCurrent) showEmptyState()
        treePanel.rebuild()
    }

    private fun renameRequest(request: HttpRequestConfig) {
        val name = Messages.showInputDialog(
            project,
            "Request name:",
            "Rename Request",
            Messages.getQuestionIcon(),
            request.name,
            null
        )?.trim() ?: return
        if (name.isEmpty()) return
        request.name = name
        treePanel.repaintTree()
    }

    private fun renameGroup(group: RequestGroup) {
        val requested = Messages.showInputDialog(
            project,
            "Group name:",
            "Rename Group",
            Messages.getQuestionIcon(),
            group.name,
            null
        )?.trim() ?: return
        if (requested.isEmpty()) return
        group.name = if (service.state().groups.any { it.id != group.id && it.name == requested }) {
            service.uniqueGroupName(requested)
        } else {
            requested
        }
        service.notifyChanged()
        treePanel.rebuild()
    }

    private fun duplicateRequest(request: HttpRequestConfig) {
        val copy = service.duplicateRequest(request.id) ?: return
        treePanel.rebuild(copy.id)
        openRequest(copy)
    }

    private fun moveRequest(request: HttpRequestConfig, targetGroup: RequestGroup) {
        if (!service.moveRequest(request.id, targetGroup.id)) return
        treePanel.rebuild(request.id)
    }

    private fun moveRequestToNewGroup(request: HttpRequestConfig) {
        val name = Messages.showInputDialog(
            project,
            "Group name:",
            "Move to New Group",
            Messages.getQuestionIcon(),
            "",
            null
        )?.trim() ?: return
        if (name.isEmpty()) return
        val group = service.addGroup(service.uniqueGroupName(name))
        service.moveRequest(request.id, group.id)
        treePanel.rebuild(request.id)
    }

    private fun copyCurl(request: HttpRequestConfig, format: CurlFormat) {
        val state = service.state()
        val environment = service.activeEnvironment()
        val prepared = try {
            RequestPreparer.prepare(
                request = request,
                environment = environment,
                globalHeaders = environment?.globalHeaders.orEmpty(),
                globalParams = environment?.globalParams.orEmpty(),
                timeoutMs = state.timeoutMs,
                followRedirects = state.followRedirects,
                registry = service.functionRegistry()
            )
        } catch (e: RequestBuildException) {
            notify(e.message ?: "Request could not be prepared", NotificationType.WARNING)
            return
        } catch (e: Exception) {
            notify(e.message ?: "Request could not be prepared", NotificationType.ERROR)
            return
        }

        val command = CurlCommandBuilder.build(prepared.request, format)
        CopyPasteManager.getInstance().setContents(StringSelection(command))
        notify("cURL command copied (${format.label}).", NotificationType.INFORMATION)
    }

    private fun openEnvironmentDialog() {
        val dialog = EnvironmentDialog(project, service.state(), service)
        if (dialog.showAndGet()) {
            refreshEnvironmentCombo()
            refreshGlobals()
            editorPanel.refreshVariables()
        }
    }

    private fun openFunctionsDialog() {
        FunctionsDialog(project, service).show()
    }

    private fun openGlobalSettings() {
        val dialog = GlobalSettingsDialog(project, service.state(), service)
        if (dialog.showAndGet()) {
            refreshGlobals()
            currentRequest?.let { editorPanel.open(it) }
        }
    }

    private fun openHistoryDialog() {
        val dialog = HistoryDialog(project, service)
        if (dialog.showAndGet()) {
            dialog.restoredEntry?.let { restoreHistory(it) }
        }
    }

    private fun restoreHistory(entry: RequestHistoryEntry) {
        val group = service.findGroupByName(HISTORY_GROUP_NAME) ?: service.createGroupIfMissing(HISTORY_GROUP_NAME)
        val copy = entry.request.deepCopy(newId = true).apply {
            name = service.uniqueRequestName(
                group,
                entry.requestName.ifBlank { "${entry.method} ${entry.url}" }
            )
            springKey = null
        }
        group.requests.add(copy)
        group.expanded = true
        service.notifyChanged()
        treePanel.rebuild(copy.id)
        openRequest(copy)
    }

    private fun sendRequest(request: HttpRequestConfig) {
        if (inFlight != null) {
            if (activeSendId != request.id) {
                editorPanel.setStatusWarning("Another request is still running. Cancel it first.")
            }
            return
        }
        val state = service.state()
        val environment = service.activeEnvironment()
        val prepared: PrepareResult = try {
            RequestPreparer.prepare(
                request = request,
                environment = environment,
                globalHeaders = environment?.globalHeaders.orEmpty(),
                globalParams = environment?.globalParams.orEmpty(),
                timeoutMs = state.timeoutMs,
                followRedirects = state.followRedirects,
                registry = service.functionRegistry()
            )
        } catch (e: RequestBuildException) {
            editorPanel.setStatusWarning(e.message)
            responsePanel.showError(e.message ?: "Request could not be prepared")
            return
        } catch (e: Exception) {
            editorPanel.setStatusWarning(e.message ?: "Request could not be prepared")
            responsePanel.showError(e.message ?: "Request could not be prepared")
            return
        }

        val startedAt = System.currentTimeMillis()
        val requestSnapshot = request.deepCopy()
        val environmentName = service.activeEnvironment()?.name.orEmpty()
        // The values are written to the environment that was active when the request was sent, even
        // if the user switches environments while the request is still running.
        val environmentId = service.activeEnvironment()?.id
        val extractorsSnapshot = request.extractors.map { it.deepCopy() }
        responseCache.remove(request.id)
        editorPanel.setSending(true)
        editorPanel.setStatusWarning(null)
        responsePanel.showLoading()
        val future = try {
            transport.send(prepared.request)
        } catch (e: Exception) {
            editorPanel.setSending(false)
            editorPanel.setStatusWarning(e.message ?: "Invalid request")
            responsePanel.showError(e.message ?: "Invalid request")
            return
        }
        inFlight = future
        activeSendId = request.id
        future.whenComplete { response, error ->
            // Extraction is pure and runs off the UI thread; the write-back happens below.
            val extracted = if (error == null && response != null) {
                runCatching { ResponseExtractorEngine.extract(response, extractorsSnapshot) }
                    .getOrElse { emptyList() }
            } else {
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater {
                if (inFlight !== future) return@invokeLater
                inFlight = null
                activeSendId = null
                val isCurrent = currentRequest?.id == request.id
                if (isCurrent) editorPanel.setSending(false)
                if (project.isDisposed) return@invokeLater

                if (error != null) {
                    val cause = HttpTransport.unwrap(error)
                    if (cause is CancellationException) {
                        if (isCurrent) responsePanel.showCancelled()
                        return@invokeLater
                    }
                    val message = HttpTransport.describeError(error)
                    val hint = if (
                        message.contains("refused", ignoreCase = true) ||
                        message.contains("Could not connect", ignoreCase = true)
                    ) {
                        "\n\nCheck that the application is running and the environment's host address is correct."
                    } else {
                        ""
                    }
                    val fullMessage = "$message$hint"
                    cacheResponse(request.id, null, fullMessage)
                    if (isCurrent) {
                        editorPanel.setStatusWarning(message)
                        responsePanel.showError(fullMessage)
                        editorPanel.setExtractionReport(null)
                    }
                    recordHistory(
                        requestSnapshot,
                        prepared.request.url,
                        environmentName,
                        response = null,
                        errorMessage = fullMessage,
                        durationMs = System.currentTimeMillis() - startedAt
                    )
                } else if (response != null) {
                    val report = if (extracted.isEmpty()) null else applyExtractedValues(environmentId, extracted)
                    cacheResponse(request.id, response, null, report)
                    if (isCurrent) {
                        responsePanel.show(response)
                        responsePanel.setExtractionReport(report)
                        editorPanel.setExtractionReport(report)
                        if (!response.successful) {
                            editorPanel.setStatusWarning("HTTP ${response.statusCode} ${response.statusText}".trim())
                        }
                    }
                    recordHistory(
                        requestSnapshot,
                        prepared.request.url,
                        environmentName,
                        response = response,
                        errorMessage = null,
                        durationMs = response.durationMs
                    )
                }
            }
        }
    }

    /**
     * Writes the values captured from [extracted] into [environmentId] (or the active environment
     * when that one is gone) and reports what changed. Must run on the UI thread because it mutates
     * project state and notifies the listeners.
     */
    private fun applyExtractedValues(
        environmentId: String?,
        extracted: List<ExtractedValue>
    ): ExtractionReport {
        if (extracted.isEmpty()) return ExtractionReport.EMPTY
        val environment = service.state().environments.firstOrNull { it.id == environmentId }
            ?: service.activeEnvironment()
        val report = ResponseExtractorEngine.apply(environment, extracted)
        if (report.successCount > 0) service.notifyChanged()
        return report
    }

    /** Runs the Extract rules of the open request against its cached response. */
    private fun runExtractionForCurrentRequest() {
        val request = currentRequest ?: return
        if (request.extractors.none { it.enabled && !it.isBlank() }) {
            editorPanel.setStatusWarning("Add at least one Extract rule first.")
            return
        }
        val response = responseCache[request.id]?.response
        if (response == null) {
            editorPanel.setStatusWarning("Send the request once, then the rules can run against that response.")
            return
        }
        val report = try {
            applyExtractedValues(
                service.activeEnvironment()?.id,
                ResponseExtractorEngine.extract(response, request.extractors.toList())
            )
        } catch (e: Exception) {
            editorPanel.setStatusWarning(e.message ?: "Extract failed.")
            return
        }
        cacheResponse(request.id, response, null, report)
        responsePanel.setExtractionReport(report)
        editorPanel.setExtractionReport(report)
        editorPanel.setStatusWarning(
            if (report.failureCount > 0) "${report.failureCount} Extract rule(s) failed." else null
        )
    }

    private fun recordHistory(
        request: HttpRequestConfig,
        finalUrl: String,
        environmentName: String,
        response: HttpResponseData?,
        errorMessage: String?,
        durationMs: Long
    ) {
        service.addHistory(
            RequestHistoryEntry().apply {
                timestamp = System.currentTimeMillis()
                requestName = request.name
                method = request.method
                url = finalUrl
                this.environmentName = environmentName
                statusCode = response?.statusCode ?: 0
                statusText = response?.statusText.orEmpty()
                this.durationMs = durationMs
                responseSize = response?.bodyBytes ?: 0
                this.errorMessage = errorMessage
                this.request = request.deepCopy()
            }
        )
    }

    private fun cancelRequest() {
        val future = inFlight ?: return
        val requestId = activeSendId
        inFlight = null
        activeSendId = null
        future.cancel(true)
        if (currentRequest?.id == requestId) {
            editorPanel.setSending(false)
            responsePanel.showCancelled()
        }
    }

    private fun importSpringEndpoints(groupId: String?) {
        val scanTask = object : Task.Backgroundable(project, "Scanning Spring Controllers", true) {
            private var scanResult: SpringScanResult? = null

            override fun run(indicator: ProgressIndicator) {
                val result = ReadAction.nonBlocking<SpringScanResult> {
                    SpringEndpointScanner.scan(project, indicator)
                }.executeSynchronously()
                if (!indicator.isCanceled) {
                    scanResult = result
                }
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                val result = scanResult ?: return
                if (!result.springDetected) {
                    notify(
                        "Spring Web annotations were not found on this project's classpath. " +
                                "Add spring-web or spring-boot-starter-web, then try again.",
                        NotificationType.WARNING
                    )
                    return
                }
                if (result.endpoints.isEmpty()) {
                    notify("No Spring controller endpoints were found in this project.", NotificationType.INFORMATION)
                    return
                }
                applySpringEndpoints(result, groupId)
            }

            override fun onThrowable(error: Throwable) {
                Logger.getInstance(SmartRequestPanel::class.java)
                    .warn("Failed to scan Spring controllers", error)
                notify("Could not scan Spring controllers: ${HttpTransport.describeError(error)}", NotificationType.ERROR)
            }
        }
        scanTask.queue()
    }

    private fun applySpringEndpoints(result: SpringScanResult, groupId: String?) {
        val targetGroup = groupId?.let { service.findGroup(it) }
            ?: service.findGroupByName(SPRING_GROUP_NAME)
            ?: service.createGroupIfMissing(SPRING_GROUP_NAME)
        targetGroup.expanded = true
        val existing = targetGroup.requests.filter { it.springKey != null }.associateBy { it.springKey }
        var added = 0
        result.endpoints.forEach { endpoint ->
            val request = existing[endpoint.springKey]
            if (request == null) {
                targetGroup.requests.add(
                    HttpRequestConfig().apply {
                        name = endpoint.displayName
                        method = endpoint.httpMethod
                        url = "{{baseUrl}}${endpoint.path}"
                        springKey = endpoint.springKey
                    }
                )
                added++
            } else {
                request.name = endpoint.displayName
                request.method = endpoint.httpMethod
                request.url = "{{baseUrl}}${endpoint.path}"
            }
        }
        service.notifyChanged()
        val firstId = targetGroup.requests.firstOrNull { it.springKey != null }?.id
        treePanel.rebuild(firstId)
        notify(
            "Imported ${result.endpoints.size} Spring endpoint(s): $added new, " +
                    "${result.endpoints.size - added} updated.",
            NotificationType.INFORMATION
        )
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Smart Request")
            .createNotification("Smart Request", message, type)
            .notify(project)
    }

    override fun dispose() {
        service.removeChangeListener(stateListener)
        inFlight?.cancel(true)
        inFlight = null
        activeSendId = null
        scenarioPanel?.let { Disposer.dispose(it) }
        scenarioPanel = null
        openScenarioId = null
        scenarioListPanel.dispose()
    }

    // Test hooks: inspect the embedded scenario pane without opening a window.

    internal fun scenarioListForTest(): ScenarioListPanel = scenarioListPanel

    internal fun scenarioPanelForTest(): ScenarioEditorPanel? = scenarioPanel

    internal fun contentCardForTest(): String = currentContentCard

    private data class CachedResponse(
        val response: HttpResponseData?,
        val error: String?,
        val report: ExtractionReport? = null
    )

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_EDITOR = "editor"
        private const val CARD_REQUEST = "request"
        private const val CARD_SCENARIO = "scenario"
        private const val SPRING_GROUP_NAME = "Spring Controllers"
        private const val HISTORY_GROUP_NAME = "History"
        private const val RESPONSE_CACHE_SIZE = 10
    }
}
