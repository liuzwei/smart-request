package dev.smartrequest.ui

import com.intellij.openapi.components.service
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.service.SmartRequestProjectService
import dev.smartrequest.ui.dialogs.RequestPickerDialog

class RequestPickerDialogUiTest : BasePlatformTestCase() {

    private fun service(): SmartRequestProjectService = project.service()

    private fun <T> onEdtGet(action: () -> T): T = EdtTestUtil.runInEdtAndGet<T, RuntimeException> { action() }

    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }

    fun testRequestsAreFilteredByNameAndMethod() {
        onEdt {
            // The test project may keep requests from an earlier test method.
            service().state().groups.forEach { it.requests.clear() }
            service().notifyChanged()
        }
        val group = service().state().groups.first()
        val alpha = HttpRequestConfig(name = "Alpha", method = "GET", url = "{{baseUrl}}/alpha")
        val beta = HttpRequestConfig(name = "Beta", method = "POST", url = "{{baseUrl}}/beta")
        group.requests.add(alpha)
        group.requests.add(beta)
        service().notifyChanged()

        val dialog = onEdtGet { RequestPickerDialog(project, service()) }
        assertEquals(2, dialog.visibleRequestIds().size)
        assertTrue(dialog.isAddEnabled())

        onEdt { dialog.search("bet") }
        assertEquals(listOf(beta.id), dialog.visibleRequestIds())
        assertEquals(beta, dialog.selectedRequest())

        onEdt { dialog.search("POST") }
        assertEquals(listOf(beta.id), dialog.visibleRequestIds())

        onEdt { dialog.search("missing") }
        assertTrue(dialog.visibleRequestIds().isEmpty())
        assertFalse(dialog.isAddEnabled())

        onEdt { dialog.search("") }
        onEdt { dialog.selectRequest(alpha.id) }
        assertEquals(alpha, dialog.selectedRequest())

        onEdt { dialog.disposeIfNeeded() }
    }
}
