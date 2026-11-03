package dev.smartrequest.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.SmartRequestState
import dev.smartrequest.model.withoutBlankKeys
import dev.smartrequest.service.SmartRequestProjectService
import dev.smartrequest.ui.KeyValueTablePanel
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class GlobalSettingsDialog(
    project: Project,
    private val state: SmartRequestState,
    private val service: SmartRequestProjectService
) : DialogWrapper(project, true) {

    private val headers = state.globalHeaders.map { it.deepCopy() }.toMutableList()
    private val params = state.globalParams.map { it.deepCopy() }.toMutableList()
    private val headersPanel = KeyValueTablePanel(showLegend = false)
    private val paramsPanel = KeyValueTablePanel(showLegend = false)
    private val timeoutField = JBTextField(state.timeoutMs.toString())
    private val followRedirects = JCheckBox("Follow HTTP redirects", state.followRedirects)

    init {
        title = "Global Settings"
        setOKButtonText("OK")
        headersPanel.bind(headers)
        paramsPanel.bind(params)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabs = javax.swing.JTabbedPane()
        tabs.addTab("Global Headers", panelFor(
            "Headers added to every request. A request header with the same name takes precedence.",
            headersPanel
        ))
        tabs.addTab("Global Params", panelFor(
            "Query parameters added to every request URL. A request parameter with the same name takes precedence.",
            paramsPanel
        ))
        tabs.addTab("General", buildGeneralPanel())
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(480))
            add(tabs, BorderLayout.CENTER)
        }
    }

    private fun panelFor(help: String, table: KeyValueTablePanel): JComponent {
        return JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8, 6)
            add(JBLabel(help).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.NORTH)
            add(table, BorderLayout.CENTER)
        }
    }

    private fun buildGeneralPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Request timeout (ms)"), timeoutField)
            .addComponent(followRedirects)
            .addComponent(
                JBLabel("The timeout applies to connecting and receiving the complete response.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                }
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
            add(form, BorderLayout.NORTH)
        }
    }

    override fun doValidate(): ValidationInfo? {
        headersPanel.commitPendingEdit()
        paramsPanel.commitPendingEdit()
        val timeout = timeoutField.text.trim().toIntOrNull()
            ?: return ValidationInfo("Timeout must be a number", timeoutField)
        if (timeout !in SmartRequestState.MIN_TIMEOUT_MS..SmartRequestState.MAX_TIMEOUT_MS) {
            return ValidationInfo(
                "Timeout must be between ${SmartRequestState.MIN_TIMEOUT_MS} and ${SmartRequestState.MAX_TIMEOUT_MS} ms",
                timeoutField
            )
        }
        return null
    }

    override fun doOKAction() {
        headersPanel.commitPendingEdit()
        paramsPanel.commitPendingEdit()
        state.globalHeaders = headers.withoutBlankKeys()
        state.globalParams = params.withoutBlankKeys()
        state.timeoutMs = timeoutField.text.trim().toInt()
        state.followRedirects = followRedirects.isSelected
        service.notifyChanged()
        super.doOKAction()
    }
}
