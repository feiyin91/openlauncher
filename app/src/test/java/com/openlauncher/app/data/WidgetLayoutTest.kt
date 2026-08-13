package com.openlauncher.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutTest {

    @Test
    fun `moving to a free cell just relocates it, others untouched`() {
        val layout = listOf(
            WidgetConfig("A", gridX = 0, gridY = 0),
            WidgetConfig("B", gridX = 1, gridY = 0)
        )
        val result = computeWidgetMove(layout, movingId = "A", targetX = 2, targetY = 1)

        val a = result.find { it.id == "A" }!!
        val b = result.find { it.id == "B" }!!
        assertEquals(2, a.gridX); assertEquals(1, a.gridY)
        assertEquals(1, b.gridX); assertEquals(0, b.gridY) // unchanged
    }

    @Test
    fun `moving onto an occupied cell displaces the occupant to a free one`() {
        val layout = listOf(
            WidgetConfig("A", gridX = 0, gridY = 0),
            WidgetConfig("B", gridX = 1, gridY = 0)
        )
        val result = computeWidgetMove(layout, movingId = "A", targetX = 1, targetY = 0)

        val a = result.find { it.id == "A" }!!
        val b = result.find { it.id == "B" }!!
        assertEquals(1, a.gridX); assertEquals(0, a.gridY) // moved widget gets its requested spot
        // Displaced widget must land somewhere that doesn't overlap the mover,
        // and stay within the grid.
        assertTrue(b.gridX != a.gridX || b.gridY != a.gridY)
        assertTrue(b.gridX in 0 until GRID_COLS && b.gridY in 0 until GRID_ROWS)
    }

    @Test
    fun `target position is clamped to stay within the grid`() {
        val layout = listOf(WidgetConfig("A", gridX = 0, gridY = 0, spanX = 2, spanY = 1))
        val result = computeWidgetMove(layout, movingId = "A", targetX = 99, targetY = 99)

        val a = result.find { it.id == "A" }!!
        assertTrue(a.gridX <= GRID_COLS - a.spanX)
        assertTrue(a.gridY <= GRID_ROWS - a.spanY)
    }
}
