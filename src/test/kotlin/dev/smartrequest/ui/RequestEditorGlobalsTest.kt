package dev.smartrequest.ui

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue

/**
 * Each request decides whether the active environment's global headers/params are sent. They are on
 * by default; a login request can turn them off so a `{{token}}` global does not fail before the
 * login response has captured it.
 */
class RequestEditorGlobalsTest : BasePlatformTestCase() {

    fun testGlobalsAreUsedAndShownByDefault() {
        val result = EdtTestUtil.runInEdtAndGet<Triple<Boolean, Boolean, Int>, RuntimeException> {
            val panel = newPanel()
            panel.open(HttpRequestConfig(url = "{{baseUrl}}/users"))
            panel.setGlobals(
                listOf(KeyValue.of("Authorization", "Bearer {{token}}")),
                listOf(KeyValue.of("tenant", "acme"))
            )
            Triple(
                panel.useGlobalHeadersCheckbox().isSelected,
                panel.useGlobalParamsCheckbox().isSelected,
                panel.headersTable().fixedCount() + panel.paramsTable().fixedCount()
            )
        }

        assertEquals(true, result.first)
        assertEquals(true, result.second)
        assertEquals(2, result.third)
    }

    fun testUncheckingGlobalParamsHidesThemAndUpdatesTheRequest() {
        val result = EdtTestUtil.runInEdtAndGet<Pair<Boolean, Int>, RuntimeException> {
            val panel = newPanel()
            val request = HttpRequestConfig(url = "{{baseUrl}}/login")
            panel.open(request)
            panel.setGlobals(emptyList(), listOf(KeyValue.of("tenant", "acme")))
            panel.useGlobalParamsCheckbox().doClick()
            request.useGlobalParams to panel.paramsTable().fixedCount()
        }

        assertEquals(false, result.first)
        assertEquals(0, result.second)
    }

    fun testUncheckingGlobalHeadersHidesThemAndKeepsGlobalParams() {
        val result = EdtTestUtil.runInEdtAndGet<Triple<Boolean, Boolean, Int>, RuntimeException> {
            val panel = newPanel()
            val request = HttpRequestConfig(url = "{{baseUrl}}/login")
            panel.open(request)
            panel.setGlobals(
                listOf(KeyValue.of("Authorization", "Bearer {{token}}")),
                listOf(KeyValue.of("tenant", "acme"))
            )
            panel.useGlobalHeadersCheckbox().doClick()
            Triple(
                request.useGlobalHeaders,
                request.useGlobalParams,
                panel.headersTable().fixedCount() + panel.paramsTable().fixedCount()
            )
        }

        assertEquals(false, result.first)
        assertEquals(true, result.second)
        assertEquals(1, result.third)
    }

    fun testReopeningKeepsTheOptOut() {
        val result = EdtTestUtil.runInEdtAndGet<Triple<Boolean, Boolean, Int>, RuntimeException> {
            val panel = newPanel()
            val request = HttpRequestConfig(
                url = "{{baseUrl}}/login",
                useGlobalHeaders = false
            )
            panel.open(request)
            panel.setGlobals(
                listOf(KeyValue.of("Authorization", "Bearer {{token}}")),
                listOf(KeyValue.of("tenant", "acme"))
            )
            Triple(
                panel.useGlobalHeadersCheckbox().isSelected,
                panel.useGlobalParamsCheckbox().isSelected,
                panel.headersTable().fixedCount()
            )
        }

        assertEquals(false, result.first)
        assertEquals(true, result.second)
        assertEquals(0, result.third)
    }

    fun testCheckingTheBoxAgainRestoresTheGlobalRows() {
        val count = EdtTestUtil.runInEdtAndGet<Int, RuntimeException> {
            val panel = newPanel()
            panel.open(HttpRequestConfig(url = "{{baseUrl}}/login"))
            panel.setGlobals(emptyList(), listOf(KeyValue.of("tenant", "acme")))
            panel.useGlobalParamsCheckbox().doClick()
            panel.useGlobalParamsCheckbox().doClick()
            panel.paramsTable().fixedCount()
        }

        assertEquals(1, count)
    }

    private fun newPanel(): RequestEditorPanel = RequestEditorPanel(
        onSend = {},
        onCancel = {},
        onNameChanged = {},
        variablesProvider = { emptyMap() }
    )
}
