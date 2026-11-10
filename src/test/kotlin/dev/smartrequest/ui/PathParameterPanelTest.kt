package dev.smartrequest.ui

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import dev.smartrequest.model.HttpRequestConfig

class PathParameterPanelTest : BasePlatformTestCase() {

    fun testNameAndValueColumnsSplitTheWidth() {
        val widths = EdtTestUtil.runInEdtAndGet<List<Int>, RuntimeException> {
            val panel = PathParameterPanel(variablesProvider = { emptyMap() })
            panel.bind(HttpRequestConfig(url = "{{baseUrl}}/users/{id}"))
            panel.computeColumnWidths(600)
        }

        assertEquals(2, widths.size)
        assertEquals(600, widths.sum())
        assertTrue("value column takes most of the row", widths[1] > widths[0] * 2)
    }

    fun testOnlyTheValueColumnIsEditable() {
        val (nameStarted, valueStarted) = EdtTestUtil.runInEdtAndGet<Pair<Boolean, Boolean>, RuntimeException> {
            val panel = PathParameterPanel(variablesProvider = { emptyMap() })
            panel.bind(HttpRequestConfig(url = "{{baseUrl}}/users/{id}/orders/{orderId}"))
            panel.beginEdit(0, 0) to panel.beginEdit(0, 1)
        }

        assertFalse(nameStarted)
        assertTrue(valueStarted)
    }

    fun testPathParametersAreDetectedFromTheUrl() {
        val count = EdtTestUtil.runInEdtAndGet<Int, RuntimeException> {
            val panel = PathParameterPanel(variablesProvider = { emptyMap() })
            panel.bind(HttpRequestConfig(url = "{{baseUrl}}/users/{id}/orders/{orderId}"))
            panel.count()
        }

        assertEquals(2, count)
    }

    fun testValueSurvivesATemporarilyMissingPlaceholder() {
        val (first, afterRemoval, afterRestore) = EdtTestUtil.runInEdtAndGet<Triple<Int, Int, List<Pair<String, String>>>, RuntimeException> {
            val panel = PathParameterPanel(variablesProvider = { emptyMap() })
            val request = HttpRequestConfig(url = "{{baseUrl}}/users/{id}")
            panel.bind(request)
            panel.setPathValue(0, "1426")
            val first = panel.count()
            panel.syncFromUrl("{{baseUrl}}/users")
            val afterRemoval = panel.count()
            panel.syncFromUrl("{{baseUrl}}/users/{id}")
            Triple(first, afterRemoval, request.pathParams.map { it.key to it.value })
        }

        assertEquals(1, first)
        assertEquals(0, afterRemoval)
        assertEquals(listOf("id" to "1426"), afterRestore)
    }
}
