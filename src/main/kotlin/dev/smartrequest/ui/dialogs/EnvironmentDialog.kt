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
import dev.smartrequest.model.SmartRequestState
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
    private val removeButton = JButton("Remove")
    private var selected: EnvironmentConfig? = null
    private var loading = true

    init {
        title = "Environment Settings"
        setOKButtonText("OK")
        environments.forEach { listModel.addElement(it) }
        init()
        if (listModel.size() > 0) list.selectedIndex = 0
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

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Environment name").apply { font = font.deriveFont(Font.BOLD) }, nameField)
            .addLabeledComponent(JBLabel("Host address").apply { font = font.deriveFont(Font.BOLD) }, baseUrlField)
            .addVerticalGap(8)
            .addComponent(JBLabel("Variables").apply { font = font.deriveFont(Font.BOLD) })
            .addComponent(variablesHint)
            .addComponentFillVertically(variablesPanel, 8)
            .panel
        form.border = JBUI.Borders.emptyLeft(4)

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            preferredSize = Dimension(JBUI.scale(820), JBUI.scale(520))
            add(left, BorderLayout.WEST)
            add(form, BorderLayout.CENTER)
        }
    }

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
            } else {
                nameField.isEnabled = true
                baseUrlField.isEnabled = true
                nameField.text = environment.name
                baseUrlField.text = environment.baseUrl
                variablesPanel.bind(environment.variables)
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
            val keys = environment.variables.filter { it.key.isNotBlank() }.map { it.key.trim() }
            val duplicateKey = keys.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
            if (duplicateKey != null) {
                return ValidationInfo(
                    "Variable '${duplicateKey.key}' is duplicated in environment '${environment.name}'",
                    variablesPanel
                )
            }
        }
        return null
    }

    override fun doOKAction() {
        variablesPanel.commitPendingEdit()
        if (environments.isEmpty()) return
        environments.forEach { environment ->
            environment.name = environment.name.trim()
            environment.baseUrl = environment.baseUrl.trim()
            environment.variables.removeIf { it.key.isBlank() }
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
