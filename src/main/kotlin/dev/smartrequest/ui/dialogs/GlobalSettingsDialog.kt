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
import dev.smartrequest.service.SmartRequestProjectService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Project-wide request defaults. Global headers and parameters are configured per environment, in
 * the Environment Settings dialog, so they can differ between local, dev and prod.
 */
class GlobalSettingsDialog(
    project: Project,
    private val state: SmartRequestState,
    private val service: SmartRequestProjectService
) : DialogWrapper(project, true) {

    private val timeoutField = JBTextField(state.timeoutMs.toString())
    private val followRedirects = JCheckBox("Follow HTTP redirects", state.followRedirects)

    init {
        title = "Request Settings"
        setOKButtonText("OK")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Request timeout (ms)").apply { font = font.deriveFont(Font.BOLD) }, timeoutField)
            .addComponent(followRedirects)
            .addComponent(
                JBLabel("The timeout applies to connecting and receiving the complete response.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                }
            )
            .addComponent(
                JBLabel(
                    "Global headers and query parameters are configured per environment: " +
                            "open Environments and use the Global Headers / Global Params tabs."
                ).apply { foreground = UIUtil.getContextHelpForeground() }
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(240))
            add(form, BorderLayout.NORTH)
        }
    }

    override fun doValidate(): ValidationInfo? {
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
        state.timeoutMs = timeoutField.text.trim().toInt()
        state.followRedirects = followRedirects.isSelected
        service.notifyChanged()
        super.doOKAction()
    }
}
