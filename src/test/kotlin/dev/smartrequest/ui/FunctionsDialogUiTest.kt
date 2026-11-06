package dev.smartrequest.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.service.SmartRequestProjectService
import dev.smartrequest.template.BuiltInFunctionCatalog
import dev.smartrequest.ui.dialogs.FunctionsDialog

class FunctionsDialogUiTest : BasePlatformTestCase() {

    fun testDialogListsProjectFunctions() {
        val service = project.service<SmartRequestProjectService>()
        service.state().functions.clear()
        service.addFunction(
            name = "bearer",
            parameters = listOf("value"),
            expression = "concat('Bearer ', base64(value))",
            description = "Adds a bearer prefix"
        )

        val dialog = EdtTestUtil.runInEdtAndGet<FunctionsDialog, RuntimeException> {
            FunctionsDialog(project, service)
        }
        assertEquals(1, dialog.functionCountForTest())
        assertEquals(BuiltInFunctionCatalog.all.size, dialog.builtInCountForTest())
        assertTrue(dialog.selectBuiltInForTest("base64(value)"))
        assertTrue(dialog.builtInDetailForTest().contains("{{base64(token)}}"))
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }
}
