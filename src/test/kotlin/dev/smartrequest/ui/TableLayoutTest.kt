package dev.smartrequest.ui

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TableLayoutTest {

    @Test
    fun `splits the width using the requested ratio`() {
        val widths = proportionalWidths(600, listOf(3.0, 4.0, 3.0), listOf(0, 0, 0))

        assertEquals(600, widths.sum())
        assertEquals(180, widths[0])
        assertEquals(240, widths[1])
        assertEquals(180, widths[2])
    }

    @Test
    fun `rounding leftover pixels keep the total exact`() {
        val widths = proportionalWidths(601, listOf(3.0, 4.0, 3.0), listOf(0, 0, 0))

        assertEquals(601, widths.sum())
        assertTrue(widths[1] > widths[0])
    }

    @Test
    fun `minimum widths are respected before shrinking`() {
        val widths = proportionalWidths(120, listOf(3.0, 4.0, 3.0), listOf(10, 10, 80))

        assertEquals(120, widths.sum())
        assertEquals(80, widths[2])
        assertTrue(widths[0] >= 10)
        assertTrue(widths[1] >= 10)
    }

    @Test
    fun `falls back to minimums when the minima do not fit`() {
        val widths = proportionalWidths(60, listOf(3.0, 4.0, 3.0), listOf(30, 40, 30))

        assertArrayEquals(intArrayOf(30, 40, 30), widths)
    }

    @Test
    fun `non positive width falls back to minimums`() {
        assertArrayEquals(intArrayOf(10, 20), proportionalWidths(0, listOf(1.0, 1.0), listOf(10, 20)))
        assertArrayEquals(intArrayOf(10, 20), proportionalWidths(-5, listOf(1.0, 1.0), listOf(10, 20)))
    }

    @Test
    fun `empty weights produce no columns`() {
        assertEquals(0, proportionalWidths(100, emptyList(), emptyList()).size)
    }

    @Test
    fun `two column tables split by their own ratio`() {
        val widths = proportionalWidths(700, listOf(3.0, 7.0), listOf(0, 0))

        assertEquals(700, widths.sum())
        assertEquals(210, widths[0])
        assertEquals(490, widths[1])
    }
}
