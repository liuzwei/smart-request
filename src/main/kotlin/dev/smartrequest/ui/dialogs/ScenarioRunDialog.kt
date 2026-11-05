package dev.smartrequest.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.http.VariableResolver
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.scenario.ScenarioValidator
import dev.smartrequest.service.SmartRequestProjectService
import dev.smartrequest.ui.KeyValueTablePanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel

/**
 * Run inputs: the environment, the scenario parameters, and a value for every variable that no
 * upstream step captures. Nothing is persisted except the scenario's own parameter defaults; the
 * values entered here apply to this run only.
 *
 * The dialog re-validates on every edit, so its Run button is the only place that decides whether
 * the flow may start: structural errors keep it disabled even if the user filled in every variable.
 */
class ScenarioRunDialog(
    project: Project,
    private val scenario: ScenarioConfig,
    private val service: SmartRequestProjectService,
    initialEnvironment: EnvironmentConfig?
) : DialogWrapper(project) {

    private val parameters = scenario.parameters
        .filter { it.key.isNotBlank() }
        .map { it.deepCopy() }
        .toMutableList()

    /** Variables this dialog added itself; they are not part of the scenario's defaults. */
    private val autoVariables = LinkedHashSet<String>()
    private var requiredNames: Set<String> = emptySet()
    private var blankNames: Set<String> = emptySet()
    private var report: ScenarioValidator.Report = ScenarioValidator.Report()

    private val environmentCombo = JComboBox<EnvironmentConfig>().apply {
        maximumRowCount = 12
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is EnvironmentConfig) {
                    text = value.name
                    toolTipText = value.baseUrl
                }
                return this
            }
        }
        addActionListener { refreshValidation() }
    }
    private val paramsPanel = KeyValueTablePanel(
        onChanged = { refreshValidation() },
        showLegend = false
    )
    private val missingLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyTop(4)
    }
    private val statusLabel = JBLabel().apply {
        border = JBUI.Borders.emptyTop(6)
        font = font.deriveFont(Font.BOLD)
    }
    private val hintLabel = JBLabel(
        "Values apply to this run only; the environment is not updated."
    ).apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    init {
        title = "Run ${scenario.name}"
        setOKButtonText("Run")
        init()
        val environments = service.state().environments
        environmentCombo.model = DefaultComboBoxModel(environments.toTypedArray())
        val selected = environments.firstOrNull { it.id == initialEnvironment?.id }
            ?: environments.firstOrNull()
        if (selected != null) environmentCombo.selectedItem = selected
        paramsPanel.bind(parameters)
        refreshValidation()
    }

    override fun createCenterPanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(4, 0)
            preferredSize = Dimension(JBUI.scale(660), JBUI.scale(400))
        }
        val top = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            isOpaque = false
            val environment = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(JBLabel("Environment").apply { font = font.deriveFont(Font.BOLD) })
                add(environmentCombo)
            }
            add(environment, BorderLayout.NORTH)
            add(hintLabel, BorderLayout.CENTER)
        }
        panel.add(top, BorderLayout.NORTH)
        val center = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            isOpaque = false
            add(paramsPanel, BorderLayout.CENTER)
            add(missingLabel, BorderLayout.SOUTH)
        }
        panel.add(center, BorderLayout.CENTER)
        panel.add(statusLabel, BorderLayout.SOUTH)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = environmentCombo

    override fun doOKAction() {
        paramsPanel.commitPendingEdit()
        refreshValidation()
        if (!report.canRun) return
        super.doOKAction()
    }

    fun selectedEnvironment(): EnvironmentConfig? = environmentCombo.selectedItem as? EnvironmentConfig

    /** Enabled parameter rows as a map; blank keys are ignored. */
    fun parameters(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        parameters.filter { it.enabled }.forEach { row ->
            val key = row.key.trim()
            if (key.isNotEmpty()) result[key] = row.value
        }
        return result
    }

    private fun refreshValidation() {
        val environment = selectedEnvironment() ?: service.activeEnvironment()

        // "Missing" is computed without the rows this dialog added itself; otherwise adding an empty
        // row would make the variable look supplied and the Run button would never explain why it is
        // disabled.
        val userParameters = parameters
            .filter { it.enabled && it.key.trim().isNotEmpty() && it.key.trim() !in autoVariables }
            .associate { it.key.trim() to it.value }
        val missingReport = validate(environment, userParameters)
        requiredNames = missingReport.missingVariables

        val added = requiredNames.filter { name -> parameters.none { it.key == name } }
        if (added.isNotEmpty()) {
            added.forEach { name ->
                autoVariables.add(name)
                parameters.add(KeyValue.of(name, ""))
            }
            paramsPanel.bind(parameters)
        }
        val stale = parameters.filter { row ->
            row.key in autoVariables && row.key !in requiredNames && row.value.isBlank()
        }
        if (stale.isNotEmpty()) {
            parameters.removeAll(stale)
            stale.forEach { autoVariables.remove(it.key) }
            paramsPanel.bind(parameters)
        }

        report = validate(environment, parameters())
        blankNames = requiredNames.filter { name ->
            parameters.none { it.key == name && it.enabled && it.value.isNotBlank() }
        }.toSet()
        updateLabels()
        okAction.isEnabled = report.canRun && blankNames.isEmpty()
    }

    private fun validate(
        environment: EnvironmentConfig?,
        values: Map<String, String>
    ): ScenarioValidator.Report = ScenarioValidator.validate(
        scenario = scenario,
        requestSource = service::findRequest,
        environmentVariables = VariableResolver.environmentVariables(environment),
        parameters = values
    )

    private fun updateLabels() {
        if (requiredNames.isNotEmpty()) {
            val detail = if (blankNames.isEmpty()) {
                "Supplied for this run only: ${requiredNames.joinToString(", ")}."
            } else {
                "Fill in: ${blankNames.joinToString(", ")}. These values exist for this run only."
            }
            missingLabel.text = detail
            missingLabel.isVisible = true
        } else {
            missingLabel.text = ""
            missingLabel.isVisible = false
        }

        val error = report.errors.firstOrNull()
        statusLabel.text = when {
            error != null -> "Cannot run: ${error.message}"
            blankNames.isNotEmpty() -> "Cannot run: fill in ${blankNames.joinToString(", ")}"
            report.warnings.isNotEmpty() -> "Ready to run · ${report.warnings.size} warning(s)"
            report.requestNodeCount == 0 -> "Add at least one request step"
            else -> "Ready to run"
        }
        statusLabel.toolTipText = if (report.problems.isEmpty()) {
            null
        } else {
            report.problems.joinToString("\n") { problem ->
                (if (problem.severity == ScenarioValidator.Severity.ERROR) "Error: " else "Warning: ") +
                        problem.message
            }
        }
        statusLabel.foreground = when {
            error != null -> JBColor.RED
            blankNames.isNotEmpty() -> WARNING_COLOR
            report.warnings.isNotEmpty() -> WARNING_COLOR
            report.requestNodeCount == 0 -> JBColor.RED
            else -> READY_COLOR
        }
    }

    // Test hooks.

    internal fun parameterNames(): List<String> = parameters.filter { it.enabled }.map { it.key }

    internal fun parameterValue(name: String): String? =
        parameters.firstOrNull { it.key == name }?.value

    internal fun setParameter(name: String, value: String) {
        val index = parameters.indexOfFirst { it.key == name }
        if (index >= 0) {
            paramsPanel.setRowValues(index, name, value)
        } else {
            parameters.add(KeyValue.of(name, value))
            paramsPanel.bind(parameters)
        }
        refreshValidation()
    }

    internal fun missingVariableNames(): Set<String> = requiredNames

    internal fun blankVariableNames(): Set<String> = blankNames

    internal fun statusText(): String = statusLabel.text

    internal fun isRunEnabled(): Boolean = okAction.isEnabled

    internal fun selectEnvironment(name: String) {
        val environment = service.state().environments.firstOrNull { it.name == name } ?: return
        environmentCombo.selectedItem = environment
        refreshValidation()
    }

    internal fun selectedEnvironmentName(): String? = selectedEnvironment()?.name

    internal fun environmentNames(): List<String> =
        (0 until environmentCombo.itemCount).mapNotNull { environmentCombo.getItemAt(it).name }

    internal fun reportForTest(): ScenarioValidator.Report = report

    companion object {
        private val READY_COLOR = JBColor(0x1A7F37, 0x73BD79)
        private val WARNING_COLOR = JBColor(0xA15C00, 0xD6A26A)
    }
}
