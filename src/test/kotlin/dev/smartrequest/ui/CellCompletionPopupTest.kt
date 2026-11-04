package dev.smartrequest.ui

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.KeyValue
import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.JList
import javax.swing.JTextField

/**
 * Asking for variable completion inside a cell must not interrupt editing: the editor has to stay
 * open with the keyboard focus, and text typed into it must never be lost.
 */
class CellCompletionPopupTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        if (GraphicsEnvironment.isHeadless()) {
            throw IllegalStateException("A display is required for this test")
        }
    }

    fun testAskingForCompletionKeepsTheEditorOpenAndFocused() {
        var editorCell: Pair<Int, Int>? = null
        var focusStillInEditor = false

        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            val frame = JFrame("smart-request test")
            try {
                val panel = KeyValueTablePanel(variablesProvider = { mapOf("token" to "abc", "tenantCode" to "acme") })
                panel.bind(mutableListOf(KeyValue.of("X-Test", "y")))
                frame.contentPane.add(panel)
                frame.setSize(700, 300)
                frame.isVisible = true
                panel.tableView().doLayout()

                panel.beginEdit(0, 2)
                val field = panel.tableView().editorComponent as? VariableTextField
                checkNotNull(field) { "cell editor must be a VariableTextField" }
                field.requestFocusInWindow()
                // Ctrl+Space asks for the variable chooser, exactly like a user would.
                field.dispatchEvent(
                    KeyEvent(
                        field,
                        KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),
                        InputEvent.CTRL_DOWN_MASK,
                        KeyEvent.VK_SPACE,
                        KeyEvent.CHAR_UNDEFINED
                    )
                )
                UIUtil.dispatchAllInvocationEvents()

                editorCell = panel.editingCell()
                focusStillInEditor = field.isFocusOwner
            } finally {
                frame.dispose()
            }
        }

        assertEquals(0 to 2, editorCell)
        assertTrue("the editor must keep the keyboard focus", focusStillInEditor)
    }

    fun testTypedValueIsKeptEvenWhenTheEditorCloses() {
        val values = mutableListOf(KeyValue.of("X-Test", ""))
        val stored = EdtTestUtil.runInEdtAndGet<String, RuntimeException> {
            val panel = KeyValueTablePanel(variablesProvider = { mapOf("token" to "abc") })
            panel.bind(values)
            panel.beginEdit(0, 2)
            val field = panel.tableView().editorComponent as JTextField
            field.text = "Bearer {{token}}"
            panel.tableView().removeEditor()
            values.first().value
        }

        assertEquals("Bearer {{token}}", stored)
    }

    fun testClickingAChooserEntryInsertsTheVariable() {
        assertEquals("{{x-code}}", pickVariable("{{"))
    }

    fun testClickingAChooserEntryReplacesOnlyThePartialPrefix() {
        assertEquals("Bearer {{x-code}}", pickVariable("Bearer {{"))
    }

    /** Types [initial], opens the chooser and clicks `x-code`; returns the resulting field text. */
    private fun pickVariable(initial: String): String? {
        var text: String? = null

        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            val frame = JFrame("smart-request test")
            try {
                val panel = KeyValueTablePanel(variablesProvider = { mapOf("token" to "abc", "x-code" to "c") })
                panel.bind(mutableListOf(KeyValue.of("X-Test", "")))
                frame.contentPane.add(panel)
                frame.setSize(700, 300)
                frame.isVisible = true
                panel.tableView().setSize(660, 240)
                panel.tableView().doLayout()

                panel.beginEdit(0, 2)
                val field = panel.tableView().editorComponent as VariableTextField
                field.text = initial
                field.caretPosition = initial.length
                field.dispatchEvent(
                    KeyEvent(
                        field,
                        KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),
                        InputEvent.CTRL_DOWN_MASK,
                        KeyEvent.VK_SPACE,
                        KeyEvent.CHAR_UNDEFINED
                    )
                )
                UIUtil.dispatchAllInvocationEvents()

                val list = findList(field.completionContent()) ?: error("chooser list not found")
                val index = (0 until list.model.size).first { list.model.getElementAt(it) == "x-code" }
                val bounds = list.getCellBounds(index, index)
                list.dispatchEvent(clickEvent(list, bounds.x + 4, bounds.y + bounds.height / 2, MouseEvent.MOUSE_PRESSED))
                list.dispatchEvent(clickEvent(list, bounds.x + 4, bounds.y + bounds.height / 2, MouseEvent.MOUSE_RELEASED))
                UIUtil.dispatchAllInvocationEvents()
                text = field.text
            } finally {
                frame.dispose()
            }
        }

        return text
    }

    private fun clickEvent(list: JList<*>, x: Int, y: Int, id: Int): MouseEvent = MouseEvent(
        list,
        id,
        System.currentTimeMillis(),
        0,
        x,
        y,
        1,
        false,
        MouseEvent.BUTTON1
    )

    private fun findList(component: java.awt.Component?): JList<*>? {
        if (component is JList<*>) return component
        if (component is java.awt.Container) {
            component.components.forEach { child ->
                findList(child)?.let { return it }
            }
        }
        return null
    }
}
