package dev.smartrequest.scenario

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusExpectationTest {

    @Test
    fun `empty expression accepts successes and redirects`() {
        assertTrue(StatusExpectation.matches("", 200))
        assertTrue(StatusExpectation.matches("", 302))
        assertFalse(StatusExpectation.matches("", 404))
        assertFalse(StatusExpectation.matches("", 500))
        assertNull(StatusExpectation.error(""))
    }

    @Test
    fun `status classes ranges and lists are supported`() {
        assertTrue(StatusExpectation.matches("4xx", 404))
        assertFalse(StatusExpectation.matches("4xx", 500))
        assertTrue(StatusExpectation.matches("2XX", 201))
        assertTrue(StatusExpectation.matches("200-204", 204))
        assertFalse(StatusExpectation.matches("200-204", 205))
        assertTrue(StatusExpectation.matches("200, 201, 204", 204))
        assertFalse(StatusExpectation.matches("200, 201, 204", 202))
    }

    @Test
    fun `invalid expressions are rejected`() {
        assertNotNull(StatusExpectation.error("6xx"))
        assertNotNull(StatusExpectation.error("abc"))
        assertNotNull(StatusExpectation.error("200-"))
        assertNotNull(StatusExpectation.error("900"))
        assertNotNull(StatusExpectation.error("300-200"))
    }

    @Test
    fun `describe falls back to the default range`() {
        assertTrue(StatusExpectation.describe("").contains("200"))
        assertTrue(StatusExpectation.describe("4xx").contains("4xx"))
    }
}
