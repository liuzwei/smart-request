package dev.smartrequest.service

import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestMoveTest {

    @Test
    fun `move request appends it to the target group and keeps its data`() {
        val service = SmartRequestProjectService()
        val source = service.state().groups.first()
        val request = source.requests.first().apply {
            name = "Health check"
            headers.add(KeyValue.of("X-Test", "1"))
        }
        val target = service.addGroup("Target").apply {
            expanded = false
            requests.add(HttpRequestConfig(name = "Existing"))
        }

        assertTrue(service.moveRequest(request.id, target.id))

        assertFalse(source.requests.any { it.id == request.id })
        assertEquals(listOf("Existing", "Health check"), target.requests.map { it.name })
        assertSame(request, target.requests.last())
        assertEquals("1", target.requests.last().headers.first().value)
        assertTrue(target.expanded)
    }

    @Test
    fun `moving to the current group or unknown ids is a no-op`() {
        val service = SmartRequestProjectService()
        val source = service.state().groups.first()
        val request = source.requests.first()
        val target = service.addGroup("Target")

        assertFalse(service.moveRequest(request.id, source.id))
        assertFalse(service.moveRequest(request.id, "missing-group"))
        assertFalse(service.moveRequest("missing-request", target.id))

        assertEquals(1, source.requests.size)
        assertTrue(target.requests.isEmpty())
    }

    @Test
    fun `a request can be moved back and is appended again`() {
        val service = SmartRequestProjectService()
        val source = service.state().groups.first()
        val request = source.requests.first()
        val target = service.addGroup("Target").apply {
            requests.add(HttpRequestConfig(name = "Existing"))
        }

        assertTrue(service.moveRequest(request.id, target.id))
        assertTrue(service.moveRequest(request.id, source.id))

        assertEquals(listOf(request), source.requests)
        assertEquals(listOf("Existing"), target.requests.map { it.name })
    }

    @Test
    fun `move notifies listeners exactly once`() {
        val service = SmartRequestProjectService()
        val request = service.state().groups.first().requests.first()
        val target = service.addGroup("Target")
        var changes = 0
        service.addChangeListener { changes++ }

        assertTrue(service.moveRequest(request.id, target.id))
        assertEquals(1, changes)
    }
}
