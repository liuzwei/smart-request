package dev.smartrequest.spring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpringPathUtilsTest {

    @Test
    fun `joins class and method paths`() {
        assertEquals("/api/users", SpringPathUtils.joinPaths("/api/", "/users"))
        assertEquals("/users", SpringPathUtils.joinPaths("", "users"))
        assertEquals("/api", SpringPathUtils.joinPaths("/api", ""))
        assertEquals("/", SpringPathUtils.joinPaths("", ""))
    }

    @Test
    fun `removes regex constraints from path variables`() {
        assertEquals("/users/{id}", SpringPathUtils.normalizePath("/users/{id:\\d+}"))
        assertEquals("/users/{id}", SpringPathUtils.normalizePath("/users/{id:[0-9]{1,3}}"))
    }

    @Test
    fun `collapses duplicate slashes`() {
        assertEquals("/api/users", SpringPathUtils.normalizePath("//api//users/"))
    }
}
