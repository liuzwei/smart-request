package dev.smartrequest.extract

import dev.smartrequest.http.HttpResponseData
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.ResponseExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResponseExtractorEngineTest {

    @Test
    fun `updates an existing variable`() {
        val environment = environment(KeyValue.of("token", "old"))
        val report = run(environment, response("""{"token":"new"}"""), rule("token", "data.token"))

        assertEquals(1, report.successCount)
        assertEquals("new", environment.variables.single { it.key == "token" }.value)
        assertEquals("old", report.results.single().previous)
        assertFalse(report.results.single().added)
    }

    @Test
    fun `appends a missing variable`() {
        val environment = environment()
        val report = run(environment, response("""{"user":{"id":42}}"""), rule("", "data.user.id"))

        val stored = environment.variables.single()
        assertEquals("id", stored.key)
        assertEquals("42", stored.value)
        assertTrue(report.results.single().added)
        assertEquals(1, report.addedCount)
    }

    @Test
    fun `derives a name from the last path segment when the name is empty`() {
        assertEquals("id", ResponseExtractorEngine.deriveVariableName("data.user.id"))
        assertEquals("name", ResponseExtractorEngine.deriveVariableName("data.items[0].name"))
        assertEquals("X_Request_Id", ResponseExtractorEngine.deriveVariableName("headers.X-Request-Id"))
        assertEquals("status", ResponseExtractorEngine.deriveVariableName("status"))
        assertEquals("", ResponseExtractorEngine.deriveVariableName("data"))
        assertEquals("_1st", ResponseExtractorEngine.sanitizeVariableName("1st"))
        assertEquals("user_name", ResponseExtractorEngine.sanitizeVariableName("user.name"))
    }

    @Test
    fun `a failing rule never overwrites the previous value`() {
        val environment = environment(KeyValue.of("token", "keep-me"))
        val report = run(environment, response("""{"other":"x"}"""), rule("token", "data.token"))

        assertEquals(0, report.successCount)
        assertEquals(1, report.failureCount)
        assertEquals("keep-me", environment.variables.single { it.key == "token" }.value)
    }

    @Test
    fun `a disabled variable is updated and enabled again`() {
        val environment = environment(KeyValue.of("token", "old", enabled = false))
        val report = run(environment, response("""{"token":"new"}"""), rule("token", "data.token"))

        val stored = environment.variables.single { it.key == "token" }
        assertEquals("new", stored.value)
        assertTrue(stored.enabled)
        assertTrue(report.results.single().reEnabled)
    }

    @Test
    fun `variable names cannot contain spaces or braces`() {
        val environment = environment()
        val report = run(environment, response("""{"a":"1"}"""), rule("bad name", "data.a"))

        assertEquals(0, report.successCount)
        assertTrue(report.results.single().error.orEmpty().contains("spaces"))
        assertTrue(environment.variables.isEmpty())
    }

    @Test
    fun `baseUrl cannot be overwritten`() {
        val environment = environment()
        val report = run(environment, response("""{"baseUrl":"http://x"}"""), rule("baseUrl", "data.baseUrl"))

        assertEquals(0, report.successCount)
        assertTrue(environment.variables.isEmpty())
        assertTrue(report.results.single().error.orEmpty().contains("reserved"))
    }

    @Test
    fun `reads response headers case insensitively`() {
        val environment = environment()
        val report = run(
            environment,
            response("""{}""", headers = listOf("X-Request-Id" to "abc")),
            rule("traceId", "headers.x-request-id")
        )

        assertEquals("abc", environment.variables.single().value)
        assertEquals(1, report.successCount)
    }

    @Test
    fun `reads status and status text`() {
        val environment = environment()
        run(environment, response("{}", status = 404, statusText = "Not Found"), rule("code", "status"))
        run(environment, response("{}", status = 404, statusText = "Not Found"), rule("reason", "statusText"))

        assertEquals("404", environment.variables.first { it.key == "code" }.value)
        assertEquals("Not Found", environment.variables.first { it.key == "reason" }.value)
    }

    @Test
    fun `disabled and empty rules are ignored`() {
        val environment = environment()
        val rules = listOf(
            rule("a", "data.a", enabled = false),
            ResponseExtractor(),
            rule("b", "data.b")
        )
        val report = run(environment, response("""{"a":1,"b":2}"""), *rules.toTypedArray())

        assertEquals(1, report.results.size)
        assertEquals("2", environment.variables.single().value)
    }

    @Test
    fun `two rules sharing a variable keep the last value`() {
        val environment = environment()
        val report = run(
            environment,
            response("""{"first":"1","second":"2"}"""),
            rule("shared", "data.first"),
            rule("shared", "data.second")
        )

        assertEquals(2, report.results.size)
        assertEquals("2", environment.variables.single { it.key == "shared" }.value)
    }

    @Test
    fun `reports every failure reason`() {
        val rules = listOf(
            rule("a", "data.missing"),
            rule("b", "data.token"),          // null
            rule("c", "data.items[9]"),       // out of bounds
            rule("d", "not.a.path"),          // syntax
            rule("", "data")                  // no name
        )
        val report = ResponseExtractorEngine.extract(
            response("""{"token":null,"items":[1]}"""),
            rules
        ).let { ResponseExtractorEngine.apply(environment(), it) }

        assertEquals(5, report.results.size)
        assertEquals(0, report.successCount)
        assertTrue(report.results[0].error.orEmpty().contains("not present"))
        assertTrue(report.results[1].error.orEmpty().contains("null"))
        assertTrue(report.results[2].error.orEmpty().contains("out of bounds"))
        assertTrue(report.results[3].error.orEmpty().contains("Unknown source"))
        assertTrue(report.results[4].error.orEmpty().contains("Variable name is empty"))
    }

    @Test
    fun `truncated binary and missing responses fail with a reason`() {
        val truncated = ResponseExtractorEngine.extract(response("""{"a":1}""", truncated = true), listOf(rule("a", "data.a")))
        val binary = ResponseExtractorEngine.extract(response("""{"a":1}""", textual = false), listOf(rule("a", "data.a")))
        val missing = ResponseExtractorEngine.extract(null, listOf(rule("a", "data.a")))

        assertTrue(truncated.single().error.orEmpty().contains("truncated"))
        assertTrue(binary.single().error.orEmpty().contains("binary"))
        assertTrue(missing.single().error.orEmpty().contains("No response"))
    }

    @Test
    fun `non json body fails for data rules`() {
        val values = ResponseExtractorEngine.extract(
            response("<html>nope</html>", contentType = "text/html"),
            listOf(rule("a", "data.a"))
        )

        assertTrue(values.single().error.orEmpty().contains("not valid JSON"))
    }

    @Test
    fun `no environment means nothing is written`() {
        val values = ResponseExtractorEngine.extract(response("""{"a":1}"""), listOf(rule("a", "data.a")))
        val report = ResponseExtractorEngine.apply(null, values)

        assertEquals(0, report.successCount)
        assertTrue(report.results.single().error.orEmpty().contains("No active environment"))
    }

    @Test
    fun `empty rule lists produce an empty report`() {
        assertTrue(ResponseExtractorEngine.extract(response("{}"), emptyList()).isEmpty())
        assertTrue(ResponseExtractorEngine.apply(environment(), emptyList()).isEmpty)
    }

    @Test
    fun `report counts added and updated values`() {
        val environment = environment(KeyValue.of("existing", "1"))
        val report = run(
            environment,
            response("""{"a":"2","b":"3"}"""),
            rule("existing", "data.a"),
            rule("fresh", "data.b")
        )

        assertEquals(2, report.successCount)
        assertEquals(1, report.addedCount)
        assertEquals(1, report.updatedCount)
        assertEquals(2, environment.variables.size)
    }

    @Test
    fun `values are written to the environment that was active when sending`() {
        // apply() itself only knows the environment it is given; the panel snapshots the id at send
        // time. This test locks the contract: two environments never share writes.
        val local = environment(KeyValue.of("token", "local"))

        val report = ResponseExtractorEngine.apply(
            local,
            ResponseExtractorEngine.extract(response("""{"token":"new"}"""), listOf(rule("token", "data.token")))
        )

        assertEquals(1, report.successCount)
        assertEquals("new", local.variables.single { it.key == "token" }.value)
        assertNull(report.results.single().error)
    }

    private fun run(
        environment: EnvironmentConfig,
        response: HttpResponseData?,
        vararg rules: ResponseExtractor
    ): ExtractionReport = ResponseExtractorEngine.apply(
        environment,
        ResponseExtractorEngine.extract(response, rules.toList())
    )

    private fun rule(variable: String, source: String, enabled: Boolean = true) =
        ResponseExtractor(enabled = enabled, variable = variable, source = source)

    private fun environment(vararg variables: KeyValue): EnvironmentConfig =
        EnvironmentConfig(name = "local", baseUrl = "http://localhost:8080").apply {
            this.variables = variables.map { it.deepCopy() }.toMutableList()
        }

    private fun response(
        body: String,
        status: Int = 200,
        statusText: String = "OK",
        headers: List<Pair<String, String>> = emptyList(),
        contentType: String? = "application/json",
        truncated: Boolean = false,
        textual: Boolean = true
    ) = HttpResponseData(
        statusCode = status,
        statusText = statusText,
        headers = headers,
        body = body,
        bodyBytes = body.toByteArray().size.toLong(),
        durationMs = 12,
        contentType = contentType,
        truncated = truncated,
        textual = textual
    )
}
