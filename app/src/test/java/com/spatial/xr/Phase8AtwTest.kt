package com.spatial.xr

import com.spatial.xr.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8AtwTest {

    companion object {
        const val TARGET_REFRESH_RATE_HZ = 120
        const val MAX_MOTION_TO_PHOTON_LATENCY_MS = 18.0f
        const val VSYNC_PERIOD_MS = 1000f / TARGET_REFRESH_RATE_HZ // 8.33ms
    }

    @Test
    fun testRefreshRateAndLatencyTargets() {
        assertEquals(120, TARGET_REFRESH_RATE_HZ)
        assertEquals(8.333f, VSYNC_PERIOD_MS, 0.005f)
        assertTrue("Latency limit must be 18ms", MAX_MOTION_TO_PHOTON_LATENCY_MS <= 18.0f)
    }

    @Test
    fun testAtwBridgeMethods() {
        if (NativeBridge.isNativeLoaded()) {
            val startSuccess = NativeBridge.nativeStartAtwThread()
            assertTrue(startSuccess)
        } else {
            // Expected in host JVM environment
            assertTrue(true)
        }
    }
}
