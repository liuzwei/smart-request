package dev.smartrequest.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.SmartRequestState
import dev.smartrequest.http.HttpHeaderNames
import dev.smartrequest.service.SmartRequestProjectService
import dev.smartrequest.ui.KeyValueTablePanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.util.UUID
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class EnvironmentDialog(
    project: Project,
    private val state: SmartRequestState,
    private val service: SmartRequestProjectService
) : DialogWrapper(project, true) {

    private val environments = state.environments.map { it.deepCopy() }.toMutableList()
    private val listModel = DefaultListModel<EnvironmentConfig>()
    private val list = JBList(listModel)
    private val nameField = JBTextField()
    private val baseUrlField = JBTextField()
    private val variablesPanel = KeyValueTablePanel(showLegend = false)
    private val globalHeadersPanel = KeyValueTablePanel(
        showLegend = false,
        keySuggestions = HttpHeaderNames.COMMON
    )
    private val globalParamsPanel = KeyValueTablePanel(showLegend = false)
    private val tabs = JTabbedPane()
    private val removeButton = JButton("Remove")
    private var selected: EnvironmentConfig? = null
    private var loading = true

    init {
        title = "Environment Settings"
        setOKButtonText("OK")
        environments.forEach { listModel.addElement(it) }
        init()
        // Open on the environment the toolbar currently points at, not always on the first one.
        val activeId = state.activeEnvironment()?.id
        val activeIndex = environments.indexOfFirst { it.id == activeId }
        if (listModel.size() > 0) {
            list.selectedIndex = if (activeIndex >= 0) activeIndex else 0
        }
        updateButtons()
    }

    override fun createCenterPanel(): JComponent {
        list.cellRenderer = EnvironmentRenderer()
        list.preferredSize = Dimension(JBUI.scale(240), JBUI.scale(400))
        list.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            loadSelected()
        }

        val addButton = JButton("Add")
        val duplicateButton = JButton("Duplicate")
        addButton.addActionListener { addEnvironment() }
        duplicateButton.addActionListener { duplicateEnvironment() }
        removeButton.addActionListener { removeEnvironment() }

        val left = JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.emptyRight(12)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(addButton)
                add(duplicateButton)
                add(removeButton)
            }, BorderLayout.SOUTH)
        }

        nameField.document.addDocumentListener(documentListener {
            if (loading) return@documentListener
            selected?.name = nameField.text
            list.repaint()
        })
        baseUrlField.document.addDocumentListener(documentListener {
            if (loading) return@documentListener
            selected?.baseUrl = baseUrlField.text
            list.repaint()
        })

        val variablesHint = JBLabel(
            "Use {{baseUrl}} in requests. Add variables such as token, tenantCode, or userId."
        ).apply {
            foreground = UIUtil.getContextHelpForeground()
        }
        val headersHint = JBLabel(
            "Sent with every request in this environment; request-level headers win."
        ).apply {
            foreground = UIUtil.getContextHelpForeground()
        }
        val paramsHint = JBLabel(
            "Added to every request URL in this environment; request-level params win."
        ).apply {
            foreground = UIUtil.getContextHelpForeground()
        }

        if (tabs.tabCount == 0) {
            tabs.addTab("Variables", tableTab(variablesHint, variablesPanel))
            tabs.addTab("Global Headers", tableTab(headersHint, globalHeadersPanel))
            tabs.addTab("Global Params", tableTab(paramsHint, globalParamsPanel))
        }
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Environment name").apply { font = font.deriveFont(Font.BOLD) }, nameField)
            .addLabeledComponent(JBLabel("Host address").apply { font = font.deriveFont(Font.BOLD) }, baseUrlField)
            .addVerticalGap(8)
            .addComponentFillVertically(tabs, 8)
            .panel
        form.border = JBUI.Borders.emptyLeft(4)

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            preferredSize = Dimension(JBUI.scale(860), JBUI.scale(560))
            add(left, BorderLayout.WEST)
            add(form, BorderLayout.CENTER)
        }
    }

    private fun tableTab(hint: JBLabel, table: KeyValueTablePanel): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8, 6)
            add(hint, BorderLayout.NORTH)
            add(table, BorderLayout.CENTER)
        }

    /** Tab titles, used by tests to keep the order of the environment sections stable. */
    internal fun tabTitles(): List<String> = (0 until tabs.tabCount).map { tabs.getTitleAt(it) }

    /** Name of the environment selected in the list; used by tests. */
    internal fun selectedEnvironmentName(): String? = selected?.name

    private fun loadSelected() {
        val environment = list.selectedValue
        selected = environment
        loading = true
        try {
            if (environment == null) {
                nameField.text = ""
                baseUrlField.text = ""
                nameField.isEnabled = false
                baseUrlField.isEnabled = false
                variablesPanel.bind(mutableListOf())
                globalHeadersPanel.bind(mutableListOf())
                globalParamsPanel.bind(mutableListOf())
            } else {
                nameField.isEnabled = true
                baseUrlField.isEnabled = true
                nameField.text = environment.name
                baseUrlField.text = environment.baseUrl
                variablesPanel.bind(environment.variables)
                globalHeadersPanel.bind(environment.globalHeaders)
                globalParamsPanel.bind(environment.globalParams)
            }
        } finally {
            loading = false
        }
        updateButtons()
    }

    private fun addEnvironment() {
        val environment = EnvironmentConfig().apply {
            id = UUID.randomUUID().toString()
            name = uniqueName("new env")
            baseUrl = "http://localhost:8080"
        }
        environments.add(environment)
        listModel.addElement(environment)
        list.selectedIndex = listModel.size() - 1
        nameField.requestFocusInWindow()
        nameField.selectAll()
        updateButtons()
    }

    private fun duplicateEnvironment() {
        val source = selected ?: return
        val copy = source.deepCopy().apply {
            id = UUID.randomUUID().toString()
            name = uniqueName("${source.name} copy")
        }
        environments.add(copy)
        listModel.addElement(copy)
        list.selectedIndex = listModel.size() - 1
        updateButtons()
    }

    private fun removeEnvironment() {
        val environment = selected ?: return
        if (environments.size <= 1) return
        val index = environments.indexOf(environment)
        if (index < 0) return
        environments.removeAt(index)
        listModel.remove(index)
        if (listModel.size() > 0) list.selectedIndex = minOf(index, listModel.size() - 1)
        updateButtons()
    }

    private fun updateButtons() {
        removeButton.isEnabled = environments.size > 1 && selected != null
    }

    private fun uniqueName(base: String): String {
        if (environments.none { it.name == base }) return base
        var index = 2
        while (environments.any { it.name == "$base $index" }) index++
        return "$base $index"
    }

    override fun doValidate(): ValidationInfo? {
        variablesPanel.commitPendingEdit()
        globalHeadersPanel.commitPendingEdit()
        globalParamsPanel.commitPendingEdit()
        if (environments.isEmpty()) {
            return ValidationInfo("Add at least one environment", nameField)
        }
        val blank = environments.firstOrNull { it.name.isBlank() }
        if (blank != null) {
            return ValidationInfo("Environment name must not be empty", nameField)
        }
        val duplicate = environments.groupBy { it.name.trim().lowercase() }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            return ValidationInfo("Environment name '${duplicate.value.first().name}' is duplicated", nameField)
        }
        environments.forEach { environment ->
            duplicateKey(environment.variables)?.let {
                return ValidationInfo(
                    "Variable '$it' is duplicated in environment '${environment.name}'",
                    variablesPanel
                )
            }
            duplicateKey(environment.globalHeaders)?.let {
                return ValidationInfo(
                    "Global header '$it' is duplicated in environment '${environment.name}'",
                    globalHeadersPanel
                )
            }
            duplicateKey(environment.globalParams)?.let {
                return ValidationInfo(
                    "Global param '$it' is duplicated in environment '${environment.name}'",
                    globalParamsPanel
                )
            }
        }
        return null
    }

    private fun duplicateKey(values: List<KeyValue>): String? {
        val keys = values.filter { it.key.isNotBlank() }.map { it.key.trim().lowercase() }
        return keys.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
    }

    override fun doOKAction() {
        variablesPanel.commitPendingEdit()
        globalHeadersPanel.commitPendingEdit()
        globalParamsPanel.commitPendingEdit()
        if (environments.isEmpty()) return
        environments.forEach { environment ->
            environment.name = environment.name.trim()
            environment.baseUrl = environment.baseUrl.trim()
            environment.variables.removeIf { it.key.isBlank() }
            environment.globalHeaders.removeIf { it.key.isBlank() }
            environment.globalParams.removeIf { it.key.isBlank() }
            environment.normalize()
        }
        state.environments.clear()
        state.environments.addAll(environments)
        if (state.environments.none { it.id == state.activeEnvironmentId }) {
            state.activeEnvironmentId = state.environments.first().id
        }
        service.notifyChanged()
        super.doOKAction()
    }

    private fun documentListener(action: () -> Unit): DocumentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = action()
        override fun removeUpdate(e: DocumentEvent) = action()
        override fun changedUpdate(e: DocumentEvent) = action()
    }

    private class EnvironmentRenderer : ColoredListCellRenderer<EnvironmentConfig>() {
        override fun customizeCellRenderer(
            list: JList<out EnvironmentConfig>,
            value: EnvironmentConfig?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return
            icon = com.intellij.icons.AllIcons.Nodes.Folder
            append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  ${value.baseUrl}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            border = JBUI.Borders.empty(4, 2)
        }
    }
}
