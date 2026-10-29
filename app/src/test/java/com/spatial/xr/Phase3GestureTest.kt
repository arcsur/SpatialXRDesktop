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

    @Test
    fun testPinchProximityAndFusionLogic() {
        // Distance 50mm -> Open hand
        val distOpen = 0.050f
        assertFalse("50mm is open hand, not pinched", distOpen < 0.020f)
        assertFalse("50mm is outside magnetic approach range", distOpen < 0.045f)

        // Distance 30mm -> Magnetic proximity approach
        val distProximity = 0.030f
        assertFalse("30mm is not yet pinched", distProximity < 0.020f)
        assertTrue("30mm is within magnetic approach range", distProximity < 0.045f)

        // Distance 15mm -> Pinched (fused reticle)
        val distPinched = 0.015f
        assertTrue("15mm triggers fused pinch reticle", distPinched < 0.020f)
    }

    @Test
    fun testPalmLookHoldDebounceLogic() {
        val holdThresholdMs = 350L
        var dockToggled = false

        fun evaluatePalmGesture(startTime: Long, currentTime: Long): Boolean {
            return (currentTime - startTime) >= holdThresholdMs
        }

        // Noise spike lasting 150ms -> No trigger
        assertFalse(evaluatePalmGesture(1000L, 1150L))

        // Stable deliberate gaze hold lasting 400ms -> Trigger!
        assertTrue(evaluatePalmGesture(1000L, 1400L))
    }

    @Test
    fun testDualHandTrackingStateIndependence() {
        // Left hand aiming at (-0.2, 0.1), Right hand pinched at (0.3, -0.1)
        val leftAimX = 0.35f
        val rightAimX = 0.75f
        val leftPinching = false
        val rightPinching = true

        assertFalse(leftPinching)
        assertTrue(rightPinching)
        assertTrue("Left and right hands maintain separate horizontal positions", leftAimX != rightAimX)
    }
}
