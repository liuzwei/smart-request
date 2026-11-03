package dev.smartrequest.ui

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.KeyValue
import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JFrame
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
}
