package dev.smartrequest.ui

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue

/**
 * The URL field and the Params table describe the same query string: editing either side must update
 * the other one.
 */
class RequestEditorQuerySyncTest : BasePlatformTestCase() {

    fun testTypingInTheUrlFillsTheParameterTable() {
        val (url, params) = EdtTestUtil.runInEdtAndGet<Pair<String, List<Pair<String, String>>>, RuntimeException> {
            val panel = newPanel()
            panel.open(HttpRequestConfig(url = "{{baseUrl}}/users"))
            panel.setUrlFieldText("{{baseUrl}}/users?page=2&size=10")
            panel.urlFieldText() to panel.parameterPairs()
        }

        assertEquals("{{baseUrl}}/users?page=2&size=10", url)
        assertEquals(listOf("page" to "2", "size" to "10"), params)
    }

    fun testRemovingAParameterFromTheUrlRemovesTheRow() {
        val params = EdtTestUtil.runInEdtAndGet<List<Pair<String, String>>, RuntimeException> {
            val panel = newPanel()
            panel.open(HttpRequestConfig(url = "{{baseUrl}}/users?page=2&size=10"))
            panel.setUrlFieldText("{{baseUrl}}/users?size=10")
            panel.parameterPairs()
        }

        assertEquals(listOf("size" to "10"), params)
    }

    fun testEditingAParameterUpdatesTheUrl() {
        val url = EdtTestUtil.runInEdtAndGet<String, RuntimeException> {
            val panel = newPanel()
            panel.open(HttpRequestConfig(url = "{{baseUrl}}/users"))
            val table = panel.paramsTable()
            table.addRow()
            table.commitPendingEdit()
            table.setRowValues(0, "page", "2")
            panel.urlFieldText()
        }

        assertEquals("{{baseUrl}}/users?page=2", url)
    }

    fun testDisablingAParameterRemovesItFromTheUrlButKeepsTheRow() {
        val (url, rows) = EdtTestUtil.runInEdtAndGet<Pair<String, List<KeyValue>>, RuntimeException> {
            val panel = newPanel()
            val request = HttpRequestConfig(
                url = "{{baseUrl}}/users?page=2",
                params = mutableListOf(KeyValue.of("page", "2"))
            )
            panel.open(request)
            panel.paramsTable().toggleEnabled(0)
            panel.urlFieldText() to request.params
        }

        assertEquals("{{baseUrl}}/users", url)
        assertEquals(1, rows.size)
        assertEquals(false, rows.first().enabled)
    }

    fun testOpeningMergesParametersStoredOnlyInTheModel() {
        val (url, params) = EdtTestUtil.runInEdtAndGet<Pair<String, List<Pair<String, String>>>, RuntimeException> {
            val panel = newPanel()
            panel.open(
                HttpRequestConfig(
                    url = "{{baseUrl}}/users?page=2",
                    params = mutableListOf(KeyValue.of("size", "10"))
                )
            )
            panel.urlFieldText() to panel.parameterPairs()
        }

        assertEquals("{{baseUrl}}/users?page=2&size=10", url)
        assertEquals(listOf("page" to "2", "size" to "10"), params)
    }

    fun testPathParametersAreUnaffectedByQuerySyncing() {
        val (params, pathCount) = EdtTestUtil.runInEdtAndGet<Pair<List<Pair<String, String>>, Int>, RuntimeException> {
            val panel = newPanel()
            panel.open(HttpRequestConfig(url = "{{baseUrl}}/users/{id}"))
            panel.setUrlFieldText("{{baseUrl}}/users/{id}?page=2")
            panel.parameterPairs() to panel.pathParameterCount()
        }

        assertEquals(listOf("page" to "2"), params)
        assertEquals(1, pathCount)
    }

    fun testEditingAParameterValueKeepsThePathParameterValue() {
        val (count, pathParams) = EdtTestUtil.runInEdtAndGet<Pair<Int, List<Pair<String, String>>>, RuntimeException> {
            val panel = newPanel()
            panel.open(
                HttpRequestConfig(
                    url = "{{baseUrl}}/workshop/{relationalId}/candidate-members",
                    pathParams = mutableListOf(KeyValue.of("relationalId", "1426"))
                )
            )
            panel.paramsTable().addRow()
            panel.paramsTable().commitPendingEdit()
            panel.paramsTable().setRowValues(0, "page", "2")
            panel.pathParameterCount() to panel.pathParameterPairs()
        }

        assertEquals(1, count)
        assertEquals(listOf("relationalId" to "1426"), pathParams)
    }

    fun testEditingAParameterValueKeepsTheUrlPath() {
        val (url, pathCount) = EdtTestUtil.runInEdtAndGet<Pair<String, Int>, RuntimeException> {
            val panel = newPanel()
            panel.open(HttpRequestConfig(url = "{{baseUrl}}/workshop/{relationalId}/candidate-members"))
            panel.paramsTable().addRow()
            panel.paramsTable().commitPendingEdit()
            panel.paramsTable().setRowValues(0, "page", "2")
            panel.urlFieldText() to panel.pathParameterCount()
        }

        assertEquals("{{baseUrl}}/workshop/{relationalId}/candidate-members?page=2", url)
        assertEquals(1, pathCount)
    }

    private fun newPanel(): RequestEditorPanel = RequestEditorPanel(
        onSend = {},
        onCancel = {},
        onNameChanged = {},
        variablesProvider = { emptyMap() }
    )
}
