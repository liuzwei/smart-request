package dev.smartrequest.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GroupDragDataTest {

    @Test
    fun `a group id round trips through the drag payload`() {
        val payload = GroupDragData.encode("group-1")

        assertEquals("group-1", GroupDragData.decode(payload))
        assertEquals("group-1", GroupDragData.decode(" $payload "))
    }

    @Test
    fun `request payloads and unrelated text are rejected`() {
        assertNull(GroupDragData.decode(null))
        assertNull(GroupDragData.decode(""))
        assertNull(GroupDragData.decode(RequestDragData.encode("request-1")))
        assertNull(GroupDragData.decode("smart-request/group:"))
    }
}
