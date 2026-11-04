package dev.smartrequest.ui

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.extract.ExtractionReport
import dev.smartrequest.extract.ExtractionResult
import dev.smartrequest.model.ResponseExtractor
import javax.swing.JLabel

class ExtractorTablePanelTest : BasePlatformTestCase() {

    fun testTableHasTheExpectedColumns() {
        val headers = EdtTestUtil.runInEdtAndGet<List<String>, RuntimeException> {
            val panel = newPanel()
            (0 until panel.tableView().columnModel.columnCount).map {
                panel.tableView().columnModel.getColumn(it).headerValue.toString()
            }
        }

        assertEquals(listOf("", "Variable", "Source", "Last run"), headers)
    }

    fun testAddRowStartsEditingTheSource() {
        val rows = mutableListOf<ResponseExtractor>()
        val cell = EdtTestUtil.runInEdtAndGet<Pair<Int, Int>?, RuntimeException> {
            val panel = newPanel()
            panel.bind(rows)
            panel.addRow()
            panel.editingCell()
        }

        assertEquals(1, rows.size)
        assertEquals(0 to ExtractorTablePanel.SOURCE_COLUMN, cell)
    }

    fun testEditingTheSourceDerivesTheVariableName() {
        val row = ResponseExtractor()
        EdtTestUtil.runInEdtAndGet<Unit, RuntimeException> {
            val panel = newPanel()
            panel.bind(mutableListOf(row))
            panel.setRowSource(0, "data.user.id")
        }

        assertEquals("id", row.variable)
    }

    fun testAnExplicitVariableNameIsNotOverwritten() {
        val row = ResponseExtractor()
        EdtTestUtil.runInEdtAndGet<Unit, RuntimeException> {
            val panel = newPanel()
            panel.bind(mutableListOf(row))
            panel.setRowVariable(0, "token")
            panel.setRowSource(0, "data.result.accessToken")
        }

        assertEquals("token", row.variable)
    }

    fun testRemovingSelectedRows() {
        val rows = mutableListOf(ResponseExtractor(variable = "a"), ResponseExtractor(variable = "b"))
        EdtTestUtil.runInEdtAndGet<Unit, RuntimeException> {
            val panel = newPanel()
            panel.bind(rows)
            panel.tableView().setRowSelectionInterval(0, 0)
            panel.removeSelectedRows()
        }

        assertEquals(1, rows.size)
        assertEquals("b", rows.single().variable)
    }

    fun testTheCheckboxTogglesTheRule() {
        val row = ResponseExtractor(variable = "a", source = "data.a")
        EdtTestUtil.runInEdtAndGet<Unit, RuntimeException> {
            val panel = newPanel()
            panel.bind(mutableListOf(row))
            panel.toggleEnabled(0)
        }

        assertFalse(row.enabled)
    }

    fun testDisabledPanelDoesNotStartEditing() {
        val started = EdtTestUtil.runInEdtAndGet<Boolean, RuntimeException> {
            val panel = newPanel()
            panel.bind(mutableListOf(ResponseExtractor(variable = "a", source = "data.a")))
            panel.setEnabledState(false)
            panel.beginEdit(0, ExtractorTablePanel.VARIABLE_COLUMN)
        }

        assertFalse(started)
    }

    fun testResultsAreMatchedByExtractorId() {
        val row = ResponseExtractor(variable = "token", source = "data.token")
        val text = EdtTestUtil.runInEdtAndGet<String, RuntimeException> {
            val panel = newPanel()
            panel.bind(mutableListOf(row))
            panel.setResults(
                ExtractionReport(
                    listOf(
                        ExtractionResult(
                            extractorId = row.id,
                            variable = "token",
                            source = "data.token",
                            value = "v",
                            added = true
                        )
                    )
                )
            )
            val table = panel.tableView()
            table.setSize(640, 120)
            table.doLayout()
            val renderer = table.getCellRenderer(0, ExtractorTablePanel.RESULT_COLUMN)
            (table.prepareRenderer(renderer, 0, ExtractorTablePanel.RESULT_COLUMN) as JLabel).text
        }

        assertEquals("Added", text)
    }

    fun testFailedRulesAreShownWithTheirReason() {
        val row = ResponseExtractor(variable = "token", source = "data.token")
        val text = EdtTestUtil.runInEdtAndGet<String, RuntimeException> {
            val panel = newPanel()
            panel.bind(mutableListOf(row))
            panel.setResults(
                ExtractionReport(
                    listOf(
                        ExtractionResult(
                            extractorId = row.id,
                            variable = "token",
                            source = "data.token",
                            error = "key 'token' is not present in 'data'"
                        )
                    )
                )
            )
            val table = panel.tableView()
            table.setSize(640, 120)
            table.doLayout()
            val renderer = table.getCellRenderer(0, ExtractorTablePanel.RESULT_COLUMN)
            (table.prepareRenderer(renderer, 0, ExtractorTablePanel.RESULT_COLUMN) as JLabel).text
        }

        assertEquals("key 'token' is not present in 'data'", text)
    }

    private fun newPanel(): ExtractorTablePanel = ExtractorTablePanel()
}
