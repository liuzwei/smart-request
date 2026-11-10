package dev.smartrequest.service

import dev.smartrequest.http.QueryParams
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.RequestGroup
import dev.smartrequest.model.SmartRequestState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpringDefaultNoneCleanupTest {

    private val sentinel = "\n\t\t\n\t\t\n\uE000\uE001\uE002\n\t\t\t\t\n"

    @Test
    fun `loadState clears the sentinel from a saved parameter and its url copy`() {
        val service = SmartRequestProjectService()
        val request = HttpRequestConfig(
            url = QueryParams.replaceQuery(
                "http://localhost:8080/api",
                listOf("keyword" to sentinel, "page" to "1")
            ),
            params = mutableListOf(KeyValue.of("keyword", sentinel), KeyValue.of("page", "1"))
        )
        val state = SmartRequestState()
        state.groups.add(RequestGroup(name = "My Requests").apply { requests.add(request) })

        service.loadState(state)

        assertEquals("", request.params.first { it.key == "keyword" }.value)
        assertEquals("1", request.params.first { it.key == "page" }.value)
        assertEquals(listOf("page" to "1"), QueryParams.parse(request.url))
    }

    @Test
    fun `loadState keeps normal parameters untouched`() {
        val service = SmartRequestProjectService()
        val request = HttpRequestConfig(
            url = "http://localhost:8080/api?keyword=spring&page=2",
            params = mutableListOf(KeyValue.of("keyword", "spring"), KeyValue.of("page", "2"))
        )
        val state = SmartRequestState()
        state.groups.add(RequestGroup(name = "My Requests").apply { requests.add(request) })

        service.loadState(state)

        assertEquals(listOf("keyword" to "spring", "page" to "2"), QueryParams.parse(request.url))
        assertEquals("spring", request.params.first().value)
    }
}
