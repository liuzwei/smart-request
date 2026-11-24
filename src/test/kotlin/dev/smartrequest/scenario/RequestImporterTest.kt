package dev.smartrequest.scenario

import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.RequestGroup
import dev.smartrequest.service.SmartRequestProjectService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestImporterTest {

    private fun fileWithFourRequests(): List<RequestGroup> = listOf(
        RequestGroup(
            name = "Auth",
            requests = mutableListOf(
                HttpRequestConfig(name = "Login", url = "{{baseUrl}}/login"),
                HttpRequestConfig(name = "Logout", url = "{{baseUrl}}/logout")
            )
        ),
        RequestGroup(
            name = "Shop",
            requests = mutableListOf(
                HttpRequestConfig(name = "Cart", url = "{{baseUrl}}/cart"),
                HttpRequestConfig(name = "Orders", url = "{{baseUrl}}/orders")
            )
        )
    )

    @Test
    fun `importing four requests adds exactly four requests`() {
        val service = SmartRequestProjectService()
        // A fresh project service starts with one built-in "Health check" request; the import must
        // add exactly the four requests of the file on top of it, never more.
        val baseline = service.state().groups.sumOf { it.requests.size }
        val text = RequestJson.encode(fileWithFourRequests())
        val decoded = RequestJson.decode(text)
        assertEquals(2, decoded.size, "the file must carry two groups")

        val imported = RequestImporter.apply(service, decoded)

        assertEquals(4, imported, "four requests must be imported")
        val all = service.state().groups.flatMap { it.requests }
        assertEquals(baseline + 4, all.size, "got ${all.map { it.name }}")
        assertEquals(
            listOf("Cart", "Login", "Logout", "Orders"),
            all.filter { it.name != "Health check" }.map { it.name }.sorted()
        )
    }

    @Test
    fun `importing a file with duplicate group names does not duplicate requests`() {
        val service = SmartRequestProjectService()
        val baseline = service.state().groups.sumOf { it.requests.size }
        // A hand-edited or concatenated file may repeat a group name. Each request must still be
        // imported exactly once.
        val groups = listOf(
            RequestGroup(name = "Shared", requests = mutableListOf(HttpRequestConfig(name = "A", url = "{{baseUrl}}/a"))),
            RequestGroup(name = "Shared", requests = mutableListOf(HttpRequestConfig(name = "B", url = "{{baseUrl}}/b")))
        )

        val imported = RequestImporter.apply(service, groups)

        assertEquals(2, imported)
        val shared = service.findGroupByName("Shared")!!
        assertEquals(listOf("A", "B"), shared.requests.map { it.name }.sorted())
        assertEquals(baseline + 2, service.state().groups.sumOf { it.requests.size })
    }

    @Test
    fun `creating a missing group adds it once`() {
        val service = SmartRequestProjectService()
        val first = service.createGroupIfMissing("Fresh")
        val second = service.createGroupIfMissing("Fresh")

        assertEquals(first.id, second.id)
        assertEquals(1, service.state().groups.count { it.name == "Fresh" })
    }

    @Test
    fun `the guard rejects a rapid repeat of the same file`() {
        SmartRequestImportGuard.reset()

        assertTrue(SmartRequestImportGuard.shouldImport("payload", now = 1_000))
        assertFalse(SmartRequestImportGuard.shouldImport("payload", now = 1_100))
        assertFalse(SmartRequestImportGuard.shouldImport("payload", now = 2_400))
        // A different file is never blocked.
        assertTrue(SmartRequestImportGuard.shouldImport("other", now = 2_500))
        // The same file after the window is a deliberate second import.
        assertTrue(SmartRequestImportGuard.shouldImport("payload", now = 9_000))
    }
}