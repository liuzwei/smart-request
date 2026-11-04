package dev.smartrequest.ui

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.KeyValue
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Covers the request editor table layout: the key and value columns must fill the available width
 * with a 3:4 ratio, and cells must become editable with a single click and stay editable.
 */
class KeyValueTablePanelTest : BasePlatformTestCase() {

    fun testKeyAndValueColumnsUseAThreeToFourRatio() {
        val widths = EdtTestUtil.runInEdtAndGet<List<Int>, RuntimeException> {
            newPanel().computeColumnWidths(600)
        }

        assertEquals(2, widths.size)
        assertEquals(600 - JBUI.scale(34), widths.sum())
        assertTrue("value column is the widest", widths[1] > widths[0])
        assertTrue("key column is no longer squeezed", widths[0] > JBUI.scale(200))
    }

    fun testColumnsGrowWithThePanel() {
        val (narrow, wide) = EdtTestUtil.runInEdtAndGet<Pair<List<Int>, List<Int>>, RuntimeException> {
            val panel = newPanel()
            panel.computeColumnWidths(500) to panel.computeColumnWidths(1000)
        }

        assertTrue(wide[0] > narrow[0])
        assertTrue(wide[1] > narrow[1])
    }

    fun testTableHasNoOriginColumn() {
        val columns = EdtTestUtil.runInEdtAndGet<List<String>, RuntimeException> {
            val table = newPanel().tableView()
            (0 until table.columnModel.columnCount).map { table.columnModel.getColumn(it).headerValue.toString() }
        }

        assertEquals(listOf("", "Key", "Value"), columns)
    }

    fun testClickingACellStartsEditingIt() {
        val values = mutableListOf(KeyValue.of("authorization", "Bearer 1234567890"))
        val started = EdtTestUtil.runInEdtAndGet<Boolean, RuntimeException> {
            val panel = newPanel()
            panel.bind(values)
            panel.beginEdit(0, 1)
        }

        assertTrue(started)
    }

    fun testGlobalRowsStayReadOnly() {
        val values = mutableListOf(KeyValue.of("X-Trace", "1"))
        val fixed = listOf(KeyValue.of("Authorization", "Bearer global"))
        val (editStarted, toggled) = EdtTestUtil.runInEdtAndGet<Pair<Boolean, Boolean>, RuntimeException> {
            val panel = newPanel()
            panel.bind(values, fixed)
            panel.beginEdit(0, 1) to panel.toggleEnabled(0)
        }

        assertFalse(editStarted)
        assertFalse(toggled)
    }

    fun testCheckboxColumnTogglesTheEnabledFlag() {
        val value = KeyValue.of("X-Test", "yes")
        val toggled = EdtTestUtil.runInEdtAndGet<Boolean, RuntimeException> {
            val panel = newPanel()
            panel.bind(mutableListOf(value))
            panel.toggleEnabled(0)
        }

        assertTrue(toggled)
        assertFalse(value.enabled)
    }

    fun testAddedRowsAreEditableRightAway() {
        val values = mutableListOf<KeyValue>()
        val started = EdtTestUtil.runInEdtAndGet<Boolean, RuntimeException> {
            val panel = newPanel()
            panel.bind(values)
            panel.addRow()
            panel.beginEdit(0, 1)
        }

        assertTrue(started)
        assertEquals(1, values.size)
    }

    fun testSingleMouseClickOpensTheInlineEditor() {
        val cell = EdtTestUtil.runInEdtAndGet<Pair<Int, Int>?, RuntimeException> {
            val panel = newPanel()
            panel.bind(mutableListOf(KeyValue.of("authorization", "Bearer 1234567890")))
            click(panel, row = 0, viewColumn = 2)
            panel.editingCell()
        }

        assertEquals(0 to 2, cell)
    }

