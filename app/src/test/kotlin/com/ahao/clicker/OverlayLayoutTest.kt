package com.ahao.clicker

import org.junit.Assert.*
import org.junit.Test

class OverlayLayoutTest {
    @Test fun `wide header reserves buttons and gives remaining width to move handle`() {
        val cells = overlayHeaderCells(248, 48, 4)
        assertEquals(OverlayRect(0, 0, 48, 48), cells.first())
        assertEquals(OverlayRect(200, 0, 48, 48), cells.last())
        assertEquals(104, cells[2].width)
    }
    @Test fun `narrow multi header shrinks all four controls into their original single row order`() {
        assertEquals(listOf(OverlayRect(0, 0, 30, 30), OverlayRect(30, 0, 30, 30),
            OverlayRect(60, 0, 30, 30), OverlayRect(90, 0, 30, 30)), overlayHeaderCells(120, 48, 4))
    }
    @Test fun `narrow timed header keeps three controls on a single row`() {
        assertEquals(listOf(OverlayRect(0, 0, 40, 40), OverlayRect(40, 0, 40, 40),
            OverlayRect(80, 0, 40, 40)), overlayHeaderCells(120, 48, 3))
    }
    @Test fun `shrinking past the old wrapping threshold reduces height instead of adding a row`() {
        for (count in 3..4) {
            assertEquals(47, overlayHeaderCells(count * 48 - 1, 48, count).maxOf { it.top + it.height })
            assertEquals(48, overlayHeaderCells(count * 48, 48, count).maxOf { it.top + it.height })
        }
    }
    @Test fun `all controls keep their order and remain on one row without clipping across supported widths`() {
        for (width in 120..300) for (count in 3..4) {
            val cells = overlayHeaderCells(width, 48, count)
            assertTrue(cells.all { it.width >= 30 && it.height in 30..48 && it.top == 0 && it.left >= 0 && it.left + it.width <= width })
            assertTrue(cells.zipWithNext().all { (a, b) -> a.left + a.width <= b.left })
            cells.forEachIndexed { i, a -> cells.drop(i + 1).forEach { b ->
                assertTrue(a.left + a.width <= b.left || b.left + b.width <= a.left ||
                    a.top + a.height <= b.top || b.top + b.height <= a.top)
            } }
        }
    }
    @Test fun `large fonts and short tiles keep the entire clock ink inside the available area`() {
        for (fontScale in listOf(1f, 1.5f, 2f)) {
            val textWidth = 187f * fontScale; val textHeight = 31f * fontScale
            for ((width, height) in listOf(120f to 32f, 248f to 20f, 400f to 96f)) {
                val scale = overlayClockScale(width, height, textWidth, textHeight)
                assertTrue(scale > 0 && scale <= 1)
                assertTrue(textWidth * scale <= width + .01f && textHeight * scale <= height + .01f)
            }
        }
    }
    @Test fun `clock keeps natural size when space is sufficient`() {
        assertEquals(1f, overlayClockScale(300f, 80f, 187f, 31f), .001f)
    }
}
