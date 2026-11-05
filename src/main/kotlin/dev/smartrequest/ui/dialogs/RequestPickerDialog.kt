package dev.smartrequest.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.service.SmartRequestProjectService
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
 * Picks the request a new scenario step will send. Filtering matches name, method, URL and group,
 * and each row shows its group so requests with the same name stay distinguishable.
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

    fun selectedRequest(): HttpRequestConfig? = list.selectedValue?.request

    private fun rebuild() {
        val query = searchField.text.trim()
        val previous = list.selectedValue?.request?.id
        val all = service.state().groups.flatMap { group ->
            group.requests.map { request -> Choice(request, group.name) }
        }
        val matches = all.filter { choice ->
            val request = choice.request
            query.isEmpty() ||
                request.name.contains(query, ignoreCase = true) ||
                request.method.contains(query, ignoreCase = true) ||
                request.url.contains(query, ignoreCase = true) ||
                choice.groupName.contains(query, ignoreCase = true)
        }
        model.clear()
        matches.forEach(model::addElement)
        emptyLabel.isVisible = matches.isEmpty()
        val target = matches.firstOrNull { it.request.id == previous }
            ?: matches.firstOrNull { it.request.id == lastUsedRequestId }
            ?: matches.firstOrNull()
        if (target != null) {
            list.setSelectedValue(target, true)
            list.ensureIndexIsVisible(matches.indexOf(target).coerceAtLeast(0))
        }
        updateOk()
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
        val index = (0 until model.size()).firstOrNull { model.getElementAt(it).request.id == requestId } ?: return
        list.selectedIndex = index
    }

    internal fun visibleRequestIds(): List<String> = (0 until model.size()).map { model.getElementAt(it).request.id }

    internal fun isAddEnabled(): Boolean = okAction.isEnabled

    /** Applies the current selection without opening the dialog window. */
    internal fun confirmForTest(): HttpRequestConfig? {
        val request = selectedRequest()
        if (request != null) doOKAction()
        return request
    }

    internal fun filterText(): String = searchField.text

    private data class Choice(val request: HttpRequestConfig, val groupName: String)

    private class RequestRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is Choice) {
                text = "${value.request.method}  ${value.request.name}  —  ${value.groupName}"
                toolTipText = value.request.url
            }
            return this
        }
    }
}
