package com.ahao.clicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlaySizeTest {
    private val bounds = OverlayRect(10, 30, 980, 1_920)
    private fun restore(size: OverlaySize, density: Float = 2f, area: OverlayRect = bounds,
        left: Int = 20, top: Int = 60, minHeight: (Int) -> Int = { 180 }) =
        restoreOverlaySize(size, density, left, top, area, 256, minHeight)

    @Test fun `fractional dp dimensions survive encode and restore exactly at same density`() {
        val size = OverlaySize(401 / 2.625f, 503 / 2.625f)
        assertEquals(size, OverlaySize.decode(size.encode()))
        assertEquals(OverlayRect(20, 60, 401, 503), restore(size, 2.625f))
    }
    @Test fun `missing malformed nonpositive and nonfinite data falls back to defaults`() {
        listOf("", "x,200", "100", "100,200,300", "0,200", "100,-2", "NaN,200", "100,Infinity", "1e50,200")
            .forEach { assertNull(it, OverlaySize.decode(it)) }
    }
    @Test fun `density changes keep logical size`() {
        assertEquals(OverlayRect(20, 60, 600, 750), restore(OverlaySize(200f, 250f), 3f))
    }
    @Test fun `new safe area crops dimensions without changing saved size`() {
        val size = OverlaySize(400f, 700f)
        val small = OverlayRect(30, 20, 500, 300)
        assertEquals(small, restore(size, area = small))
        assertEquals(OverlayRect(20, 60, 800, 1_400), restore(size))
    }
    @Test fun `minimum height is calculated from final clamped width`() {
        assertEquals(OverlayRect(20, 60, 256, 356), restore(OverlaySize(1f, 1f), minHeight = { it + 100 }))
        assertEquals(OverlayRect(10, 30, 90, 70), restore(OverlaySize(1f, 1f), area = OverlayRect(10, 30, 90, 70)))
    }
    @Test fun `position shifts inside safe bounds instead of unnecessarily shrinking window`() {
        assertEquals(OverlayRect(190, 950, 800, 1_000), restore(OverlaySize(400f, 500f), left = 900, top = 1_900))
    }
}
