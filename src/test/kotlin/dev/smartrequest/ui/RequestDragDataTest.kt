package dev.smartrequest.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RequestDragDataTest {

    @Test
    fun `a request id round trips through the drag payload`() {
        val payload = RequestDragData.encode("request-1")

        assertEquals("request-1", RequestDragData.decode(payload))
        assertEquals("request-1", RequestDragData.decode(" ${payload} "))
    }

    @Test
    fun `unrelated text is rejected`() {
        assertNull(RequestDragData.decode(null))
        assertNull(RequestDragData.decode(""))
        assertNull(RequestDragData.decode("file:///tmp/a.json"))
        assertNull(RequestDragData.decode("smart-request/request:"))
        assertNull(RequestDragData.decode("something smart-request/request:request-1"))
    }
}
