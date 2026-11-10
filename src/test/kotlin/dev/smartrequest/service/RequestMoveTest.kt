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

    @Test
    fun `request can be inserted before another request in the same group`() {
        val service = SmartRequestProjectService()
        val group = service.state().groups.first()
        group.requests.clear()
        val first = HttpRequestConfig(name = "First")
        val second = HttpRequestConfig(name = "Second")
        val third = HttpRequestConfig(name = "Third")
        group.requests.addAll(listOf(first, second, third))

        assertTrue(service.moveRequestToIndex(third.id, group.id, 0))

        assertEquals(listOf("Third", "First", "Second"), group.requests.map { it.name })
    }

    @Test
    fun `request can be inserted after another request in the same group`() {
        val service = SmartRequestProjectService()
        val group = service.state().groups.first()
        group.requests.clear()
        val first = HttpRequestConfig(name = "First")
        val second = HttpRequestConfig(name = "Second")
        val third = HttpRequestConfig(name = "Third")
        group.requests.addAll(listOf(first, second, third))

        assertTrue(service.moveRequestToIndex(first.id, group.id, 3))

        assertEquals(listOf("Second", "Third", "First"), group.requests.map { it.name })
    }

    @Test
    fun `request can be moved to an index in another group`() {
        val service = SmartRequestProjectService()
        val source = service.state().groups.first()
        source.requests.clear()
        val request = HttpRequestConfig(name = "Request")
        source.requests.add(request)
        val target = service.addGroup("Target")
        target.requests.addAll(listOf(HttpRequestConfig(name = "A"), HttpRequestConfig(name = "B")))

        assertTrue(service.moveRequestToIndex(request.id, target.id, 1))

        assertEquals(listOf("A", "Request", "B"), target.requests.map { it.name })
        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun `moving a request to its current index is a no-op`() {
        val service = SmartRequestProjectService()
        val group = service.state().groups.first()
        group.requests.clear()
        val request = HttpRequestConfig(name = "Request")
        group.requests.add(request)
        group.requests.add(HttpRequestConfig(name = "Other"))
        var changes = 0
        service.addChangeListener { changes++ }

        assertFalse(service.moveRequestToIndex(request.id, group.id, 0))
        assertEquals(0, changes)
        assertEquals(listOf("Request", "Other"), group.requests.map { it.name })
    }

    @Test
    fun `groups can be reordered`() {
        val service = SmartRequestProjectService()
        val state = service.state()
        state.groups.clear()
        val first = service.addGroup("First")
        val second = service.addGroup("Second")
        val third = service.addGroup("Third")

        assertTrue(service.moveGroup(third.id, 0))
        assertEquals(listOf("Third", "First", "Second"), state.groups.map { it.name })

        assertTrue(service.moveGroup(third.id, 3))
        assertEquals(listOf("First", "Second", "Third"), state.groups.map { it.name })

        assertFalse(service.moveGroup(first.id, 0))
        assertEquals(listOf("First", "Second", "Third"), state.groups.map { it.name })
        assertSame(second, state.groups[1])
    }
}
