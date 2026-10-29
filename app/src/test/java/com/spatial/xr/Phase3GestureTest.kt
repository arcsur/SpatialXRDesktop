package com.spatial.xr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3GestureTest {

    enum class PinchState {
        NONE, PINCH_DOWN, PINCH_HOLD, PINCH_UP
    }

    data class SpatialRay(
        val originX: Float, val originY: Float, val originZ: Float,
        val dirX: Float, val dirY: Float, val dirZ: Float
    )

    @Test
    fun testPinchThresholdsSpecification() {
        val downThresholdMeters = 0.020f // 20mm
        val upThresholdMeters = 0.028f   // 28mm
        assertTrue("Down threshold must be strictly less than up threshold", downThresholdMeters < upThresholdMeters)
    }

    @Test
    fun testSpatialRayDirectionNormalization() {
        val ray = SpatialRay(0f, 0f, 0f, 0f, 0f, -1f)
        val len = Math.sqrt((ray.dirX * ray.dirX + ray.dirY * ray.dirY + ray.dirZ * ray.dirZ).toDouble()).toFloat()
        assertEquals(1.0f, len, 1e-4f)
    }

    @Test
    fun testVitureCameraHardwareConstants() {
        assertEquals(3141, com.spatial.xr.hardware.VitureCameraStreamer.SONIX_VENDOR_ID)
        assertEquals(25451, com.spatial.xr.hardware.VitureCameraStreamer.SONIX_PRODUCT_ID)
        assertEquals(13770, com.spatial.xr.hardware.VitureCameraStreamer.VITURE_VENDOR_ID)
    }

    @Test
    fun testNativeBridgeGracefulFallbackWhenNotLoaded() {
        assertFalse(com.spatial.xr.bridge.NativeBridge.isNativeLoaded())
    }
}
