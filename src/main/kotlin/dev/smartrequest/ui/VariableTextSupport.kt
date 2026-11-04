package dev.smartrequest.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.DefaultHighlighter
import javax.swing.text.JTextComponent

/**
 * Adds `{{variable}}` highlighting and a lightweight completion popup to any Swing text component.
 * It intentionally does not register a global completion contributor, so it only affects Smart Request
 * fields and never interferes with normal editors.
 */
class VariableTextSupport(
    private val component: JTextComponent,
    private val variablesProvider: () -> Map<String, String>,
    private val highlightPathParameters: Boolean = false,
    private val requestFocusInPopup: Boolean = true,
    private val enableCompletion: Boolean = true
) {
    private val pattern = Regex("""\{\{\s*([^{}\s]+?)\s*}}""")
    private val pathPattern = Regex("""(?<!\{)\{([A-Za-z_][A-Za-z0-9_.-]*)(?::[^{}]*)?}(?!})""")
    private var popup: JBPopup? = null

    private val documentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = changed()
        override fun removeUpdate(e: DocumentEvent) = changed()
        override fun changedUpdate(e: DocumentEvent) = changed()

        private fun changed() {
            refresh()
            if (enableCompletion) maybeShowPopupAfterTyping()
        }
    }

    private val keyListener = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (e.isControlDown && e.keyCode == KeyEvent.VK_SPACE) {
                e.consume()
                showPopup()
                return
            }
            // The popup does not take focus when it is shown around a table cell, so the field keeps
            // receiving the keys that should close it.
            if (!requestFocusInPopup && (
                    e.keyCode == KeyEvent.VK_ESCAPE ||
                        e.keyCode == KeyEvent.VK_ENTER ||
                        e.keyCode == KeyEvent.VK_TAB
                    )
            ) {
                hidePopup()
            }
        }
    }

    private val focusListener = object : java.awt.event.FocusAdapter() {
        override fun focusLost(e: java.awt.event.FocusEvent) {
            // A click inside the chooser can move the focus to the popup window for a moment.
            // Hiding the popup right there cancels it before the click is delivered, which made
            // picking a variable in the Params / Path Params tables do nothing at all.
            SwingUtilities.invokeLater {
                if (!component.isFocusOwner && !isPopupFocused()) hidePopup()
            }
        }
    }

    private fun isPopupFocused(): Boolean {
        val current = popup ?: return false
        if (!current.isVisible) return false
        // getContent() is @NotNull but throws on a popup that is already disposed.
        val content = runCatching { current.content }.getOrNull() ?: return false
        val focusOwner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            ?: return false
        return SwingUtilities.isDescendingFrom(focusOwner, content)
    }

    init {
        component.document.addDocumentListener(documentListener)
        if (enableCompletion) {
            component.addKeyListener(keyListener)
            if (!requestFocusInPopup) component.addFocusListener(focusListener)
        }
        refresh()
    }

    fun refresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { refresh() }
            return
        }
        component.highlighter.removeAllHighlights()
        val text = component.text
        if (text.isEmpty()) return
        val variables = variablesProvider()
        pattern.findAll(text).forEach { match ->
            val known = variables.containsKey(match.groupValues[1])
            val color = if (known) KNOWN_BACKGROUND else UNKNOWN_BACKGROUND
            addHighlight(match.range.first, match.range.last + 1, color)
        }
        if (highlightPathParameters) {
            pathPattern.findAll(text).forEach { match ->
                addHighlight(match.range.first, match.range.last + 1, PATH_BACKGROUND)
            }
        }
    }

    private fun addHighlight(start: Int, end: Int, color: java.awt.Color) {
        runCatching {
            component.highlighter.addHighlight(
                start,
                end,
                DefaultHighlighter.DefaultHighlightPainter(color)
            )
        }
    }

    private fun maybeShowPopupAfterTyping() {
        val text = component.text
        val caret = component.caretPosition.coerceIn(0, text.length)
        if (caret >= 2 && text.length >= 2 && text.substring(caret - 2, caret) == "{{") {
            SwingUtilities.invokeLater { if (component.isShowing) showPopup() }
        }
    }

    private fun showPopup() {
        if (popup?.isVisible == true) return
        if (!component.isShowing) return
        val variables = variablesProvider()
        if (variables.isEmpty()) return
        val names = variables.keys.sorted()
        val newPopup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(names)
            .setTitle("Variables")
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(requestFocusInPopup)
            .setItemChosenCallback { insertVariable(it) }
            .createPopup()
        popup = newPopup

        val caret = runCatching { component.modelToView2D(component.caretPosition) }.getOrNull()
        if (caret != null) {
            val point = Point(caret.x.toInt(), caret.y.toInt() + caret.height.toInt())
            newPopup.showInScreenCoordinates(component, SwingUtilities.convertPoint(component, point, null))
        } else {
            newPopup.showUnderneathOf(component)
        }
    }

    private fun hidePopup() {
        popup?.cancel()
        popup = null
    }

    /** True while the variable chooser is on screen; used by the cell editor tests. */
    internal fun isPopupVisible(): Boolean = popup?.isVisible == true

    /** The chooser content, used by tests that exercise picking an entry. */
    internal fun popupContent(): javax.swing.JComponent? = popup?.content

    private fun insertVariable(name: String) {
        val text = component.text
        val caret = component.caretPosition.coerceIn(0, text.length)
        val before = text.substring(0, caret)
        val partial = PARTIAL_PATTERN.find(before)
        val start = partial?.range?.first ?: caret
        val inserted = "{{$name}}"
        val document = component.document
        // Edit the document directly instead of replacing the selection: the chooser may be
        // confirmed after the field lost focus (or its selection), and a stale selection would
        // overwrite the wrong range.
        runCatching {
            document.remove(start, caret - start)
            document.insertString(start, inserted, null)
        }
        component.caretPosition = (start + inserted.length).coerceAtMost(document.length)
        component.requestFocusInWindow()
        refresh()
    }

    companion object {
        private val PARTIAL_PATTERN = Regex("""\{\{[A-Za-z0-9_.-]*$""")
        private val KNOWN_BACKGROUND = JBColor(0xE1EDFF, 0x2C405B)
        private val UNKNOWN_BACKGROUND = JBColor(0xFFE0E0, 0x4A2B2B)
        private val PATH_BACKGROUND = JBColor(0xE3F4E3, 0x2D4A2D)
    }
}

class VariableTextField(
    variablesProvider: () -> Map<String, String>,
    highlightPathParameters: Boolean = false,
    requestFocusInPopup: Boolean = true,
    enableCompletion: Boolean = true
) : JBTextField() {
    private val support = VariableTextSupport(
        this,
        variablesProvider,
        highlightPathParameters,
        requestFocusInPopup,
        enableCompletion
    )

    fun refreshVariables() = support.refresh()

    /** True while the variable chooser is on screen; used by the cell editor tests. */
    internal fun isCompletionVisible(): Boolean = support.isPopupVisible()

    /** The chooser content, used by tests that exercise picking an entry. */
    internal fun completionContent(): javax.swing.JComponent? = support.popupContent()
}

class VariableTextArea(variablesProvider: () -> Map<String, String>) : JBTextArea() {
    private val support = VariableTextSupport(this, variablesProvider)

    fun refreshVariables() = support.refresh()
}
