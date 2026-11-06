package dev.smartrequest.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuiltInFunctionCatalogTest {

    @Test
    fun `every registered built-in has a catalog entry`() {
        val registered = BuiltInFunctions.all.map { it.name }.toSet()
        val documented = BuiltInFunctionCatalog.all.map { it.name }.toSet()
        assertEquals(registered, documented)
    }

    @Test
    fun `catalog entries have examples and scope`() {
        BuiltInFunctionCatalog.all.forEach { doc ->
            assertTrue(doc.example.startsWith("{{"), "Example for ${doc.name}")
            assertTrue(doc.example.endsWith("}}"), "Example for ${doc.name}")
            assertTrue(doc.scope.isNotBlank(), "Scope for ${doc.name}")
            assertTrue(doc.description.isNotBlank(), "Description for ${doc.name}")
            assertTrue(doc.category.isNotBlank(), "Category for ${doc.name}")
        }
    }
}
