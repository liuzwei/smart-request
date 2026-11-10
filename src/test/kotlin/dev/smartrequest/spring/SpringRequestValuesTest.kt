package dev.smartrequest.spring

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpringRequestValuesTest {

    @Test
    fun `detects spring default none sentinel`() {
        val sentinel = "\n\t\t\n\t\t\n\uE000\uE001\uE002\n\t\t\t\t\n"

        assertTrue(SpringRequestValues.isDefaultNone(sentinel))
        assertTrue(SpringRequestValues.isDefaultNone("  \uE000\uE001\uE002  "))
        assertTrue(SpringRequestValues.isDefaultNone("ValueConstants.DEFAULT_NONE"))
    }

    @Test
    fun `does not treat real values as the sentinel`() {
        assertFalse(SpringRequestValues.isDefaultNone(""))
        assertFalse(SpringRequestValues.isDefaultNone("1"))
        assertFalse(SpringRequestValues.isDefaultNone("keyword"))
        assertFalse(SpringRequestValues.isDefaultNone("   "))
        assertFalse(SpringRequestValues.isDefaultNone("\uE000"))
    }
}
