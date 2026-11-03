package dev.smartrequest.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class ModelsTest {

    @Test
    fun `history is capped during normalization`() {
        val state = SmartRequestState.createDefault()
        repeat(105) { index ->
            state.history.add(
                RequestHistoryEntry(timestamp = (104 - index).toLong(), url = "http://localhost/$index")
            )
        }

        state.normalize()

        assertEquals(SmartRequestState.MAX_HISTORY, state.history.size)
        assertEquals(104L, state.history.first().timestamp)
    }

    @Test
    fun `deep copy includes path parameters and does not share lists`() {
        val request = HttpRequestConfig().apply {
            pathParams.add(KeyValue.of("id", "42"))
            headers.add(KeyValue.of("X-Test", "yes"))
        }

        val copy = request.deepCopy(newId = true)

        assertNotSame(request.id, copy.id)
        assertEquals("42", copy.pathParams.first().value)
        assertNotSame(request.pathParams.first(), copy.pathParams.first())
        assertEquals("yes", copy.headers.first().value)
    }
}
