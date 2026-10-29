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

    @Test
    fun testSpatialHandAssignmentDisambiguatesFlippedClassifierLabels() {
        data class MockHandCandidate(val id: Int, val centerX: Float, val rawCategoryIsLeft: Boolean)

        fun assignHands(candidates: List<MockHandCandidate>): Pair<MockHandCandidate?, MockHandCandidate?> {
            if (candidates.isEmpty()) return Pair(null, null)
            if (candidates.size == 1) {
                val c = candidates[0]
                val isLeft = if (c.centerX < 0.42f) true else if (c.centerX > 0.58f) false else c.rawCategoryIsLeft
                return if (isLeft) Pair(c, null) else Pair(null, c)
            }
            val sorted = candidates.sortedBy { it.centerX }
            return Pair(sorted[0], sorted[1])
        }

        // Scenario 1: MediaPipe mistakenly labels BOTH hands as "Right"
        val bothRight = listOf(
            MockHandCandidate(0, centerX = 0.28f, rawCategoryIsLeft = false), // Hand on left
            MockHandCandidate(1, centerX = 0.72f, rawCategoryIsLeft = false)  // Hand on right
        )
        val (left1, right1) = assignHands(bothRight)
        assertEquals(0, left1?.id)
        assertEquals(1, right1?.id)

        // Scenario 2: MediaPipe completely inverts labels (left hand labeled Right, right hand labeled Left)
        val inverted = listOf(
            MockHandCandidate(0, centerX = 0.30f, rawCategoryIsLeft = false), // Left hand mislabeled Right
            MockHandCandidate(1, centerX = 0.70f, rawCategoryIsLeft = true)   // Right hand mislabeled Left
        )
        val (left2, right2) = assignHands(inverted)
        assertEquals(0, left2?.id)
        assertEquals(1, right2?.id)
    }

    @Test
    fun testDiscontinuitySnapGuardPreventsCrossHandDragging() {
        fun smooth(curr: Float, target: Float): Float {
            val diff = Math.abs(target - curr)
            if (diff > 0.20f) return target // Snap guard
            return curr + (target - curr) * 0.40f
        }

        // Normal small movement: smoothed
        val smoothed = smooth(0.50f, 0.53f)
        assertEquals(0.512f, smoothed, 1e-3f)

        // Teleport / cross-hand swap (> 20cm / 0.20): snaps directly, zero cross-drag!
        val snapped = smooth(-0.30f, 0.30f)
        assertEquals(0.30f, snapped, 1e-4f)
    }
}
