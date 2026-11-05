package dev.smartrequest.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
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
import dev.smartrequest.scenario.ScenarioImporter
import dev.smartrequest.scenario.ScenarioJson
import dev.smartrequest.scenario.ScenarioJsonException
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
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The scenario list under the request tree in the Smart Request tool window. Selecting a scenario
 * opens it in the panel on the right, exactly like selecting a request opens the request editor.
 * The window has no separate Scenarios tool window or editor tab.
 */
class ScenarioListPanel(
    private val project: Project,
    private val onOpen: (ScenarioConfig) -> Unit = {}
) : JBPanel<ScenarioListPanel>(BorderLayout()), Disposable {

    private val service = project.service<SmartRequestProjectService>()
    private val model = DefaultListModel<ScenarioConfig>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ScenarioRenderer()
        border = JBUI.Borders.empty(4)
        visibleRowCount = 8
        addListSelectionListener {
            if (rebuilding || it.valueIsAdjusting) return@addListSelectionListener
            selectedScenario()?.let(onOpen)
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(event)) {
                    val index = locationToIndex(event.point)
                    if (index >= 0) {
                        // Select for the context menu, but do not open the scenario on a right click.
                        rebuilding = true
                        try {
                            selectedIndex = index
                        } finally {
                            rebuilding = false
                        }
                    }
                    showActionsMenu(this@apply, event.x, event.y)
                } else if (SwingUtilities.isLeftMouseButton(event)) {
                    // Re-open the already-selected scenario as well: selecting a request switches the
                    // right pane away and a second click should bring the scenario back.
                    if (locationToIndex(event.point) >= 0) selectedScenario()?.let(onOpen)
                }
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER) selectedScenario()?.let(onOpen)
            }
        })
    }
    private val searchField = JBTextField().apply {
        emptyText.text = "Filter scenarios"
    }
    private val emptyLabel = JBLabel("No scenarios yet. Create one to chain requests.").apply {
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = JBLabel.CENTER
        border = JBUI.Borders.empty(6)
    }
    private val newButton = smallButton("+", "New scenario") { createScenario() }
    private val moreButton = smallButton("⋯", "Scenario actions") { showMoreMenu() }

    private var rebuilding = false
    private val stateListener: () -> Unit = { rebuild() }

    init {
        border = JBUI.Borders.empty(4)
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuild()
            override fun removeUpdate(e: DocumentEvent) = rebuild()
            override fun changedUpdate(e: DocumentEvent) = rebuild()
        })

        add(buildHeader(), BorderLayout.NORTH)
        add(JBScrollPane(list).apply { border = JBUI.Borders.emptyTop(2) }, BorderLayout.CENTER)
        add(emptyLabel, BorderLayout.SOUTH)

        service.addChangeListener(stateListener)
        rebuild()
    }

    private fun buildHeader(): JPanel {
        val header = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(2)
        }
        val titleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("Scenarios").apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.WEST)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                    isOpaque = false
                    add(newButton)
                    add(moreButton)
                },
                BorderLayout.EAST
            )
        }
        header.add(titleRow, BorderLayout.NORTH)
        header.add(searchField, BorderLayout.CENTER)
        return header
    }

    private fun smallButton(text: String, tooltip: String, action: () -> Unit): JButton =
        JButton(text).apply {
            toolTipText = tooltip
            isFocusPainted = false
            margin = JBUI.insets(1, 4)
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
            preferredSize = Dimension(JBUI.scale(if (text.length <= 1) 26 else 34), JBUI.scale(24))
            addActionListener { action() }
        }

    private fun showMoreMenu() {
        showActionsMenu(moreButton, 0, moreButton.height)
    }

    private fun showActionsMenu(anchor: Component, x: Int, y: Int) {
        val selected = selectedScenario() != null
        val menu = JPopupMenu()
        menu.add(menuItem("New Scenario") { createScenario() })
        menu.addSeparator()
        menu.add(menuItem("Rename…", selected) { renameSelected() })
        menu.add(menuItem("Duplicate", selected) { duplicateSelected() })
        menu.add(menuItem("Delete", selected) { deleteSelected() })
        menu.addSeparator()
        menu.add(menuItem("Import…") { importFile() })
        menu.add(menuItem("Export…", selected) { exportSelected() })
        menu.show(anchor, x, y)
    }

    private fun menuItem(text: String, enabled: Boolean = true, action: () -> Unit): JMenuItem =
        JMenuItem(text).apply {
            isEnabled = enabled
            addActionListener { action() }
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
            if (target != null) {
                list.setSelectedValue(target, true)
            } else {
                list.clearSelection()
            }
        } finally {
            rebuilding = false
        }
    }

    private fun selectedScenario(): ScenarioConfig? = list.selectedValue

    fun createScenario() {
        val scenario = service.addScenario("New Scenario")
        rebuild()
        list.setSelectedValue(scenario, true)
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

    private fun exportSelected() {
        val scenario = selectedScenario() ?: return
        val text = try {
            encodeScenario(scenario)
        } catch (e: Exception) {
            notify("Could not prepare the scenario: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR)
            return
        }
        val descriptor = FileSaverDescriptor(
            "Export Scenario",
            "Save the scenario and the requests it uses as a JSON file",
            "json"
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, "${safeFileName(scenario.name)}.json") ?: return
        try {
            wrapper.file.writeText(text)
            notify("Exported '${scenario.name}'.", NotificationType.INFORMATION)
        } catch (e: Exception) {
            notify("Could not write the file: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR)
        }
    }

    private fun importFile() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("Import Scenario")
        val file = FileChooserFactory.getInstance()
            .createFileChooser(descriptor, project, null)
            .choose(project)
            .firstOrNull() ?: return
        try {
            val text = String(file.contentsToByteArray(), Charsets.UTF_8)
            val scenario = importScenario(text)
            list.setSelectedValue(scenario, true)
            notify("Imported scenario '${scenario.name}'.", NotificationType.INFORMATION)
        } catch (e: ScenarioJsonException) {
            notify(e.message ?: "The file is not a valid scenario.", NotificationType.WARNING)
        } catch (e: Exception) {
            notify("Could not import the file: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR)
        }
    }

    private fun encodeScenario(scenario: ScenarioConfig): String = ScenarioJson.encode(
        scenario = scenario,
        requestProvider = service::findRequest,
        groupProvider = { id -> service.groupOf(id)?.name }
    )

    private fun importScenario(text: String): ScenarioConfig {
        val bundle = ScenarioJson.decode(text)
        val scenario = ScenarioImporter.apply(service, bundle)
        rebuild()
        return scenario
    }

    private fun safeFileName(name: String): String {
        val cleaned = name.trim()
            .map { character ->
                if (character.isLetterOrDigit() || character == ' ' || character == '-' || character == '_') {
                    character
                } else {
                    '_'
                }
            }
            .joinToString("")
            .trim()
        return cleaned.ifEmpty { "scenario" }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Smart Request")
            .createNotification("Scenarios", message, type)
            .notify(project)
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
        list.setSelectedValue(scenario, true)
        return scenario
    }

    internal fun duplicateForTest(id: String): ScenarioConfig? {
        val copy = service.duplicateScenario(id) ?: return null
        rebuild()
        list.setSelectedValue(copy, true)
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

    internal fun isDeleteEnabled(): Boolean = selectedScenario() != null

    internal fun exportForTest(scenarioId: String): String? =
        service.scenario(scenarioId)?.let { encodeScenario(it) }

    internal fun importForTest(text: String): ScenarioConfig = importScenario(text)

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
