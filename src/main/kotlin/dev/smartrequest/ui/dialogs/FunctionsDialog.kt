package dev.smartrequest.ui.dialogs

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
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * Project-level function catalog. Built-ins are documented in docs/template-functions.md; this
 * dialog manages the user-defined pure macros stored in `.idea/smart-request.xml`.
 */
class FunctionsDialog(
    private val owner: Project,
    private val service: SmartRequestProjectService
) : DialogWrapper(owner, true) {

    private val model = object : DefaultTableModel(
        arrayOf("Name", "Parameters", "Description"),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = JBTable(model).apply {
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
        refresh()
    }

    override fun createCenterPanel(): JComponent {
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JButton("Add…").apply { addActionListener { addFunction() } })
            add(JButton("Edit…").apply { addActionListener { editSelected() } })
            add(JButton("Delete").apply { addActionListener { deleteSelected() } })
        }
        val hint = JBLabel(
            "Macros are pure: they may use their parameters, literals and other functions, " +
                "but not environment variables. Example: concat('Bearer ', base64(value))."
        ).apply {
            foreground = UIUtil.getContextHelpForeground()
        }
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(10)
            preferredSize = Dimension(JBUI.scale(680), JBUI.scale(380))
            add(buttons, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(hint, BorderLayout.SOUTH)
        }
        return panel
    }

    private fun refresh() {
        functions = service.functions().toList()
        model.rowCount = 0
        functions.forEach { definition ->
            model.addRow(
                arrayOf(
                    definition.name,
                    definition.parameters.joinToString(", "),
                    definition.description
                )
            )
        }
    }

    private fun selected(): FunctionDefinition? {
        val index = table.selectedRow
        return functions.getOrNull(index)
    }

    private fun addFunction() {
        val dialog = FunctionEditorDialog(owner, service, null)
        if (dialog.showAndGet()) refresh()
    }

    private fun editSelected() {
        val definition = selected() ?: return
        val dialog = FunctionEditorDialog(owner, service, definition)
        if (dialog.showAndGet()) refresh()
    }

    private fun deleteSelected() {
        val definition = selected() ?: return
        val result = Messages.showYesNoDialog(
            owner,
            "Delete function '${definition.name}'?",
            "Delete Function",
            Messages.getQuestionIcon()
        )
        if (result != Messages.YES) return
        service.removeFunction(definition.id)
        refresh()
    }

    // Test hooks.

    internal fun functionCountForTest(): Int = model.rowCount

    internal fun selectForTest(name: String): Boolean {
        val index = functions.indexOfFirst { it.name == name }
        if (index < 0) return false
        table.selectionModel.setSelectionInterval(index, index)
        return true
    }

    internal fun deleteForTest(name: String) {
        val definition = functions.firstOrNull { it.name == name } ?: return
        service.removeFunction(definition.id)
        refresh()
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
