package dev.smartrequest.service

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.template.TemplateEngine

class FunctionServiceTest : BasePlatformTestCase() {

    private fun service(): SmartRequestProjectService = project.service()

    override fun setUp() {
        super.setUp()
        service().state().functions.clear()
        service().notifyChanged()
    }

    fun testFunctionCanBeAddedAndResolved() {
        val service = service()
        val definition = service.addFunction(
            name = "bearer",
            parameters = listOf("value"),
            expression = "concat('Bearer ', base64(value))",
            description = "Bearer header"
        )
        assertNotNull(definition)
        assertEquals(1, service.functions().size)

        val result = TemplateEngine.resolve(
            "{{bearer(token)}}",
            mapOf("token" to "hello"),
            service.functionRegistry()
        )
        assertEquals("Bearer aGVsbG8=", result.text)
        assertTrue(result.errors.isEmpty())
    }

    fun testFunctionValidationRejectsBadDefinitions() {
        val service = service()
        assertTrue(service.functionError(null, "base64", emptyList(), "'x'")!!.contains("built-in"))
        assertTrue(
            service.functionError(null, "bad name", emptyList(), "'x'")!!.contains("Function name")
        )
        assertTrue(
            service.functionError(null, "f", listOf("x", "x"), "concat(x, x)")!!
                .contains("unique")
        )
        assertTrue(
            service.functionError(null, "f", listOf("x"), "concat(x, missing)")!!
                .contains("Unknown parameter")
        )
        assertTrue(
            service.functionError(null, "f", listOf("x"), "noSuchFunction(x)")!!
                .contains("Unknown function")
        )
    }

    fun testFunctionCanBeUpdatedAndRemoved() {
        val service = service()
        val definition = service.addFunction("a", listOf("x"), "'one'")!!
        assertTrue(service.updateFunction(definition.id, "b", listOf("x"), "'two'"))
        assertEquals("b", service.functions().single().name)
        assertEquals("two", TemplateEngine.resolve("{{b('ignored')}}", emptyMap(), service.functionRegistry()).text)

        service.removeFunction(definition.id)
        assertTrue(service.functions().isEmpty())
    }

    fun testDuplicateFunctionNamesAreRejected() {
        val service = service()
        assertNotNull(service.addFunction("encode", listOf("x"), "base64(x)"))
        assertTrue(
            service.functionError(null, "ENCODE", listOf("x"), "base64(x)")!!
                .contains("already named")
        )
        assertNull(service.addFunction("ENCODE", listOf("x"), "base64(x)"))
    }
}
