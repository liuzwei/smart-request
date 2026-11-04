package dev.smartrequest.ui

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.extract.ExtractionReport
import dev.smartrequest.extract.ExtractionResult
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.ResponseExtractor
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JTabbedPane
import javax.swing.JTable

/**
 * The Extract rules live in the request editor and the captured values are shown read-only in the
 * response panel; both tabs must stay in sync with the model.
 */
class ExtractTabsUiTest : BasePlatformTestCase() {

    fun testResponsePanelListsTheCapturedValues() {
        val (title, rows) = EdtTestUtil.runInEdtAndGet<Pair<String, Int>, RuntimeException> {
            val panel = ResponsePanel(project)
            panel.setSize(560, 420)
            panel.setExtractionReport(
                ExtractionReport(
                    listOf(
                        ExtractionResult("a", "token", "data.token", "abc", added = true),
                        ExtractionResult("b", "userId", "data.user.id", error = "key 'id' is not present in 'data.user'")
                    )
                )
            )
            val tabs = findTabs(panel) ?: error("tabs not found")
            tabs.getTitleAt(2) to (findTable(tabs.getComponentAt(2))?.model?.rowCount ?: -1)
        }

        assertEquals("Variables (2)", title)
        assertEquals(2, rows)
    }

    fun testResponsePanelReturnsToTheEmptyState() {
        val (title, rows) = EdtTestUtil.runInEdtAndGet<Pair<String, Int>, RuntimeException> {
            val panel = ResponsePanel(project)
            panel.setSize(560, 420)
            panel.setExtractionReport(
                ExtractionReport(listOf(ExtractionResult("a", "token", "data.token", "abc")))
            )
            panel.setExtractionReport(null)
            val tabs = findTabs(panel) ?: error("tabs not found")
            tabs.getTitleAt(2) to (findTable(tabs.getComponentAt(2))?.model?.rowCount ?: -1)
        }

        assertEquals("Variables (0)", title)
        assertEquals(0, rows)
    }

    fun testRequestEditorShowsTheRuleCountInTheTabTitle() {
        val title = EdtTestUtil.runInEdtAndGet<String, RuntimeException> {
            val panel = RequestEditorPanel({}, {}, {}, { emptyMap() })
            val request = HttpRequestConfig(
                extractors = mutableListOf(
                    ResponseExtractor(variable = "token", source = "data.token"),
                    ResponseExtractor(variable = "id", source = "data.id")
                )
            )
            panel.open(request)
            findTabs(panel)?.getTitleAt(4) ?: error("tabs not found")
        }

        assertEquals("Extract (2)", title)
    }

    private fun findTabs(component: JComponent): JTabbedPane? {
        if (component is JTabbedPane) return component
        if (component is Container) {
            component.components.forEach { child ->
                (child as? JComponent)?.let { found -> findTabs(found)?.let { return it } }
            }
        }
        return null
    }

    private fun findTable(component: java.awt.Component?): JTable? {
        if (component is JTable) return component
        if (component is Container) {
            component.components.forEach { child ->
                findTable(child)?.let { return it }
            }
        }
        return null
    }
}
