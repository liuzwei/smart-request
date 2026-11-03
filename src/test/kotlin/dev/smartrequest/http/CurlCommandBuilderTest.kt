package dev.smartrequest.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CurlCommandBuilderTest {

    private val request = PreparedRequest(
        method = "POST",
        url = "http://localhost:8080/api/users?page=1",
        headers = listOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer it's"
        ),
        body = """{"name":"O'Reilly","active":true}""",
        timeoutMs = 30_000,
        followRedirects = true
    )

    @Test
    fun `builds a linux curl command with escaped single quotes`() {
        assertEquals(
            """
            curl --request POST \
              --location \
              'http://localhost:8080/api/users?page=1' \
              --header 'Content-Type: application/json' \
              --header 'Authorization: Bearer it'\''s' \
              --data-raw '{"name":"O'\''Reilly","active":true}'
            """.trimIndent(),
            CurlCommandBuilder.build(request, CurlFormat.LINUX)
        )
    }

    @Test
    fun `builds a powershell curl command with doubled single quotes`() {
        assertEquals(
            """
            curl.exe --request POST `
              --location `
              'http://localhost:8080/api/users?page=1' `
              --header 'Content-Type: application/json' `
              --header 'Authorization: Bearer it''s' `
              --data-raw '{"name":"O''Reilly","active":true}'
            """.trimIndent(),
            CurlCommandBuilder.build(request, CurlFormat.POWERSHELL)
        )
    }

    @Test
    fun `uses head instead of request for head calls`() {
        val head = PreparedRequest(
            method = "HEAD",
            url = "http://localhost:8080/health",
            headers = emptyList(),
            body = null,
            timeoutMs = 30_000,
            followRedirects = false
        )

        assertEquals(
            "curl --head 'http://localhost:8080/health'",
            CurlCommandBuilder.build(head, CurlFormat.LINUX)
        )
    }
}