    fun testMouseClickOnTheCheckboxOnlyTogglesTheRow() {
        val value = KeyValue.of("X-Test", "yes")
        val editing = EdtTestUtil.runInEdtAndGet<Pair<Int, Int>?, RuntimeException> {
            val panel = newPanel()
            panel.bind(mutableListOf(value))
            click(panel, row = 0, viewColumn = 0)
            panel.editingCell()
        }

        assertFalse(value.enabled)
        assertNull(editing)
    }

    fun testResizingTheTableKeepsTheOpenEditor() {
        val cell = EdtTestUtil.runInEdtAndGet<Pair<Int, Int>?, RuntimeException> {
            val panel = newPanel()
            panel.bind(mutableListOf(KeyValue.of("authorization", "Bearer 1234567890")))
            val table = panel.tableView()
            table.setSize(640, 200)
            table.doLayout()
            panel.beginEdit(0, 1)
            table.setSize(560, 200)
            table.doLayout()
            panel.editingCell()
        }

        assertEquals(0 to 1, cell)
    }

    fun testLayoutAppliesTheComputedWidthsAndFillsTheTable() {
        val (applied, expected) = EdtTestUtil.runInEdtAndGet<Pair<List<Int>, List<Int>>, RuntimeException> {
            val panel = newPanel()
            panel.bind(mutableListOf(KeyValue.of("authorization", "Bearer 1234567890")))
            val table = panel.tableView()
            table.setSize(640, 200)
            table.doLayout()
            panel.appliedColumnWidths() to panel.computeColumnWidths(640)
        }

        assertEquals(expected, applied)
        val used = applied.sum() + JBUI.scale(34)
        assertEquals("columns must fill the whole table width", 640, used)
    }

    fun testHeaderKeyEditorIsAChooserPanelButValueStaysAPlainField() {
        val (keyIsPanel, valueIsField) = EdtTestUtil.runInEdtAndGet<Pair<Boolean, Boolean>, RuntimeException> {
            val panel = KeyValueTablePanel(keySuggestions = listOf("Authorization", "Content-Type"))
            panel.bind(mutableListOf(KeyValue.of("", "")))
            panel.beginEdit(0, 1)
            val key = panel.tableView().editorComponent is JPanel
            panel.tableView().removeEditor()
            panel.beginEdit(0, 2)
            val value = panel.tableView().editorComponent is JTextField
            key to value
        }

        assertTrue(keyIsPanel)
        assertTrue(valueIsField)
    }

    fun testChoosingAHeaderSuggestionFillsTheKeyCell() {
        val values = mutableListOf(KeyValue.of("", "Bearer"))
        EdtTestUtil.runInEdtAndGet<Unit, RuntimeException> {
            val panel = KeyValueTablePanel(keySuggestions = listOf("Authorization", "Content-Type"))
            panel.bind(values)
            panel.chooseKeySuggestion(0, "Authorization")
        }

        assertEquals("Authorization", values.single().key)
    }

    fun testPanelsWithoutSuggestionsKeepThePlainKeyField() {
        val plain = EdtTestUtil.runInEdtAndGet<Boolean, RuntimeException> {
            val panel = KeyValueTablePanel()
            panel.bind(mutableListOf(KeyValue.of("", "")))
            panel.beginEdit(0, 1)
            panel.tableView().editorComponent is JTextField
        }

        assertTrue(plain)
    }

    private fun newPanel(): KeyValueTablePanel = KeyValueTablePanel()

    private fun click(panel: KeyValueTablePanel, row: Int, viewColumn: Int) {
        val table = panel.tableView()
        table.setSize(640, 200)
        table.doLayout()
        val rect = table.getCellRect(row, viewColumn, true)
        table.dispatchEvent(
            MouseEvent(
                table,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                0,
                rect.x + rect.width / 2,
                rect.y + rect.height / 2,
                1,
                false,
                MouseEvent.BUTTON1
            )
        )
        UIUtil.dispatchAllInvocationEvents()
    }
}
