package dev.smartrequest.ui

import com.intellij.json.JsonFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
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
import dev.smartrequest.http.HttpResponseData
import dev.smartrequest.http.HttpTransport
import dev.smartrequest.util.JsonPrettyPrinter
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.Locale
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

class ResponsePanel(
    private val project: Project
) : JBPanel<ResponsePanel>(BorderLayout()) {

    private val statusLabel = JBLabel("No response yet").apply {
        font = font.deriveFont(Font.BOLD)
        foreground = UIUtil.getContextHelpForeground()
    }
    private val metaLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val copyButton = JButton("Copy").apply {
        isEnabled = false
        addActionListener { copyBody() }
    }
    private val prettyCheck = JCheckBox("Pretty JSON", true).apply {
        isOpaque = false
        isEnabled = false
        addActionListener { renderBody() }
    }
    private val wrapCheck = JCheckBox("Wrap lines", true).apply {
        isOpaque = false
        addActionListener { applyWrap() }
    }
    private val searchButton = JButton("Search").apply {
        toolTipText = "Search response body (Ctrl+F)"
        addActionListener { toggleSearch() }
    }
    private val foldButton = JButton("Fold").apply {
        toolTipText = "Fold / unfold JSON structures"
        isEnabled = false
        addActionListener { toggleFolding() }
    }
    private val searchField = JBTextField().apply {
        preferredSize = Dimension(JBUI.scale(180), preferredSize.height)
        addActionListener { selectMatch(currentMatch + 1) }
    }
    private val searchStatus = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val searchPrev = JButton("Prev").apply {
        isEnabled = false
        addActionListener { selectMatch(currentMatch - 1) }
    }
    private val searchNext = JButton("Next").apply {
        isEnabled = false
        addActionListener { selectMatch(currentMatch + 1) }
    }
    private val searchClose = JButton("×").apply {
        toolTipText = "Close search"
        addActionListener { hideSearch() }
    }
    private val searchBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        isOpaque = false
        add(JBLabel("Find"))
        add(searchField)
        add(searchPrev)
        add(searchNext)
        add(searchStatus)
        add(searchClose)
        isVisible = false
    }
    private val contentTypeLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val tabs = JTabbedPane()

    private val bodyEditor = EditorTextField("", project, PlainTextFileType.INSTANCE).apply {
        setOneLineMode(false)
        setViewer(true)
        border = JBUI.Borders.customLine(JBColor.border())
        // EditorTextField hides both scrollbars of its embedded editor, and Swing ignores mouse
        // wheel events when no scrollbar is visible. Long response bodies were therefore stuck at
        // the top and could not be scrolled at all. Show the scrollbars again when they are needed.
        addSettingsProvider { editor ->
            editor.setVerticalScrollbarVisible(true)
            responseEditorScrollPane(editor)?.let { scrollPane ->
                scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            }
        }
    }

    private val headersModel = HeadersModel()
    private val headersTable = JBTable(headersModel).apply {
        rowHeight = JBUI.scale(24)
        autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        columnModel.getColumn(0).preferredWidth = JBUI.scale(220)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(480)
    }

    private var lastResponse: HttpResponseData? = null
    private var matches: List<IntRange> = emptyList()
    private var currentMatch = -1
    private val searchHighlighters = ArrayList<RangeHighlighter>()

    /** The response currently displayed, if any; used by the scenario response viewer tests. */
    internal fun responseForTest(): HttpResponseData? = lastResponse
    private val manualFoldRegions = ArrayList<com.intellij.openapi.editor.FoldRegion>()
    private val extractionModel = ExtractionModel()
    private val extractionTable = JBTable(extractionModel).apply {
        rowHeight = JBUI.scale(24)
        autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        columnModel.getColumn(0).preferredWidth = JBUI.scale(200)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(440)
        columnModel.getColumn(2).preferredWidth = JBUI.scale(160)
        setDefaultRenderer(String::class.java, ExtractionCellRenderer())
    }
    private val extractionHint = JBLabel(NO_EXTRACT_HINT).apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private var searchDispatcherInstalled = false
    private val searchKeyDispatcher = KeyEventDispatcher { event ->
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (event.id == KeyEvent.KEY_PRESSED &&
            event.isControlDown &&
            event.keyCode == KeyEvent.VK_F &&
            focusOwner != null &&
            SwingUtilities.isDescendingFrom(focusOwner, bodyEditor)
        ) {
            showSearch()
            true
        } else {
            false
        }
    }

    init {
        border = JBUI.Borders.empty(8, 12, 10, 12)
        add(buildHeader(), BorderLayout.NORTH)
        add(buildTabs(), BorderLayout.CENTER)
        installEditorShortcuts()
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applySearch()
            override fun removeUpdate(e: DocumentEvent) = applySearch()
            override fun changedUpdate(e: DocumentEvent) = applySearch()
        })
        showEmptyState()
    }

    override fun addNotify() {
        super.addNotify()
        if (!searchDispatcherInstalled) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(searchKeyDispatcher)
            searchDispatcherInstalled = true
        }
    }

    override fun removeNotify() {
        if (searchDispatcherInstalled) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(searchKeyDispatcher)
            searchDispatcherInstalled = false
        }
        super.removeNotify()
    }

    private fun buildHeader(): JComponent {
        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(JBLabel("Response").apply { font = font.deriveFont(Font.BOLD) })
            add(statusLabel)
            add(metaLabel)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(copyButton)
        }
        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)
        return header
    }

    private fun buildTabs(): JComponent {
        val bodyPanel = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.emptyTop(4)
        }
        val bodyToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(searchButton)
            add(foldButton)
            add(prettyCheck)
            add(wrapCheck)
            add(contentTypeLabel)
        }
        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(bodyToolbar, BorderLayout.NORTH)
            add(searchBar, BorderLayout.SOUTH)
        }
        bodyPanel.add(header, BorderLayout.NORTH)
        bodyPanel.add(bodyEditor, BorderLayout.CENTER)
        tabs.addTab("Body", bodyPanel)
        tabs.addTab("Headers (0)", JBScrollPane(headersTable).apply {
            border = JBUI.Borders.customLine(JBColor.border())
        })
        tabs.addTab("Variables (0)", JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.emptyTop(4)
            add(extractionHint, BorderLayout.NORTH)
            add(JBScrollPane(extractionTable).apply {
                border = JBUI.Borders.customLine(JBColor.border())
            }, BorderLayout.CENTER)
        })
        return tabs
    }

    /**
     * Shows what the Extract rules of the request that produced this response captured. This tab is
     * read-only on purpose: the rules themselves belong to the request and are edited in the Extract
     * tab of the request editor.
     */
    fun setExtractionReport(report: ExtractionReport?) {
        val results = report?.results.orEmpty()
        extractionModel.setRows(results)
        if (tabs.tabCount > VARIABLES_TAB) {
            tabs.setTitleAt(VARIABLES_TAB, "Variables (${results.size})")
        }
        extractionHint.text = when {
            report == null -> NO_EXTRACT_HINT
            results.isEmpty() -> "No value was extracted from this response."
            report.failureCount > 0 -> "${report.successCount} captured, ${report.failureCount} failed."
            else -> "${results.size} value(s) written to the active environment."
        }
    }

    private fun installEditorShortcuts() {
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    hideSearch()
                } else if (e.keyCode == KeyEvent.VK_ENTER) {
                    selectMatch(currentMatch + if (e.isShiftDown) -1 else 1)
                }
            }
        })
    }

    fun showEmptyState() {
        lastResponse = null
        statusLabel.text = "No response yet"
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        metaLabel.text = ""
        contentTypeLabel.text = ""
        setBodyText("Send a request to see the response here.", json = false)
        prettyCheck.isEnabled = false
        copyButton.isEnabled = false
        headersModel.setRows(emptyList())
        tabs.setTitleAt(1, "Headers (0)")
        setExtractionReport(null)
        tabs.selectedIndex = 0
        hideSearch()
        foldButton.isEnabled = false
        foldButton.text = "Fold"
    }

    fun showLoading() {
        lastResponse = null
        statusLabel.text = "Sending request…"
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        metaLabel.text = ""
        contentTypeLabel.text = ""
        setBodyText("", json = false)
        prettyCheck.isEnabled = false
        copyButton.isEnabled = false
        headersModel.setRows(emptyList())
        tabs.setTitleAt(1, "Headers (0)")
        setExtractionReport(null)
        tabs.selectedIndex = 0
        hideSearch()
        foldButton.isEnabled = false
        foldButton.text = "Fold"
    }

    fun show(response: HttpResponseData) {
        lastResponse = response
        setExtractionReport(null)
        statusLabel.text = buildString {
            append(response.statusCode)
            if (response.statusText.isNotEmpty()) append(' ').append(response.statusText)
        }
        statusLabel.foreground = when {
            response.statusCode in 200..299 -> SUCCESS_COLOR
            response.statusCode in 300..399 -> INFO_COLOR
            response.statusCode in 400..499 -> WARNING_COLOR
            else -> ERROR_COLOR
        }
        metaLabel.text = buildString {
            append(response.durationMs).append(" ms")
            append(" · ")
            append(formatBytes(response.bodyBytes))
            if (response.truncated) append(" (truncated)")
        }
        contentTypeLabel.text = response.contentType?.substringBefore(';')?.trim().orEmpty()
        contentTypeLabel.toolTipText = response.contentType
        prettyCheck.isEnabled = response.isJson && response.textual
        if (prettyCheck.isEnabled) prettyCheck.isSelected = true
        copyButton.isEnabled = true
        headersModel.setRows(response.headers)
        tabs.setTitleAt(1, "Headers (${response.headers.size})")
        renderBody()
        updateFoldButton()
    }

    fun showError(message: String) {
        lastResponse = null
        statusLabel.text = "Request failed"
        statusLabel.foreground = ERROR_COLOR
        metaLabel.text = ""
        contentTypeLabel.text = ""
        prettyCheck.isEnabled = false
        copyButton.isEnabled = true
        headersModel.setRows(emptyList())
        tabs.setTitleAt(1, "Headers (0)")
        setExtractionReport(null)
        wrapCheck.isSelected = true
        setBodyText(message, json = false)
        tabs.selectedIndex = 0
        hideSearch()
        foldButton.isEnabled = false
        foldButton.text = "Fold"
    }

    fun showCancelled() {
        lastResponse = null
        statusLabel.text = "Cancelled"
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        metaLabel.text = ""
        contentTypeLabel.text = ""
        prettyCheck.isEnabled = false
        copyButton.isEnabled = false
        setBodyText("Request cancelled.", json = false)
        headersModel.setRows(emptyList())
        tabs.setTitleAt(1, "Headers (0)")
        setExtractionReport(null)
        tabs.selectedIndex = 0
        hideSearch()
        foldButton.isEnabled = false
        foldButton.text = "Fold"
    }

    private fun renderBody() {
        val response = lastResponse ?: return
        if (!response.textual) {
            setBodyText(
                "Binary response (${formatBytes(response.bodyBytes)}).\n" +
                        (response.contentType?.let { "Content-Type: $it\n" } ?: ""),
                json = false
            )
            foldButton.isEnabled = false
            return
        }
        var shown = if (prettyCheck.isSelected && response.isJson) {
            JsonPrettyPrinter.prettyPrintOrNull(response.body) ?: response.body
        } else {
            response.body
        }
        if (response.truncated) {
            shown += "\n\n--- Response truncated at ${formatBytes(HttpTransport.MAX_BODY_BYTES.toLong())} ---"
        }
        setBodyText(shown, json = response.isJson)
    }

    private fun setBodyText(text: String, json: Boolean) {
        bodyEditor.getEditor(true)?.let { clearManualFolds(it) }
        bodyEditor.fileType = if (json) JsonFileType.INSTANCE else PlainTextFileType.INSTANCE
        bodyEditor.text = text
        bodyEditor.setCaretPosition(0)
        applyWrap()
        ApplicationManager.getApplication().invokeLater {
            applyWrap()
            if (json) installManualFolds()
            updateFoldButton()
            applySearch()
        }
    }

    private fun applyWrap() {
        bodyEditor.getEditor(true)?.settings?.isUseSoftWraps = wrapCheck.isSelected
    }

    /**
     * The scroll pane that hosts the editor created by [EditorTextField]. The platform does not
     * expose it directly, so look it up in the editor's component tree; callers must handle null.
     */
    private fun responseEditorScrollPane(editor: Editor): JScrollPane? = findScrollPane(editor.component)

    private fun findScrollPane(component: Component?): JScrollPane? {
        if (component == null) return null
        if (component is JScrollPane) return component
        if (component !is Container) return null
        for (child in component.components) {
            findScrollPane(child)?.let { return it }
        }
        return null
    }

    private fun copyBody() {
        val text = bodyEditor.text
        if (text.isEmpty()) return
        com.intellij.openapi.ide.CopyPasteManager.getInstance()
            .setContents(java.awt.datatransfer.StringSelection(text))
    }

    private fun toggleSearch() {
        if (searchBar.isVisible) {
            hideSearch()
        } else {
            showSearch()
        }
    }

    private fun showSearch() {
        searchBar.isVisible = true
        searchBar.parent?.revalidate()
        searchBar.parent?.repaint()
        searchField.requestFocusInWindow()
        searchField.selectAll()
        applySearch()
    }

    private fun hideSearch() {
        searchBar.isVisible = false
        searchBar.parent?.revalidate()
        searchBar.parent?.repaint()
        clearSearch()
    }

    private fun applySearch() {
        val editor = bodyEditor.getEditor(true) ?: return
        clearSearchHighlighters(editor)
        matches = emptyList()
        currentMatch = -1
        val query = searchField.text
        if (!searchBar.isVisible || query.isEmpty()) {
            updateSearchStatus()
            return
        }
        val text = bodyEditor.text
        val found = ArrayList<IntRange>()
        var index = text.indexOf(query, ignoreCase = true)
        while (index >= 0 && found.size < MAX_SEARCH_MATCHES) {
            found += index until (index + query.length)
            index = text.indexOf(query, index + query.length, ignoreCase = true)
        }
        matches = found
        if (found.isNotEmpty()) {
            editor.foldingModel.runBatchFoldingOperation {
                editor.foldingModel.allFoldRegions.forEach { it.isExpanded = true }
            }
            updateFoldButton()
        }
        val attributes = TextAttributes().apply {
            backgroundColor = SEARCH_HIGHLIGHT
        }
        found.forEach { range ->
            runCatching {
                searchHighlighters += editor.markupModel.addRangeHighlighter(
                    range.first,
                    range.last + 1,
                    HighlighterLayer.SELECTION - 1,
                    attributes,
                    HighlighterTargetArea.EXACT_RANGE
                )
            }
        }
        if (found.isNotEmpty()) {
            currentMatch = 0
            selectMatch(0)
        } else {
            updateSearchStatus()
        }
    }

    private fun selectMatch(index: Int) {
        if (matches.isEmpty()) {
            updateSearchStatus()
            return
        }
        val editor = bodyEditor.getEditor(true) ?: return
        currentMatch = Math.floorMod(index, matches.size)
        val range = matches[currentMatch]
        editor.caretModel.moveToOffset(range.first)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        editor.selectionModel.setSelection(range.first, range.last + 1)
        updateSearchStatus()
    }

    private fun updateSearchStatus() {
        searchStatus.text = when {
            searchField.text.isEmpty() -> ""
            matches.isEmpty() -> "0/0"
            else -> "${currentMatch + 1}/${matches.size}"
        }
        searchPrev.isEnabled = matches.isNotEmpty()
        searchNext.isEnabled = matches.isNotEmpty()
    }

    private fun clearSearch() {
        val editor = bodyEditor.editor
        if (editor != null) {
            clearSearchHighlighters(editor)
            editor.selectionModel.removeSelection()
        }
        matches = emptyList()
        currentMatch = -1
        updateSearchStatus()
    }

    private fun clearSearchHighlighters(editor: com.intellij.openapi.editor.Editor) {
        searchHighlighters.forEach { highlighter ->
            runCatching { editor.markupModel.removeHighlighter(highlighter) }
        }
        searchHighlighters.clear()
    }

    private fun toggleFolding() {
        val editor = bodyEditor.getEditor(true) ?: return
        if (manualFoldRegions.isEmpty()) installManualFolds()
        val regions = editor.foldingModel.allFoldRegions
        if (regions.isEmpty()) return
        val anyCollapsed = regions.any { !it.isExpanded }
        if (!anyCollapsed) {
            val rootStart = regions.minOfOrNull { it.startOffset } ?: return
            editor.foldingModel.runBatchFoldingOperation {
                regions.forEach { it.isExpanded = it.startOffset == rootStart }
            }
        } else {
            editor.foldingModel.runBatchFoldingOperation {
                regions.forEach { it.isExpanded = true }
            }
        }
        updateFoldButton()
    }

    private fun installManualFolds() {
        val editor = bodyEditor.getEditor(true) ?: return
        editor.settings.isLineNumbersShown = true
        editor.settings.isFoldingOutlineShown = true
        clearManualFolds(editor)
        if (editor.foldingModel.allFoldRegions.isNotEmpty()) return
        val text = bodyEditor.document.text
        val ranges = jsonFoldRanges(text)
        if (ranges.isEmpty()) return
        editor.foldingModel.runBatchFoldingOperation {
            ranges.forEach { fold ->
                val region = runCatching {
                    editor.foldingModel.addFoldRegion(fold.start, fold.end, fold.placeholder)
                }.getOrNull()
                if (region != null) {
                    region.isExpanded = true
                    manualFoldRegions += region
                }
            }
        }
    }

    private fun clearManualFolds(editor: com.intellij.openapi.editor.Editor) {
        manualFoldRegions.forEach { region ->
            runCatching { editor.foldingModel.removeFoldRegion(region) }
        }
        manualFoldRegions.clear()
    }

    private data class JsonFold(val start: Int, val end: Int, val placeholder: String)

    private fun jsonFoldRanges(text: String): List<JsonFold> {
        val result = ArrayList<JsonFold>()
        val stack = ArrayDeque<Pair<Int, Char>>()
        var inString = false
        var escaped = false
        text.forEachIndexed { index, c ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(index to c)
                '}', ']' -> {
                    val expected = if (c == '}') '{' else '['
                    if (stack.isNotEmpty() && stack.last().second == expected) {
                        val (start, opening) = stack.removeLast()
                        if (index - start >= MIN_FOLD_LENGTH && text.substring(start, index).contains('\n')) {
                            result += JsonFold(
                                start,
                                index + 1,
                                if (opening == '{') "{…}" else "[…]"
                            )
                        }
                    }
                }
            }
        }
        return result.sortedBy { it.start }
    }

    private fun updateFoldButton() {
        foldButton.isEnabled = lastResponse?.let { it.isJson && it.textual } == true
        val regions = bodyEditor.getEditor(true)?.foldingModel?.allFoldRegions.orEmpty()
        foldButton.text = if (regions.any { !it.isExpanded }) "Unfold" else "Fold"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private class ExtractionModel : AbstractTableModel() {
        private var rows: List<ExtractionResult> = emptyList()

        fun setRows(newRows: List<ExtractionResult>) {
            rows = newRows
            fireTableDataChanged()
        }

        fun rowAt(index: Int): ExtractionResult? = rows.getOrNull(index)

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 3
        override fun getColumnName(column: Int): String = when (column) {
            0 -> "Variable"
            1 -> "Value"
            else -> "Result"
        }

        // Needed for the custom renderer to be picked up: without it JTable falls back to the
        // Object renderer and the failure reason would be replaced by a bare "Failed".
        override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val result = rows.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                0 -> result.variable
                1 -> result.value.orEmpty()
                else -> if (result.successful) (if (result.added) "Added" else "Updated") else "Failed"
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    }

    private inner class ExtractionCellRenderer : JLabel(), TableCellRenderer {
        init {
            isOpaque = true
            border = JBUI.Borders.empty(0, 8)
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val result = extractionModel.rowAt(row)
            background = if (selected) table.selectionBackground else table.background
            if (result == null) {
                text = ""
                toolTipText = null
                return this
            }
            foreground = when {
                selected -> table.selectionForeground
                !result.successful -> ERROR_COLOR
                column == 2 -> SUCCESS_COLOR
                else -> table.foreground
            }
            text = when {
                column == 0 -> result.variable
                column == 1 -> result.value.orEmpty()
                result.successful -> if (result.added) "Added" else "Updated"
                else -> result.error.orEmpty()
            }
            toolTipText = when (column) {
                0 -> result.source
                1 -> result.value
                else -> if (result.successful) {
                    buildString {
                        append(if (result.added) "Added to the environment" else "Updated in the environment")
                        result.previous?.takeIf { it != result.value }?.let { append("\nwas: ").append(it) }
                    }
                } else {
                    result.error
                }
            }
            return this
        }
    }

    private class HeadersModel : AbstractTableModel() {
        private var rows: List<Pair<String, String>> = emptyList()

        fun setRows(newRows: List<Pair<String, String>>) {
            rows = newRows
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = if (column == 0) "Header" else "Value"
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            rows.getOrNull(rowIndex)?.let { if (columnIndex == 0) it.first else it.second }.orEmpty()

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    }

    companion object {
        private const val MAX_SEARCH_MATCHES = 500
        private const val MIN_FOLD_LENGTH = 8
        private const val VARIABLES_TAB = 2
        private const val NO_EXTRACT_HINT =
            "Add rules in the Extract tab of the request to capture values from a response."
        private val SEARCH_HIGHLIGHT = JBColor(0xFFF3B0, 0x4A452A)
        private val SUCCESS_COLOR = JBColor(0x1A7F37, 0x73BD79)
        private val INFO_COLOR = JBColor(0x0B5CAD, 0x6BA6E8)
        private val WARNING_COLOR = JBColor(0xA15C00, 0xD6A26A)
        private val ERROR_COLOR = JBColor(0xC7222D, 0xFF6B68)
    }
}
