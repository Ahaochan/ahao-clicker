package com.ahao.clicker.core

import org.junit.Assert.*
import org.junit.Test

class SyncPacingTest {
    @Test fun `manual refresh and page changes respect the per-provider minimum interval`() {
        val pacing = SyncPacing()
        assertEquals(0L, pacing.delay(BuiltinTimeSource.ALIYUN, 100))
        pacing.attempted(BuiltinTimeSource.ALIYUN, 100)
        assertEquals(59_500L, pacing.delay(BuiltinTimeSource.ALIYUN, 600))
        assertEquals(0L, pacing.delay(BuiltinTimeSource.TENCENT, 600))
        assertEquals(0L, pacing.delay(BuiltinTimeSource.ALIYUN, 60_100))
    }
}
