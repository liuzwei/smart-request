package dev.smartrequest.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.extract.ExtractionReport
import dev.smartrequest.http.QueryParams
import dev.smartrequest.model.BodyType
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.spring.SpringRequestBodyGenerator
import dev.smartrequest.util.JsonPrettyPrinter
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class RequestEditorPanel(
    private val onSend: (HttpRequestConfig) -> Unit,
    private val onCancel: () -> Unit,
    private val onNameChanged: () -> Unit,
    private val variablesProvider: () -> Map<String, String>,
    private val onRunExtract: () -> Unit = {}
) : JBPanel<RequestEditorPanel>(BorderLayout()) {

    private var current: HttpRequestConfig? = null
    private var binding = false
    private var sending = false

    /**
     * Guards the two-way sync between the URL field and the parameter table: while one side is being
     * written from the other, its change listener must not push the value back.
     */
    private var syncingQuery = false

    private var globalHeaders: List<KeyValue> = emptyList()
    private var globalParams: List<KeyValue> = emptyList()

    private val nameField = JBTextField().apply {
        font = font.deriveFont(Font.BOLD, font.size2D + 1f)
    }
    private val methodCombo = JComboBox(HttpRequestConfig.HTTP_METHODS.toTypedArray()).apply {
        preferredSize = Dimension(JBUI.scale(96), preferredSize.height)
        maximumRowCount = HttpRequestConfig.HTTP_METHODS.size
    }
    private val urlField = VariableTextField(variablesProvider, highlightPathParameters = true)
    private val sendButton = JButton("Send", AllIcons.Actions.Execute)
    private val progress = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
        preferredSize = Dimension(JBUI.scale(72), JBUI.scale(4))
        border = BorderFactory.createEmptyBorder()
    }
    private val hintLabel = JBLabel(
        "{{baseUrl}} vars · globals locked · Ctrl+Enter"
    ).apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private val paramsHint = JBLabel("Request params override globals.").apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val headersHint = JBLabel("Request headers override globals.").apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val useGlobalParamsCheck = JCheckBox("Use global params", true).apply {
        toolTipText = "Send the active environment's global query params with this request. " +
                "Uncheck it for requests that run before the globals' variables exist, such as login."
    }
    private val useGlobalHeadersCheck = JCheckBox("Use global headers", true).apply {
        toolTipText = "Send the active environment's global headers with this request. " +
                "Uncheck it for requests that run before the globals' variables exist, such as login."
    }

    private val pathPanel = PathParameterPanel(variablesProvider, onChanged = { updateTabTitles() })
    private val paramsPanel = KeyValueTablePanel(
        onChanged = { onParamsChanged() },
        variablesProvider = variablesProvider
    )
    private val headersPanel = KeyValueTablePanel(
        onChanged = { updateTabTitles() },
        variablesProvider = variablesProvider,
        keySuggestions = dev.smartrequest.http.HttpHeaderNames.COMMON
    )
    private val extractorsPanel: ExtractorTablePanel = ExtractorTablePanel(
        onChanged = { updateTabTitles() },
        onRun = { runExtractNow() },
        variableNamesProvider = {
            variablesProvider().keys
                .filter { it != dev.smartrequest.extract.ResponseExtractorEngine.RESERVED_VARIABLE }
                .sorted()
        }
    )
    private val tabs = JTabbedPane()

    private val bodyTypeCombo = JComboBox(BodyType.entries.map { it.label }.toTypedArray())
    private val bodyArea = VariableTextArea(variablesProvider).apply {
        lineWrap = false
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        border = JBUI.Borders.empty(6)
    }
    private val bodyHint = JBLabel("Select a body type to send a request body.").apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    init {
        border = JBUI.Borders.empty(10, 12, 8, 12)
        add(buildHeader(), BorderLayout.NORTH)
        add(buildTabs(), BorderLayout.CENTER)

        installListeners()
        installSendShortcut()
        setEditorEnabled(false)
        updateTabTitles()
    }

    private fun buildHeader(): JComponent {
        val header = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(8)
        }
        val gbc = GridBagConstraints()
        gbc.insets = Insets(0, 0, JBUI.scale(6), JBUI.scale(6))
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.anchor = GridBagConstraints.WEST

        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        header.add(JBLabel("Name"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.gridwidth = 2
        header.add(nameField, gbc)

        gbc.gridwidth = 1
        gbc.gridy = 1
        gbc.gridx = 0
        gbc.weightx = 0.0
        header.add(methodCombo, gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        header.add(urlField, gbc)
        gbc.gridx = 2
        gbc.weightx = 0.0
        header.add(sendButton, gbc)

        gbc.gridy = 2
        gbc.gridx = 0
        gbc.gridwidth = 3
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        header.add(hintLabel, gbc)

        gbc.gridy = 3
        gbc.gridx = 1
        gbc.gridwidth = 2
        gbc.insets = Insets(0, 0, 0, 0)
        header.add(progress, gbc)

        sendButton.addActionListener {
            if (sending) {
                onCancel()
            } else {
                sendCurrent()
            }
        }
        return header
    }

    private fun sendCurrent() {
        commitEdits()
        current?.let { onSend(it) }
    }

    private fun commitEdits() {
        pathPanel.commitPendingEdit()
        current?.let { pathPanel.syncFromUrl(it.url) }
        paramsPanel.commitPendingEdit()
        headersPanel.commitPendingEdit()
        extractorsPanel.commitPendingEdit()
    }

    private fun onParamsChanged() {
        updateTabTitles()
        pushParamsToUrl()
    }

    /**
     * URL field to parameter table. The URL wins for every enabled row, while disabled rows are kept
     * because they are intentionally left out of the URL.
     */
    private fun syncParamsFromUrl() {
        if (binding || syncingQuery) return
        val request = current ?: return
        val rows = QueryParams.rowsFromUrl(request.url, request.params)
        if (rows.map { it.key to it.value } == request.params.map { it.key to it.value }) return
        request.params.clear()
        request.params.addAll(rows)
        syncingQuery = true
        try {
            paramsPanel.bind(request.params, globalParams.filter { it.enabled })
        } finally {
            syncingQuery = false
        }
        updateTabTitles()
    }

    /** Parameter table to URL field: the enabled rows always describe the query of the URL. */
    private fun pushParamsToUrl() {
        if (binding || syncingQuery) return
        val request = current ?: return
        val url = QueryParams.replaceQuery(request.url, QueryParams.pairsForUrl(request.params))
        if (url == request.url && url == urlField.text) return
        syncingQuery = true
        try {
            request.url = url
            if (urlField.text != url) urlField.text = url
            pathPanel.syncFromUrl(url)
        } finally {
            syncingQuery = false
        }
    }

    private fun buildTabs(): JComponent {
        tabs.addTab("Params", tableTab(paramsHint, paramsPanel, useGlobalParamsCheck))
        tabs.addTab("Path Params", pathPanel)
        tabs.addTab("Headers", tableTab(headersHint, headersPanel, useGlobalHeadersCheck))
        tabs.addTab("Body", buildBodyPanel())
        tabs.addTab("Extract (0)", buildExtractPanel())
        return tabs
    }

    private fun buildExtractPanel(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.empty(8, 6)
        add(extractorsPanel, BorderLayout.CENTER)
    }

    private fun tableTab(
        hint: JBLabel,
        table: KeyValueTablePanel,
        useGlobals: JCheckBox
    ): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.empty(8, 6)
            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(hint, BorderLayout.WEST)
                add(useGlobals, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(table, BorderLayout.CENTER)
        }
        return panel
    }

    private fun buildBodyPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8, 6)
        }

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JBLabel("Type"))
            add(bodyTypeCombo)
            add(JButton("From Params").apply {
                toolTipText = "Generate a JSON body from enabled query parameters"
                addActionListener { generateBodyFromParams() }
            })
            add(JButton("Format JSON").apply {
                addActionListener { formatJsonBody() }
            })
        }
        panel.add(controls, BorderLayout.NORTH)
        panel.add(JBScrollPane(bodyArea).apply {
            border = JBUI.Borders.customLine(JBColor.border())
        }, BorderLayout.CENTER)
        panel.add(bodyHint, BorderLayout.SOUTH)
        return panel
    }

    private fun installListeners() {
        nameField.document.addDocumentListener(simpleListener {
            val request = current ?: return@simpleListener
            if (binding) return@simpleListener
            request.name = nameField.text
            onNameChanged()
        })
        urlField.document.addDocumentListener(simpleListener {
            val request = current ?: return@simpleListener
            if (binding) return@simpleListener
            request.url = urlField.text
            pathPanel.syncFromUrl(request.url)
            syncParamsFromUrl()
        })
        methodCombo.addActionListener {
            if (!binding) {
                current?.method = methodCombo.selectedItem?.toString() ?: "GET"
            }
        }
        bodyTypeCombo.addActionListener {
            if (binding) return@addActionListener
            val type = BodyType.entries.getOrNull(bodyTypeCombo.selectedIndex) ?: BodyType.NONE
            current?.bodyType = type.name
            bodyArea.isEnabled = type != BodyType.NONE && current != null
            bodyHint.text = when (type) {
                BodyType.NONE -> "No request body will be sent."
                BodyType.JSON -> "JSON body. Content-Type defaults to application/json."
                BodyType.TEXT -> "Plain text body. Content-Type defaults to text/plain."
                BodyType.XML -> "XML body. Content-Type defaults to application/xml."
                BodyType.FORM -> "Form body, for example: name=value&page=1"
            }
        }
        bodyArea.document.addDocumentListener(simpleListener {
            val request = current ?: return@simpleListener
            if (!binding) request.body = bodyArea.text
        })
        useGlobalParamsCheck.addActionListener {
            if (binding) return@addActionListener
            val request = current ?: return@addActionListener
            request.useGlobalParams = useGlobalParamsCheck.isSelected
            paramsPanel.refreshFixed(effectiveGlobalParams(request))
            updateGlobalHints()
            updateTabTitles()
        }
        useGlobalHeadersCheck.addActionListener {
            if (binding) return@addActionListener
            val request = current ?: return@addActionListener
            request.useGlobalHeaders = useGlobalHeadersCheck.isSelected
            headersPanel.refreshFixed(effectiveGlobalHeaders(request))
            updateGlobalHints()
            updateTabTitles()
        }
    }

    private fun installSendShortcut() {
        val key = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, "smartRequest.send")
        actionMap.put("smartRequest.send", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                if (!sending) sendCurrent()
            }
        })
    }

    fun open(request: HttpRequestConfig) {
        // Commit a cell that is still being edited before the table is rebound to another request.
        extractorsPanel.commitPendingEdit()
        current = request
        binding = true
        try {
            // Requests saved before the URL and the parameter table were kept in sync may carry the
            // query in only one of the two places: merge both and normalise the URL.
            val rows = QueryParams.rowsFromUrl(request.url, request.params, keepUnknown = true)
            request.params.clear()
            request.params.addAll(rows)
            request.url = QueryParams.replaceQuery(request.url, QueryParams.pairsForUrl(request.params))

            nameField.text = request.name
            methodCombo.selectedItem = request.method
            urlField.text = request.url
            useGlobalParamsCheck.isSelected = request.useGlobalParams
            useGlobalHeadersCheck.isSelected = request.useGlobalHeaders
            pathPanel.bind(request)
            paramsPanel.bind(request.params, effectiveGlobalParams(request))
            headersPanel.bind(request.headers, effectiveGlobalHeaders(request))
            extractorsPanel.bind(request.extractors)
            val type = request.bodyTypeEnum()
            bodyTypeCombo.selectedIndex = BodyType.entries.indexOf(type)
            bodyArea.text = request.body
            bodyArea.isEnabled = type != BodyType.NONE
            bodyHint.text = when (type) {
                BodyType.NONE -> "No request body will be sent."
                BodyType.JSON -> "JSON body. Content-Type defaults to application/json."
                BodyType.TEXT -> "Plain text body. Content-Type defaults to text/plain."
                BodyType.XML -> "XML body. Content-Type defaults to application/xml."
                BodyType.FORM -> "Form body, for example: name=value&page=1"
            }
            setStatusWarning(null)
        } finally {
            binding = false
        }
        setEditorEnabled(true)
        updateGlobalHints()
        updateTabTitles()
        urlField.caretPosition = 0
    }

    fun setGlobals(headers: List<KeyValue>, params: List<KeyValue>) {
        globalHeaders = headers
        globalParams = params
        refreshGlobalRows()
        updateGlobalHints()
        updateTabTitles()
    }

    fun selectTab(index: Int) {
        if (index in 0 until tabs.tabCount) tabs.selectedIndex = index
    }

    private fun effectiveGlobalParams(request: HttpRequestConfig): List<KeyValue> =
        if (request.useGlobalParams) globalParams.filter { it.enabled } else emptyList()

    private fun effectiveGlobalHeaders(request: HttpRequestConfig): List<KeyValue> =
        if (request.useGlobalHeaders) globalHeaders.filter { it.enabled } else emptyList()

    /** Re-reads the fixed rows after the environment globals changed. */
    private fun refreshGlobalRows() {
        val request = current ?: return
        paramsPanel.refreshFixed(effectiveGlobalParams(request))
        headersPanel.refreshFixed(effectiveGlobalHeaders(request))
    }

    private fun updateGlobalHints() {
        val request = current
        val paramsOn = request?.useGlobalParams ?: true
        val headersOn = request?.useGlobalHeaders ?: true
        paramsHint.text = if (paramsOn) {
            "Request params override globals."
        } else {
            "Global params are disabled for this request."
        }
        headersHint.text = if (headersOn) {
            "Request headers override globals."
        } else {
            "Global headers are disabled for this request."
        }
    }

    fun refreshVariables() {
        urlField.refreshVariables()
        bodyArea.refreshVariables()
        pathPanel.refreshVariables()
        paramsPanel.refreshVariableHighlighting()
        headersPanel.refreshVariableHighlighting()
    }

    /** Shows what the last run of this request's Extract rules changed. */
    fun setExtractionReport(report: ExtractionReport?) {
        extractorsPanel.setResults(report)
    }

    private fun runExtractNow() {
        extractorsPanel.commitPendingEdit()
        onRunExtract()
    }

    fun focusUrl() {
        urlField.requestFocusInWindow()
        urlField.selectAll()
    }

    fun setSending(value: Boolean) {
        sending = value
        sendButton.text = if (value) "Cancel" else "Send"
        sendButton.icon = if (value) null else AllIcons.Actions.Execute
        progress.isVisible = value
        if (!value) setStatusWarning(null)
    }

    fun setStatusWarning(message: String?) {
        if (message == null) {
            hintLabel.text = "{{baseUrl}} vars · globals locked · Ctrl+Enter"
            hintLabel.foreground = UIUtil.getContextHelpForeground()
            hintLabel.toolTipText = null
        } else {
            hintLabel.text = message
            hintLabel.foreground = JBColor.RED
            hintLabel.toolTipText = message
        }
    }

    fun updateGlobalCounts(headers: List<KeyValue>, params: List<KeyValue>) {
        globalHeaders = headers
        globalParams = params
        refreshGlobalRows()
        updateGlobalHints()
        updateTabTitles()
    }

    // Test hooks: drive the editor the way a user would, without dispatching input events.

    internal fun urlFieldText(): String = urlField.text

    internal fun setUrlFieldText(text: String) {
        urlField.text = text
    }

    internal fun parameterPairs(): List<Pair<String, String>> =
        current?.params?.map { it.key to it.value }.orEmpty()

    internal fun paramsTable(): KeyValueTablePanel = paramsPanel

    internal fun headersTable(): KeyValueTablePanel = headersPanel

    internal fun useGlobalParamsCheckbox(): JCheckBox = useGlobalParamsCheck

    internal fun useGlobalHeadersCheckbox(): JCheckBox = useGlobalHeadersCheck

    internal fun pathParameterCount(): Int = pathPanel.count()

    private fun updateTabTitles() {
        val request = current ?: return
        tabs.setTitleAt(0, "Params (${request.params.size + paramsPanel.fixedCount()})")
        tabs.setTitleAt(1, "Path Params (${pathPanel.count()})")
        tabs.setTitleAt(2, "Headers (${request.headers.size + headersPanel.fixedCount()})")
        tabs.setTitleAt(3, "Body")
        tabs.setTitleAt(4, "Extract (${request.extractors.size})")
    }

    private fun setEditorEnabled(enabled: Boolean) {
        nameField.isEnabled = enabled
        methodCombo.isEnabled = enabled
        urlField.isEnabled = enabled
        sendButton.isEnabled = enabled
        tabs.isEnabled = enabled
        useGlobalParamsCheck.isEnabled = enabled
        useGlobalHeadersCheck.isEnabled = enabled
        extractorsPanel.setEnabledState(enabled)
        bodyArea.isEnabled = enabled && current?.bodyTypeEnum() != BodyType.NONE
        if (!enabled) {
            nameField.text = ""
            urlField.text = ""
            bodyArea.text = ""
        }
    }

    private fun formatJsonBody() {
        val formatted = JsonPrettyPrinter.prettyPrintOrNull(bodyArea.text)
        if (formatted == null) {
            setStatusWarning("Body is not valid JSON.")
        } else {
            bodyArea.text = formatted
            setStatusWarning(null)
        }
    }

    private fun generateBodyFromParams() {
        val request = current ?: return
        val params = request.params.filter { it.enabled && it.key.isNotBlank() }
        if (params.isEmpty()) {
            setStatusWarning("Add at least one enabled query parameter first.")
            return
        }
        bodyTypeCombo.selectedIndex = BodyType.entries.indexOf(BodyType.JSON)
        bodyArea.isEnabled = true
        bodyArea.text = SpringRequestBodyGenerator.fromParams(params)
        setStatusWarning(null)
    }

    private fun simpleListener(action: () -> Unit): DocumentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = action()
        override fun removeUpdate(e: DocumentEvent) = action()
        override fun changedUpdate(e: DocumentEvent) = action()
    }
}
