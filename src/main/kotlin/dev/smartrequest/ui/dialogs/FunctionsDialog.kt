package dev.smartrequest.ui.dialogs

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.FunctionDefinition
import dev.smartrequest.service.SmartRequestProjectService
import dev.smartrequest.template.BuiltInFunctionCatalog
import dev.smartrequest.template.BuiltInFunctionDoc
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

/**
 * Function catalog. The Built-in tab documents every registered function with an example and its
 * scope; the My Functions tab manages project-level pure macros stored in `.idea/smart-request.xml`.
 */
class FunctionsDialog(
    private val owner: Project,
    private val service: SmartRequestProjectService
) : DialogWrapper(owner, true) {

    private val tabs = JTabbedPane()

    // Built-in catalog.
    private val builtInFilter = JBTextField().apply {
        emptyText.text = "Filter built-in functions"
    }
    private val builtInModel = object : DefaultTableModel(
        arrayOf("类别", "函数", "示例", "使用范围"),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val builtInTable = JBTable(builtInModel).apply {
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        setShowGrid(false)
        rowHeight = JBUI.scale(24)
        autoResizeMode = javax.swing.JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) copyExample()
            }
        })
        selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) updateBuiltInDetail()
        }
    }
    private val builtInDetail = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        rows = 4
        border = JBUI.Borders.empty(6)
        font = font.deriveFont(Font.PLAIN)
    }
    private val copyExampleButton = JButton("Copy example").apply {
        addActionListener { copyExample() }
    }
    private var visibleBuiltIns: List<BuiltInFunctionDoc> = BuiltInFunctionCatalog.all
    private var rebuildingBuiltIns = false

    // Project macros.
    private val macroModel = object : DefaultTableModel(
        arrayOf("Name", "Parameters", "Description"),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val macroTable = JBTable(macroModel).apply {
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        setShowGrid(false)
        rowHeight = JBUI.scale(24)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) editSelected()
            }
        })
    }
    private var functions: List<FunctionDefinition> = service.functions().toList()

    init {
        title = "Functions"
        setOKButtonText("Close")
        init()
        setupBuiltInColumns()
        builtInFilter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshBuiltIns()
            override fun removeUpdate(e: DocumentEvent) = refreshBuiltIns()
            override fun changedUpdate(e: DocumentEvent) = refreshBuiltIns()
        })
        refreshBuiltIns()
        refreshMacros()
    }

    override fun createCenterPanel(): JComponent {
        tabs.addTab("Built-in (${BuiltInFunctionCatalog.all.size})", buildBuiltInPanel())
        tabs.addTab("My functions (${functions.size})", buildMacroPanel())
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(JBUI.scale(940), JBUI.scale(540))
            add(tabs, BorderLayout.CENTER)
        }
    }

    private fun buildBuiltInPanel(): JComponent {
        val top = JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 6, 0, 6)
            add(builtInFilter, BorderLayout.NORTH)
            add(
                JBLabel(
                    "Functions can be used in URL, Query, Path, Header, Body, environment values " +
                        "and scenario run variables; macros can call them too."
                ).apply { foreground = UIUtil.getContextHelpForeground() },
                BorderLayout.SOUTH
            )
        }
        val bottom = JBPanel<JBPanel<*>>(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6)
            add(JBScrollPane(builtInDetail).apply {
                preferredSize = Dimension(JBUI.scale(720), JBUI.scale(90))
            }, BorderLayout.CENTER)
            add(copyExampleButton, BorderLayout.EAST)
        }
        return JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(builtInTable).apply { border = JBUI.Borders.empty(0, 6, 0, 6) }, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    private fun buildMacroPanel(): JComponent {
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 6, 0, 6)
            add(JButton("Add…").apply { addActionListener { addFunction() } })
            add(JButton("Edit…").apply { addActionListener { editSelected() } })
            add(JButton("Delete").apply { addActionListener { deleteSelected() } })
        }
        val hint = JBLabel(
            "Macros are pure: they may use their parameters, literals and other functions, " +
                "but not environment variables. Example: concat('Bearer ', base64(value))."
        ).apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(6)
        }
        return JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            add(buttons, BorderLayout.NORTH)
            add(JBScrollPane(macroTable).apply { border = JBUI.Borders.empty(0, 6, 0, 6) }, BorderLayout.CENTER)
            add(hint, BorderLayout.SOUTH)
        }
    }

    private fun setupBuiltInColumns() {
        if (builtInTable.columnCount < 4) return
        builtInTable.columnModel.getColumn(0).preferredWidth = JBUI.scale(70)
        builtInTable.columnModel.getColumn(1).preferredWidth = JBUI.scale(240)
        builtInTable.columnModel.getColumn(2).preferredWidth = JBUI.scale(330)
        builtInTable.columnModel.getColumn(3).preferredWidth = JBUI.scale(220)
    }

    private fun refreshBuiltIns() {
        val query = builtInFilter.text.trim()
        visibleBuiltIns = BuiltInFunctionCatalog.all.filter { doc ->
            query.isEmpty() ||
                doc.category.contains(query, ignoreCase = true) ||
                doc.signature.contains(query, ignoreCase = true) ||
                doc.example.contains(query, ignoreCase = true) ||
                doc.scope.contains(query, ignoreCase = true) ||
                doc.description.contains(query, ignoreCase = true)
        }
        rebuildingBuiltIns = true
        try {
            builtInModel.rowCount = 0
            visibleBuiltIns.forEach { doc ->
                builtInModel.addRow(
                    arrayOf(doc.category, doc.signature, doc.example, doc.scope)
                )
            }
            if (visibleBuiltIns.isNotEmpty()) {
                builtInTable.selectionModel.setSelectionInterval(0, 0)
            } else {
                builtInTable.clearSelection()
            }
        } finally {
            rebuildingBuiltIns = false
        }
        updateBuiltInDetail()
    }

    private fun updateBuiltInDetail() {
        if (rebuildingBuiltIns) return
        val doc = selectedBuiltIn()
        copyExampleButton.isEnabled = doc != null
        builtInDetail.text = when {
            doc == null -> "No built-in function selected."
            else -> buildString {
                append(doc.description)
                append("\n\n示例：")
                append(doc.example)
                append("\n使用范围：")
                append(doc.scope)
            }
        }
        builtInDetail.caretPosition = 0
    }

    private fun selectedBuiltIn(): BuiltInFunctionDoc? {
        val index = builtInTable.selectedRow
        return visibleBuiltIns.getOrNull(index)
    }

    private fun copyExample() {
        val doc = selectedBuiltIn() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(doc.example))
        copyExampleButton.text = "Copied"
        Timer(1200) { copyExampleButton.text = "Copy example" }.apply {
            isRepeats = false
            start()
        }
    }

    private fun refreshMacros() {
        functions = service.functions().toList()
        macroModel.rowCount = 0
        functions.forEach { definition ->
            macroModel.addRow(
                arrayOf(
                    definition.name,
                    definition.parameters.joinToString(", "),
                    definition.description
                )
            )
        }
        if (tabs.tabCount > 1) {
            tabs.setTitleAt(1, "My functions (${functions.size})")
        }
    }

    private fun selectedMacro(): FunctionDefinition? {
        val index = macroTable.selectedRow
        return functions.getOrNull(index)
    }

    private fun addFunction() {
        val dialog = FunctionEditorDialog(owner, service, null)
        if (dialog.showAndGet()) refreshMacros()
    }

    private fun editSelected() {
        val definition = selectedMacro() ?: return
        val dialog = FunctionEditorDialog(owner, service, definition)
        if (dialog.showAndGet()) refreshMacros()
    }

    private fun deleteSelected() {
        val definition = selectedMacro() ?: return
        val result = Messages.showYesNoDialog(
            owner,
            "Delete function '${definition.name}'?",
            "Delete Function",
            Messages.getQuestionIcon()
        )
        if (result != Messages.YES) return
        service.removeFunction(definition.id)
        refreshMacros()
    }

    // Test hooks.

    internal fun functionCountForTest(): Int = macroModel.rowCount

    internal fun builtInCountForTest(): Int = builtInModel.rowCount

    internal fun selectForTest(name: String): Boolean {
        val index = functions.indexOfFirst { it.name == name }
        if (index < 0) return false
        macroTable.selectionModel.setSelectionInterval(index, index)
        return true
    }

    internal fun selectBuiltInForTest(signature: String): Boolean {
        val index = visibleBuiltIns.indexOfFirst { it.signature == signature }
        if (index < 0) return false
        builtInTable.selectionModel.setSelectionInterval(index, index)
        return true
    }

    internal fun builtInDetailForTest(): String = builtInDetail.text

    internal fun deleteForTest(name: String) {
        val definition = functions.firstOrNull { it.name == name } ?: return
        service.removeFunction(definition.id)
        refreshMacros()
    }
}

