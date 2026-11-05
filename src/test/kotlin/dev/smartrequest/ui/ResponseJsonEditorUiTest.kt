package dev.smartrequest.ui

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.assertNoErrorLogged
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.http.HttpResponseData
import java.awt.Container
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JTextField

class ResponseJsonEditorUiTest : BasePlatformTestCase() {

    fun testJsonResponseCanBeFoldedAndSearched() {
        val panel = EdtTestUtil.runInEdtAndGet<ResponsePanel, RuntimeException> {
            ResponsePanel(project).apply {
                setSize(560, 520)
                show(
                    HttpResponseData(
                        statusCode = 200,
                        statusText = "OK",
                        headers = listOf("Content-Type" to "application/json; charset=utf-8"),
                        body = """{"code":200,"message":"success","data":{"id":1426,"name":"demo","items":[1,2,3],"active":true}}""",
                        bodyBytes = 120,
                        durationMs = 42,
                        contentType = "application/json; charset=utf-8",
                        truncated = false,
                        textual = true
                    )
                )
            }
        }
        val frame = EdtTestUtil.runInEdtAndGet<JFrame, RuntimeException> {
            JFrame("response").apply {
                setSize(560, 540)
                add(panel)
                isVisible = true
            }
        }
        try {
            Thread.sleep(1500)
            val foldButton = EdtTestUtil.runInEdtAndGet<JButton, RuntimeException> {
                findButton(panel, "Fold") ?: error("Fold button not found")
            }
            EdtTestUtil.runInEdtAndWait<RuntimeException> { foldButton.doClick() }
            Thread.sleep(300)
            assertEquals("Unfold", foldButton.text)

            EdtTestUtil.runInEdtAndWait<RuntimeException> { foldButton.doClick() }
            Thread.sleep(300)
            assertEquals("Fold", foldButton.text)

            val searchButton = EdtTestUtil.runInEdtAndGet<JButton, RuntimeException> {
                findButton(panel, "Search") ?: error("Search button not found")
            }
            EdtTestUtil.runInEdtAndWait<RuntimeException> { searchButton.doClick() }
            EdtTestUtil.runInEdtAndWait<RuntimeException> {
                val field = findTextField(panel) ?: error("Search field not found")
                field.text = "success"
            }
            Thread.sleep(300)
            val status = EdtTestUtil.runInEdtAndGet<JLabel, RuntimeException> {
                findLabel(panel, "1/1") ?: error("Search status not found")
            }
            assertEquals("1/1", status.text)
        } finally {
            EdtTestUtil.runInEdtAndWait<RuntimeException> { frame.dispose() }
        }
    }

    fun testShowingAnotherResponseAfterFoldsWereInstalledDoesNotFail() {
        val panel = EdtTestUtil.runInEdtAndGet<ResponsePanel, RuntimeException> {
            ResponsePanel(project).apply {
                setSize(560, 520)
                show(jsonResponse("""{"a":{"b":1},"list":[1,2,3]}"""))
            }
        }
        val frame = EdtTestUtil.runInEdtAndGet<JFrame, RuntimeException> {
            JFrame("response").apply {
                setSize(560, 540)
                add(panel)
                isVisible = true
            }
        }
        try {
            // Let installManualFolds() run for the first response, then replace the body twice.
            Thread.sleep(1200)
            val foldButton = EdtTestUtil.runInEdtAndGet<JButton, RuntimeException> {
                findButton(panel, "Fold") ?: error("Fold button not found")
            }
            EdtTestUtil.runInEdtAndWait<RuntimeException> {
                if (panel.manualFoldCountForTest() == 0) foldButton.doClick()
            }
            Thread.sleep(300)
            assertTrue(
                "expected installed fold regions",
                EdtTestUtil.runInEdtAndGet<Boolean, RuntimeException> {
                    panel.manualFoldCountForTest() > 0
                }
            )
            EdtTestUtil.runInEdtAndWait<RuntimeException> {
                assertNoErrorLogged {
                    panel.show(jsonResponse("""{"c":{"d":2},"list":[4,5,6]}"""))
                }
            }
            Thread.sleep(500)
            EdtTestUtil.runInEdtAndWait<RuntimeException> {
                assertNoErrorLogged {
                    panel.show(jsonResponse("""{"e":{"f":3}}"""))
                }
            }
            Thread.sleep(300)
        } finally {
            EdtTestUtil.runInEdtAndWait<RuntimeException> { frame.dispose() }
        }
    }

    private fun jsonResponse(body: String): HttpResponseData = HttpResponseData(
        statusCode = 200,
        statusText = "OK",
        headers = listOf("Content-Type" to "application/json; charset=utf-8"),
        body = body,
        bodyBytes = body.length.toLong(),
        durationMs = 42,
        contentType = "application/json; charset=utf-8",
        truncated = false,
        textual = true
    )

    private fun findButton(component: Container, text: String): JButton? {
        if (component is JButton && component.text == text) return component
        component.components.forEach { child ->
            if (child is Container) findButton(child, text)?.let { return it }
        }
        return null
    }

    private fun findTextField(component: Container): JTextField? {
        if (component is JTextField) return component
        component.components.forEach { child ->
            if (child is Container) findTextField(child)?.let { return it }
        }
        return null
    }

    private fun findLabel(component: Container, text: String): JLabel? {
        if (component is JLabel && component.text == text) return component
        component.components.forEach { child ->
            if (child is Container) findLabel(child, text)?.let { return it }
        }
        return null
    }
}
