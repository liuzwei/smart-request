package dev.smartrequest.spring

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.service.SmartRequestProjectService

class SpringRequestImporterTest : BasePlatformTestCase() {

    fun testCreatesThenUpdatesWithoutDuplicatingOrLosingUserValues() {
        val service = project.service<SmartRequestProjectService>()
        val imported = HttpRequestConfig().apply {
            name = "GET /search"
            method = "GET"
            url = "{{baseUrl}}/search/{scope}"
            springKey = "com.example.SearchController#search:GET:/search/{scope}"
            pathParams.add(KeyValue.of("scope", ""))
        }
        val payload = SpringGenerationPayload("SearchController", "com.example.SearchController", listOf(imported))

        val first = SpringRequestImporter.apply(project, payload)
        assertEquals(1, first.added)
        assertEquals(0, first.updated)

        val group = service.findGroupByName("SearchController")
        assertNotNull(group)
        val request = group!!.requests.single()
        request.pathParams.first().value = "42"
        request.params.add(KeyValue.of("keep", "yes"))

        val refreshed = imported.deepCopy().apply {
            name = "搜索邀请"
            params.add(KeyValue.of("q", ""))
        }
        val second = SpringRequestImporter.apply(
            project,
            SpringGenerationPayload("SearchController", "com.example.SearchController", listOf(refreshed))
        )

        assertEquals(0, second.added)
        assertEquals(1, second.updated)
        val updated = service.findGroupByName("SearchController")!!.requests.single()
        assertEquals("搜索邀请", updated.name)
        assertEquals("42", updated.pathParams.first().value)
        assertEquals("yes", updated.params.first { it.key == "keep" }.value)
        assertEquals("", updated.params.first { it.key == "q" }.value)
    }
}
