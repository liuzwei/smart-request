package dev.smartrequest.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.scenario.ScenarioGraph
import dev.smartrequest.service.SmartRequestProjectService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Tool window list of the project's scenarios: create, rename, duplicate, delete and open. The
 * editor itself lives in an editor tab so it can use the full width and be split.
 */
class ScenarioListPanel(
    private val project: Project
) : JBPanel<ScenarioListPanel>(BorderLayout()), Disposable {

    private val service = project.service<SmartRequestProjectService>()
    private val model = DefaultListModel<ScenarioConfig>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ScenarioRenderer()
        border = JBUI.Borders.empty(4)
        visibleRowCount = 14
        addListSelectionListener { if (!rebuilding && !it.valueIsAdjusting) updateButtons() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    selectedScenario()?.let(::open)
                }
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER) selectedScenario()?.let(::open)
            }
        })
    }
    private val searchField = JBTextField().apply {
        emptyText.text = "Filter scenarios"
    }
    private val emptyLabel = JBLabel("No scenarios yet. Create one to chain requests.").apply {
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = JBLabel.CENTER
        border = JBUI.Borders.empty(8)
    }

    private val newButton = JButton("New", AllIcons.General.Add)
    private val renameButton = JButton("Rename", null)
    private val duplicateButton = JButton("Duplicate", AllIcons.Actions.Copy)
    private val deleteButton = JButton("Delete", AllIcons.General.Remove)

    private var rebuilding = false
    private val stateListener: () -> Unit = { rebuild() }

    init {
        border = JBUI.Borders.empty(4)
        newButton.addActionListener { createScenario() }
        renameButton.addActionListener { renameSelected() }
        duplicateButton.addActionListener { duplicateSelected() }
        deleteButton.addActionListener { deleteSelected() }
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuild()
            override fun removeUpdate(e: DocumentEvent) = rebuild()
            override fun changedUpdate(e: DocumentEvent) = rebuild()
        })

        add(buildHeader(), BorderLayout.NORTH)
        add(JBScrollPane(list).apply { border = JBUI.Borders.emptyTop(4) }, BorderLayout.CENTER)
        add(buildFooter(), BorderLayout.SOUTH)

        service.addChangeListener(stateListener)
        rebuild()
    }

    private fun buildHeader(): JPanel {
        val header = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(2)
        }
        val title = JBLabel("Scenarios").apply { font = font.deriveFont(Font.BOLD) }
        header.add(title, BorderLayout.NORTH)
        header.add(searchField, BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(newButton)
            add(renameButton)
            add(duplicateButton)
            add(deleteButton)
        }
        header.add(buttons, BorderLayout.SOUTH)
        return header
    }

    private fun buildFooter(): JPanel {
        val footer = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(emptyLabel)
        }
        return footer
    }

    private fun rebuild() {
        val selectedId = list.selectedValue?.id
        val query = searchField.text.trim()
        val scenarios = service.scenarios().filter { scenario ->
            query.isEmpty() || scenario.name.contains(query, ignoreCase = true)
        }
        rebuilding = true
        try {
            model.clear()
            scenarios.forEach(model::addElement)
            emptyLabel.isVisible = service.scenarios().isEmpty()
            emptyLabel.text = if (service.scenarios().isEmpty()) {
                "No scenarios yet. Create one to chain requests."
            } else {
                "No scenario matches the filter."
            }
            val target = scenarios.firstOrNull { it.id == selectedId }
                ?: scenarios.firstOrNull()
            if (target != null) {
                list.setSelectedValue(target, true)
            } else {
                list.clearSelection()
            }
        } finally {
            rebuilding = false
        }
        updateButtons()
    }

    private fun updateButtons() {
        val selected = selectedScenario() != null
        renameButton.isEnabled = selected
        duplicateButton.isEnabled = selected
        deleteButton.isEnabled = selected
    }

    private fun selectedScenario(): ScenarioConfig? = list.selectedValue

    private fun createScenario() {
        val scenario = service.addScenario("New Scenario")
        rebuild()
        list.setSelectedValue(scenario, true)
        open(scenario)
    }

    private fun renameSelected() {
        val scenario = selectedScenario() ?: return
        val name = Messages.showInputDialog(
            project,
            "Scenario name:",
            "Rename Scenario",
            Messages.getQuestionIcon(),
            scenario.name,
            null
        )?.trim() ?: return
        if (name.isEmpty()) return
        service.renameScenario(scenario.id, name)
        rebuild()
    }

    private fun duplicateSelected() {
        val scenario = selectedScenario() ?: return
        val copy = service.duplicateScenario(scenario.id) ?: return
        rebuild()
        list.setSelectedValue(copy, true)
        open(copy)
    }

    private fun deleteSelected() {
        val scenario = selectedScenario() ?: return
        val steps = ScenarioGraph.requestNodes(scenario).size
        val result = Messages.showYesNoDialog(
            project,
            "Delete scenario '${scenario.name}'${if (steps > 0) " and its $steps step(s)" else ""}?",
            "Delete Scenario",
            Messages.getQuestionIcon()
        )
        if (result != Messages.YES) return
        service.removeScenario(scenario.id)
        rebuild()
    }

    private fun open(scenario: ScenarioConfig) {
        ScenarioEditorService.getInstance(project).open(scenario.id)
    }

    // Test hooks.

    internal fun scenarioCount(): Int = model.size()

    internal fun names(): List<String> = (0 until model.size()).map { model.getElementAt(it).name }

    internal fun select(id: String): Boolean {
        val index = (0 until model.size()).firstOrNull { model.getElementAt(it).id == id } ?: return false
        list.selectedIndex = index
        return true
    }

    internal fun createForTest(): ScenarioConfig {
        val scenario = service.addScenario("New Scenario")
        rebuild()
        return scenario
    }

    internal fun duplicateForTest(id: String): ScenarioConfig? {
        val copy = service.duplicateScenario(id)
        rebuild()
        return copy
    }

    internal fun deleteForTest(id: String) {
        service.removeScenario(id)
        rebuild()
    }

    internal fun search(text: String) {
        searchField.text = text
        rebuild()
    }

    internal fun isDeleteEnabled(): Boolean = deleteButton.isEnabled

    override fun dispose() {
        service.removeChangeListener(stateListener)
    }

    private class ScenarioRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is ScenarioConfig) {
                val steps = ScenarioGraph.requestNodes(value).size
                text = "${value.name}   ·   $steps step(s)"
                toolTipText = value.notes.takeIf { it.isNotBlank() } ?: value.name
                border = JBUI.Borders.empty(3, 4)
            }
            return this
        }
    }
}
