package dev.smartrequest.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.service.SmartRequestProjectService
import dev.smartrequest.ui.HttpMethodColors
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Picks the request a new scenario step will send. Requests are grouped by their parent group, with
 * a bold group header inserted before each group's requests so the user can browse the same structure
 * as the request tree.
 */
class RequestPickerDialog(
    project: Project,
    private val service: SmartRequestProjectService,
    private val lastUsedRequestId: String? = null
) : DialogWrapper(project) {

    private val searchField = JBTextField().apply {
        emptyText.text = "Filter by name, method or group"
        preferredSize = Dimension(JBUI.scale(320), preferredSize.height)
    }
    private val model = DefaultListModel<Choice>()
    private val list = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 12
        cellRenderer = RequestRenderer()
        addListSelectionListener { updateOk() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    if (selectedRequest() != null) doOKAction()
                }
            }
        })
    }
    private val emptyLabel = JBLabel("No requests match the filter.").apply {
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = JBLabel.CENTER
    }

    init {
        title = "Add Step"
        setOKButtonText("Add Step")
        init()
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuild()
            override fun removeUpdate(e: DocumentEvent) = rebuild()
            override fun changedUpdate(e: DocumentEvent) = rebuild()
        })
        rebuild()
    }

    override fun createCenterPanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(4, 0)
            preferredSize = Dimension(JBUI.scale(480), JBUI.scale(400))
        }
        panel.add(searchField, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.add(emptyLabel, BorderLayout.SOUTH)
        return panel
    }

    override fun getPreferredFocusedComponent(): JBTextField = searchField

    fun selectedRequest(): HttpRequestConfig? =
        (list.selectedValue as? Choice.RequestItem)?.request

    private fun rebuild() {
        val query = searchField.text.trim()
        val previous = (list.selectedValue as? Choice.RequestItem)?.request?.id
        val all = service.state().groups.flatMap { group ->
            val header = listOf(Choice.GroupHeader(group.name))
            val requests = group.requests.map { request -> Choice.RequestItem(request) }
            header + requests
        }
        val matches = if (query.isEmpty()) {
            // Show all, but collapse empty groups.
            all.filter { choice ->
                choice !is Choice.GroupHeader || groupHasVisibleRequests(choice.groupName, all, query)
            }
        } else {
            all.filter { choice ->
                when (choice) {
                    is Choice.RequestItem -> {
                        val request = choice.request
                        request.name.contains(query, ignoreCase = true) ||
                            request.method.contains(query, ignoreCase = true) ||
                            request.url.contains(query, ignoreCase = true) ||
                            choice.groupName.contains(query, ignoreCase = true)
                    }
                    is Choice.GroupHeader -> groupHasVisibleRequests(choice.groupName, all, query)
                }
            }
        }
        model.clear()
        matches.forEach(model::addElement)
        emptyLabel.isVisible = matches.none { it is Choice.RequestItem }
        val target = matches.firstOrNull { it is Choice.RequestItem && it.request.id == previous }
            ?: matches.firstOrNull { it is Choice.RequestItem && it.request.id == lastUsedRequestId }
            ?: matches.firstOrNull { it is Choice.RequestItem }
        if (target != null) {
            list.setSelectedValue(target, true)
            list.ensureIndexIsVisible(matches.indexOf(target).coerceAtLeast(0))
        }
        updateOk()
    }

    private fun groupHasVisibleRequests(groupName: String, all: List<Choice>, query: String): Boolean {
        if (query.isEmpty()) return true
        return all.any { choice ->
            choice is Choice.RequestItem && choice.groupName == groupName && (
                choice.request.name.contains(query, ignoreCase = true) ||
                    choice.request.method.contains(query, ignoreCase = true) ||
                    choice.request.url.contains(query, ignoreCase = true) ||
                    groupName.contains(query, ignoreCase = true)
                )
        }
    }

    private fun updateOk() {
        okAction.isEnabled = selectedRequest() != null
    }

    // Test hooks.

    internal fun search(text: String) {
        searchField.text = text
        rebuild()
    }

    internal fun selectRequest(requestId: String) {
        val index = (0 until model.size()).firstOrNull {
            val choice = model.getElementAt(it)
            choice is Choice.RequestItem && choice.request.id == requestId
        } ?: return
        list.selectedIndex = index
    }

    internal fun visibleRequestIds(): List<String> =
        (0 until model.size()).mapNotNull { idx ->
            (model.getElementAt(idx) as? Choice.RequestItem)?.request?.id
        }

    internal fun isAddEnabled(): Boolean = okAction.isEnabled

    /** Applies the current selection without opening the dialog window. */
    internal fun confirmForTest(): HttpRequestConfig? {
        val request = selectedRequest()
        if (request != null) doOKAction()
        return request
    }

    private sealed interface Choice {
        val groupName: String

        data class GroupHeader(override val groupName: String) : Choice
        data class RequestItem(
            val request: HttpRequestConfig,
            override val groupName: String = ""
        ) : Choice {
            /** The actual group the request lives in, set during list construction. */
            val actualGroupName: String get() = groupName
        }
    }

    private class RequestRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            when (value) {
                is Choice.GroupHeader -> {
                    icon = null
                    text = value.groupName
                    font = font.deriveFont(java.awt.Font.BOLD)
                    background = if (isSelected) list?.selectionBackground else UIUtil.getPanelBackground()
                    foreground = if (isSelected) list?.selectionForeground else UIUtil.getLabelForeground()
                    border = JBUI.Borders.empty(6, 4, 1, 4)
                }
                is Choice.RequestItem -> {
                    icon = null
                    text = "  ${value.request.method}  ${value.request.name}"
                    toolTipText = value.request.url
                    font = font.deriveFont(java.awt.Font.PLAIN)
                    foreground = if (isSelected) {
                        list?.selectionForeground
                    } else {
                        HttpMethodColors.of(value.request.method)
                    }
                    border = JBUI.Borders.empty(1, 8, 1, 4)
                }
            }
            return this
        }
    }
}