/**
 * Editor for one pure macro. The expression is stored without the surrounding `{{...}}`; the
 * service validates syntax, function names and parameter references before saving.
 */
class FunctionEditorDialog(
    private val owner: Project,
    private val service: SmartRequestProjectService,
    private val existing: FunctionDefinition?
) : DialogWrapper(owner, true) {

    private val nameField = JBTextField(existing?.name.orEmpty())
    private val parametersField = JBTextField(existing?.parameters?.joinToString(", ").orEmpty())
    private val descriptionField = JBTextField(existing?.description.orEmpty())
    private val expressionArea = JBTextArea(existing?.expression.orEmpty(), 5, 50).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        lineWrap = true
        wrapStyleWord = false
    }

    init {
        title = if (existing == null) "New Function" else "Edit Function"
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Name").apply { font = font.deriveFont(Font.BOLD) }, nameField)
            .addLabeledComponent(JBLabel("Parameters").apply { font = font.deriveFont(Font.BOLD) }, parametersField)
            .addComponent(
                JBLabel("Comma-separated names; reference them in the expression as {{name}}.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                }
            )
            .addLabeledComponent(JBLabel("Description"), descriptionField)
            .addLabeledComponent(
                JBLabel("Expression").apply { font = font.deriveFont(Font.BOLD) },
                JBScrollPane(expressionArea).apply {
                    preferredSize = Dimension(JBUI.scale(480), JBUI.scale(110))
                }
            )
            .addComponent(
                JBLabel(
                    "Examples: base64(value) · concat('Bearer ', base64(value)) · rsaSign(payload, key)"
                ).apply { foreground = UIUtil.getContextHelpForeground() }
            )
            .panel
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(300))
            add(form, BorderLayout.NORTH)
        }
    }

    private fun parameters(): List<String> =
        parametersField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    override fun doValidate(): ValidationInfo? {
        val error = service.functionError(
            id = existing?.id,
            name = nameField.text,
            parameters = parameters(),
            expression = expressionArea.text
        ) ?: return null
        return ValidationInfo(error, expressionArea)
    }

    override fun doOKAction() {
        val parameters = parameters()
        val saved = if (existing == null) {
            service.addFunction(
                name = nameField.text,
                parameters = parameters,
                expression = expressionArea.text,
                description = descriptionField.text
            ) != null
        } else {
            service.updateFunction(
                id = existing.id,
                name = nameField.text,
                parameters = parameters,
                expression = expressionArea.text,
                description = descriptionField.text
            )
        }
        if (!saved) {
            Messages.showErrorDialog(owner, "The function could not be saved.", "Function")
            return
        }
        super.doOKAction()
    }
}
