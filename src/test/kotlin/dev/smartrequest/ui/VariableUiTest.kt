package dev.smartrequest.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import javax.swing.SwingUtilities

class VariableUiTest : BasePlatformTestCase() {

    fun testVariableFieldHighlightsKnownAndUnknownTokens() {
        runOnEdt(object : Runnable {
            override fun run() {
                val field = VariableTextField(::variables)
                field.text = "{{baseUrl}}/api/{{missing}}"
                assertEquals(2, field.highlighter.highlights.size)
            }
        })
    }

    fun testFunctionExpressionsAreHighlightedAsOneExpression() {
        runOnEdt(object : Runnable {
            override fun run() {
                val field = VariableTextField(::variables)
                field.text = "{{base64(baseUrl)}}/{{urlencode(name)}}"
                assertEquals(2, field.highlighter.highlights.size)
            }
        })
    }

    fun testUrlFieldAlsoHighlightsSingleBracePathParameters() {
        runOnEdt(object : Runnable {
            override fun run() {
                val field = VariableTextField(::variables, highlightPathParameters = true)
                field.text = "{{baseUrl}}/users/{id}/orders/{orderId}"
                assertEquals(3, field.highlighter.highlights.size)
            }
        })
    }

    fun testReplacingTextWithStaleCaretDoesNotThrow() {
        runOnEdt(object : Runnable {
            override fun run() {
                val field = VariableTextField(::variables)
                field.text = "{{baseUrl}}"
                field.caretPosition = 2
                field.text = ""
                assertEquals("", field.text)
            }
        })
    }

    fun testPathParametersAreDerivedFromUrlChanges() {
        runOnEdt(object : Runnable {
            override fun run() {
                val panel = PathParameterPanel(variablesProvider = ::variables)
                val request = HttpRequestConfig().apply { url = "{{baseUrl}}/users/{id}/orders/{orderId}" }
                panel.bind(request)

                assertEquals(2, panel.count())
                assertEquals("id", request.pathParams[0].key)
                assertEquals("orderId", request.pathParams[1].key)

                request.pathParams[0].value = "42"
                panel.syncFromUrl("{{baseUrl}}/users/{userId}/orders/{orderId}")
                assertEquals("userId", request.pathParams[0].key)
                assertEquals("", request.pathParams[0].value)
            }
        })
    }

    private fun variables(): Map<String, String> = mapOf(
        "baseUrl" to "http://localhost:8080",
        "tenant" to "acme"
    )

    private fun runOnEdt(runnable: Runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run()
        } else {
            SwingUtilities.invokeAndWait(runnable)
        }
    }
}
