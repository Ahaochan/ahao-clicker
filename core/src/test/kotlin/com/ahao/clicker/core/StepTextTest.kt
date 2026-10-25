package com.ahao.clicker.core

import org.junit.Assert.*
import org.junit.Test

class StepTextTest {
    @Test fun `round trip preserves each point and wait`() {
        val steps = StepText.parse("10,20,0\n30.5,40,125")
        assertEquals(listOf(0L, 125L), steps.map { it.waitBeforeMillis })
        assertEquals(steps, StepText.parse(StepText.format(steps)))
    }
    @Test fun `rejects empty malformed huge and invalid input`() {
        for (text in listOf("", "1,2", "NaN,2,3", "1,2,-1", "1,2,3,4", "x".repeat(64_001))) {
            assertThrows(IllegalArgumentException::class.java) { StepText.parse(text) }
        }
        assertThrows(IllegalArgumentException::class.java) { StepText.parse(List(501) { "1,2,3" }.joinToString("\n")) }
    }
}
