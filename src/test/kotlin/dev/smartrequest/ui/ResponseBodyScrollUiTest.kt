package dev.smartrequest.ui

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextField
import dev.smartrequest.http.HttpResponseData
import java.awt.Container
import javax.swing.JFrame
import javax.swing.JScrollBar
import javax.swing.JScrollPane

/**
 * Regression test for long response bodies. [EditorTextField] hides the scrollbars of its embedded
 * editor, and Swing ignores mouse wheel events when no scrollbar is visible, so a long JSON body
 * used to stay stuck at the top. The response panel has to restore the scrollbars ("as needed") to
 * keep long responses scrollable.
 */
class ResponseBodyScrollUiTest : BasePlatformTestCase() {

    fun testLongJsonBodyIsScrollable() {
        val json = buildString {
            append("{\n")
            for (index in 1..400) {
                append("  \"key").append(index).append("\": \"value").append(index).append("\",\n")
            }
            append("  \"end\": true\n}")
        }
        val panel = EdtTestUtil.runInEdtAndGet<ResponsePanel, RuntimeException> {
            ResponsePanel(project).apply {
                setSize(560, 400)
                show(
                    HttpResponseData(
                        statusCode = 200,
                        statusText = "OK",
                        headers = listOf("Content-Type" to "application/json"),
                        body = json,
                        bodyBytes = json.length.toLong(),
                        durationMs = 12,
                        contentType = "application/json",
                        truncated = false,
                        textual = true
                    )
                )
            }
        }
        val frame = EdtTestUtil.runInEdtAndGet<JFrame, RuntimeException> {
            JFrame("response").apply {
                setSize(560, 420)
                add(panel)
                isVisible = true
            }
        }
        try {
            Thread.sleep(1000)
            // The test harness does not run the RepaintManager validation pass, so lay the
            // component tree out explicitly before inspecting the editor's scroll pane.
            EdtTestUtil.runInEdtAndWait<RuntimeException> { frame.validate() }
            Thread.sleep(300)

            val scrollbar = EdtTestUtil.runInEdtAndGet<JScrollBar, RuntimeException> {
                editorScrollPane(panel)?.verticalScrollBar ?: error("Editor scrollbar not found")
            }
            val scrolled = EdtTestUtil.runInEdtAndGet<String, RuntimeException> {
                "visible=${scrollbar.isVisible} extent=${scrollbar.visibleAmount} maximum=${scrollbar.maximum}"
            }
            assertTrue(
                "A long response body needs a vertical scrollbar, otherwise the mouse wheel " +
                        "ignores it: $scrolled",
                scrolled.startsWith("visible=true")
            )

            val offsets = EdtTestUtil.runInEdtAndGet<Pair<Int, Int>, RuntimeException> {
                val before = scrollingOffset(panel)
                scrollbar.value = scrollbar.maximum - scrollbar.visibleAmount
                before to scrollingOffset(panel)
            }
            assertTrue(
                "The response scrollbar must move the editor content (was ${offsets.first}, " +
                        "now ${offsets.second})",
                offsets.second > offsets.first
            )

            // Scrollbars are shown only when the body overflows, so the empty state stays clean.
            EdtTestUtil.runInEdtAndWait<RuntimeException> { panel.showEmptyState() }
            Thread.sleep(300)
            EdtTestUtil.runInEdtAndWait<RuntimeException> { frame.validate() }
            val emptyStateScrollbarVisible = EdtTestUtil.runInEdtAndGet<Boolean, RuntimeException> {
                editorScrollPane(panel)?.verticalScrollBar?.isVisible ?: false
            }
            assertFalse(
                "The empty response state must not reserve a scrollbar",
                emptyStateScrollbarVisible
            )
        } finally {
            EdtTestUtil.runInEdtAndWait<RuntimeException> { frame.dispose() }
        }
    }

    private fun editorScrollPane(root: Container): JScrollPane? {
        val field = findEditorTextField(root) ?: return null
        val content = field.editor?.contentComponent ?: return null
        return findScrollPanes(root).firstOrNull { it.viewport?.view === content }
    }

    private fun scrollingOffset(root: Container): Int {
        val field = findEditorTextField(root) ?: return -1
        return field.editor?.scrollingModel?.verticalScrollOffset ?: -1
    }

    private fun findEditorTextField(component: Container): EditorTextField? {
        if (component is EditorTextField) return component
        component.components.forEach { child ->
            if (child is Container) findEditorTextField(child)?.let { return it }
        }
        return null
    }

    private fun findScrollPanes(component: Container): List<JScrollPane> {
        val result = ArrayList<JScrollPane>()
        fun walk(container: Container) {
            if (container is JScrollPane) result += container
            container.components.forEach { if (it is Container) walk(it) }
        }
        walk(component)
        return result
    }
}
