package dev.smartrequest.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.RequestHistoryEntry
import dev.smartrequest.service.SmartRequestProjectService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class HistoryDialog(
    project: Project,
    private val service: SmartRequestProjectService
) : DialogWrapper(project, true) {

    private val entries = service.state().history.map { it }.toMutableList()
    private val model = HistoryTableModel(entries)
    private val table = JBTable(model).apply {
        rowHeight = JBUI.scale(26)
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        columnModel.getColumn(0).preferredWidth = JBUI.scale(120)
        columnModel.getColumn(0).maxWidth = JBUI.scale(140)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(70)
        columnModel.getColumn(1).maxWidth = JBUI.scale(80)
        columnModel.getColumn(2).preferredWidth = JBUI.scale(110)
        columnModel.getColumn(2).maxWidth = JBUI.scale(140)
        columnModel.getColumn(3).preferredWidth = JBUI.scale(440)
        columnModel.getColumn(4).preferredWidth = JBUI.scale(80)
        columnModel.getColumn(4).maxWidth = JBUI.scale(100)
        columnModel.getColumn(5).preferredWidth = JBUI.scale(100)
        columnModel.getColumn(5).maxWidth = JBUI.scale(130)
        setDefaultRenderer(String::class.java, HistoryCellRenderer())
    }

    var restoredEntry: RequestHistoryEntry? = null
        private set

    init {
        title = "Request History"
        setOKButtonText("Restore Request")
        setCancelButtonText("Close")
        init()
        isOKActionEnabled = false
        table.selectionModel.addListSelectionListener {
            isOKActionEnabled = table.selectedRow >= 0
        }
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && table.selectedRow >= 0) {
                    doOKAction()
                }
            }
        })
    }

    override fun createCenterPanel(): JComponent {
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 4, 8, 4)
            add(
                JBLabel("History is stored in .idea/smart-request.xml, newest first (up to 100 entries).")
                    .apply { foreground = UIUtil.getContextHelpForeground() },
                BorderLayout.WEST
            )
            add(JButton("Clear History").apply {
                addActionListener { clearHistory() }
            }, BorderLayout.EAST)
        }
        return JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(JBUI.scale(1000), JBUI.scale(540))
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(table).apply { border = JBUI.Borders.customLine(JBColor.border()) }, BorderLayout.CENTER)
        }
    }

    override fun doOKAction() {
        val entry = table.selectedRow.takeIf { it >= 0 }?.let { entries.getOrNull(it) } ?: return
        restoredEntry = entry
        super.doOKAction()
    }

    private fun clearHistory() {
        if (entries.isEmpty()) return
        val answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
            table,
            "Delete all request history? This cannot be undone.",
            "Clear History",
            com.intellij.openapi.ui.Messages.getQuestionIcon()
        )
        if (answer != com.intellij.openapi.ui.Messages.YES) return
        entries.clear()
        service.clearHistory()
        model.fireTableDataChanged()
        isOKActionEnabled = false
    }

    private class HistoryTableModel(
        private val entries: MutableList<RequestHistoryEntry>
    ) : AbstractTableModel() {
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        override fun getRowCount(): Int = entries.size
        override fun getColumnCount(): Int = 6

        override fun getColumnName(column: Int): String = when (column) {
            0 -> "Time"
            1 -> "Method"
            2 -> "Result"
            3 -> "URL"
            4 -> "Duration"
            else -> "Environment"
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = entries.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                0 -> timeFormat.format(Date(entry.timestamp))
                1 -> entry.method
                2 -> when {
                    entry.errorMessage != null -> "Failed"
                    entry.statusCode == 0 -> "Failed"
                    entry.statusText.isBlank() -> entry.statusCode.toString()
                    else -> "${entry.statusCode} ${entry.statusText}"
                }
                3 -> entry.url
                4 -> "${entry.durationMs} ms"
                else -> entry.environmentName
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    }

    private class HistoryCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, selected, hasFocus, row, column)
            if (component is JLabel) {
                component.toolTipText = value?.toString()
                if (!selected) {
                    component.foreground = table.foreground
                    if (column == 2) {
                        val text = value?.toString().orEmpty()
                        val code = text.takeWhile { it.isDigit() }.toIntOrNull()
                        component.foreground = when {
                            text == "Failed" -> JBColor(0xC7222D, 0xFF6B68)
                            code != null && code in 200..299 -> JBColor(0x1A7F37, 0x73BD79)
                            code != null && code >= 500 -> JBColor(0xC7222D, 0xFF6B68)
                            code != null && code >= 400 -> JBColor(0xA15C00, 0xD6A26A)
                            else -> table.foreground
                        }
                    }
                }
            }
            return component
        }
    }
}
