package com.ahao.clicker

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayResizeTest {
    private val available = OverlayRect(10, 30, 980, 1_920)
    private val start = OverlayRect(200, 300, 400, 500)
    private fun resize(dx: Int, dy: Int, left: Boolean = false, rect: OverlayRect = start,
        bounds: OverlayRect = available) = resizeOverlay(rect, bounds, dx, dy, left, 240, 180)

    @Test fun `right bottom grows and shrinks while top left stays fixed`() {
        assertEquals(OverlayRect(200, 300, 520, 650), resize(120, 150))
        assertEquals(OverlayRect(200, 300, 300, 400), resize(-100, -100))
    }
    @Test fun `left bottom anchors right edge when growing or shrinking`() {
        assertEquals(OverlayRect(80, 300, 520, 650), resize(-120, 150, true))
        assertEquals(OverlayRect(300, 300, 300, 400), resize(100, -100, true))
    }
    @Test fun `minimum size keeps controls available from either corner`() {
        assertEquals(OverlayRect(200, 300, 240, 180), resize(-1_000, -1_000))
        assertEquals(OverlayRect(360, 300, 240, 180), resize(1_000, -1_000, true))
    }
    @Test fun `cannot expand through system bars or past screen edges`() {
        assertEquals(OverlayRect(200, 300, 790, 1_650), resize(10_000, 10_000))
        assertEquals(OverlayRect(10, 300, 590, 1_650), resize(-10_000, 10_000, true))
    }
    @Test fun `undersized display uses available area instead of invalid clamp bounds`() {
        val tiny = OverlayRect(5, 20, 200, 120)
        assertEquals(tiny, resize(0, 0, bounds = tiny))
        assertEquals(tiny, resize(0, 0, true, bounds = tiny))
    }
    @Test fun `offscreen starting panel is brought inside the safe area`() {
        val outside = OverlayRect(950, 1_900, 400, 500)
        assertEquals(OverlayRect(750, 1_770, 240, 180), resize(-1_000, -1_000, rect = outside))
    }
}
