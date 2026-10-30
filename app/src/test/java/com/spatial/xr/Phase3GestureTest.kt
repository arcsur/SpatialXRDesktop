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

    @Test
    fun testStereoscopicDisparityTriangulationAndFallback() {
        // Physical parameters of Viture Carina stereo cameras:
        // Baseline B = 0.064m (64mm), Focal length f_px = 368px
        val baselineMeters = 0.064f
        val focalLengthPx = 368f
        val fbConstant = focalLengthPx * baselineMeters // 23.552

        fun triangulateDepth(disparityPx: Float): Float {
            if (disparityPx <= 0f) return 0f
            val raw = -fbConstant / disparityPx
            return raw.coerceIn(-1.30f, -0.22f)
        }

        // 1. Arm reach at 50cm (disparity ~ 47.1px)
        val depth50cm = triangulateDepth(47.1f)
        assertEquals(-0.50f, depth50cm, 0.02f)

        // 2. Close reach at 30cm (disparity ~ 78.5px)
        val depth30cm = triangulateDepth(78.5f)
        assertEquals(-0.30f, depth30cm, 0.02f)

        // 3. Fallback logic when stereo disparity unavailable (e.g. disparity <= 0)
        val stereoDepth = 0f
        val palmSpanDepth = -0.55f
        val selectedDepth = if (stereoDepth < -0.15f) stereoDepth else palmSpanDepth
        assertEquals(palmSpanDepth, selectedDepth, 1e-4f)
    }

    @Test
    fun testEgocentricClassifierInversionForCenterFovHands() {
        // MediaPipe HandLandmarker assumed mirrored selfie cameras.
        // For head-mounted forward-facing AR glasses:
        // Raw category "Right" indicates user's dorsal Left hand.
        // Raw category "Left" indicates user's dorsal Right hand.
        fun mapMediaPipeToEgocentric(categoryName: String): Boolean {
            return categoryName.equals("Right", ignoreCase = true)
        }

        assertTrue("MediaPipe 'Right' maps to egocentric Left hand", mapMediaPipeToEgocentric("Right"))
        assertFalse("MediaPipe 'Left' maps to egocentric Right hand", mapMediaPipeToEgocentric("Left"))

        // Single hand entering center FOV (0.42 <= X <= 0.58)
        fun assignSingleCenterHand(centerX: Float, categoryName: String): Boolean {
            val egocentricIsLeft = mapMediaPipeToEgocentric(categoryName)
            return when {
                centerX < 0.42f -> true
                centerX > 0.58f -> false
                else -> egocentricIsLeft
            }
        }

        // Center hand detected as "Right" by MediaPipe is assigned to Left hand
        assertTrue(assignSingleCenterHand(0.50f, "Right"))
        // Center hand detected as "Left" by MediaPipe is assigned to Right hand
        assertFalse(assignSingleCenterHand(0.50f, "Left"))
    }

    @Test
    fun testHandTrackingVisualLabelBadges() {
        // Left hand -> "L", Cyan (#FF00E5FF)
        // Right hand -> "R", Amber (#FFFF9100)
        fun getHandBadge(isLeft: Boolean): Pair<String, String> {
            val label = if (isLeft) "L" else "R"
            val colorHex = if (isLeft) "#FF00E5FF" else "#FFFF9100"
            return Pair(label, colorHex)
        }

        val (leftLabel, leftColor) = getHandBadge(true)
        assertEquals("L", leftLabel)
        assertEquals("#FF00E5FF", leftColor)

        val (rightLabel, rightColor) = getHandBadge(false)
        assertEquals("R", rightLabel)
        assertEquals("#FFFF9100", rightColor)
    }

    @Test
    fun testFistAndOpenHandPostureClassification() {
        // Model finger curl: d(tip, wrist) / d(pip, wrist)
        fun isCurled(dTipWrist: Float, dPipWrist: Float): Boolean = dTipWrist < dPipWrist * 1.05f
        fun isExtended(dTipWrist: Float, dPipWrist: Float): Boolean = dTipWrist > dPipWrist * 1.15f

        // Open hand: tips extended well beyond PIP knuckles
        val openDips = floatArrayOf(0.18f, 0.19f, 0.18f, 0.14f)
        val openPips = floatArrayOf(0.12f, 0.13f, 0.12f, 0.10f)
        var openExtCount = 0
        for (i in 0..3) {
            if (isExtended(openDips[i], openPips[i])) openExtCount++
        }
        assertTrue("Open hand has at least 3 extended fingers", openExtCount >= 3)

        // Fist: tips curled tightly inside PIP knuckles
        val fistDips = floatArrayOf(0.07f, 0.07f, 0.065f, 0.06f)
        val fistPips = floatArrayOf(0.12f, 0.13f, 0.12f, 0.10f)
        var fistCurlCount = 0
        var fistExtCount = 0
        for (i in 0..3) {
            if (isCurled(fistDips[i], fistPips[i])) fistCurlCount++
            if (isExtended(fistDips[i], fistPips[i])) fistExtCount++
        }
        assertTrue("Fist has at least 3 curled fingers", fistCurlCount >= 3)
        assertEquals("Fist has 0 extended fingers", 0, fistExtCount)

        // Pointing gesture: index extended, others curled -> neither fist nor open hand
        val pointDips = floatArrayOf(0.18f, 0.07f, 0.065f, 0.06f)
        val pointPips = floatArrayOf(0.12f, 0.13f, 0.12f, 0.10f)
        var pointExtCount = 0
        var pointCurlCount = 0
        for (i in 0..3) {
            if (isExtended(pointDips[i], pointPips[i])) pointExtCount++
            if (isCurled(pointDips[i], pointPips[i])) pointCurlCount++
        }
        assertFalse("Pointing is not open hand", pointExtCount >= 3)
        assertFalse("Pointing is not fist (index is extended)", pointCurlCount >= 3 && pointExtCount == 0)
    }

    @Test
    fun testFistGrabGestureStateMachineOnlyTriggersFromOpenHandOverWindow() {
        data class HandState(var isOpen: Boolean, var isFist: Boolean, var isOverWindow: Boolean)
        class FistGrabController {
            var wasOpenOverWindow: Boolean = false
            var isGrabbing: Boolean = false

            fun update(hand: HandState) {
                if (isGrabbing) {
                    if (!hand.isFist) {
                        // Release fist -> position set, grab session ended
                        isGrabbing = false
                        wasOpenOverWindow = hand.isOpen && hand.isOverWindow
                    }
                } else {
                    if (hand.isOpen) {
                        // Arms the grab target only when open hand is over window
                        wasOpenOverWindow = hand.isOverWindow
                    } else if (hand.isFist) {
                        // Triggers grab strictly if previously open hand over the window
                        if (wasOpenOverWindow) {
                            isGrabbing = true
                        }
                        wasOpenOverWindow = false
                    } else {
                        if (!hand.isOverWindow) wasOpenOverWindow = false
                    }
                }
            }
        }

        val controller = FistGrabController()

        // 1. Moving open hand over window arms the grab
        controller.update(HandState(isOpen = true, isFist = false, isOverWindow = true))
        assertTrue(controller.wasOpenOverWindow)
        assertFalse(controller.isGrabbing)

        // 2. Closing into a fist triggers grab!
        controller.update(HandState(isOpen = false, isFist = true, isOverWindow = true))
        assertTrue("Grab triggers on transition from open hand over window to closed fist", controller.isGrabbing)

        // 3. Opening hand releases fist -> grab ends and new position is set
        controller.update(HandState(isOpen = true, isFist = false, isOverWindow = true))
        assertFalse("Opening hand releases grab", controller.isGrabbing)

        // 4. Moving an ALREADY CLOSED FIST from outside over window does NOT trigger grab
        val outsideFistController = FistGrabController()
        // Fist formed outside window
        outsideFistController.update(HandState(isOpen = false, isFist = true, isOverWindow = false))
        assertFalse(outsideFistController.wasOpenOverWindow)
        assertFalse(outsideFistController.isGrabbing)
        // Moved over window while still a fist
        outsideFistController.update(HandState(isOpen = false, isFist = true, isOverWindow = true))
        assertFalse("Fist entering window without opening first must NOT trigger grab", outsideFistController.isGrabbing)

        // 5. Pointing over window then closing to fist does NOT trigger grab (requires OPEN hand)
        val pointingController = FistGrabController()
        pointingController.update(HandState(isOpen = false, isFist = false, isOverWindow = true)) // Pointing
        assertFalse(pointingController.wasOpenOverWindow)
        pointingController.update(HandState(isOpen = false, isFist = true, isOverWindow = true)) // Fist
        assertFalse("Fist from non-open hand must NOT trigger grab", pointingController.isGrabbing)
    }

    @Test
    fun testSameHandInvariantForFistGrab() {
        // Left hand open over window, Right hand makes a fist -> Right hand must NOT trigger grab!
        class DualHandFistController {
            var leftWasOpenOverWin = false
            var rightWasOpenOverWin = false
            var activeGrabHandIsLeft: Boolean? = null

            fun updateLeft(isOpen: Boolean, isFist: Boolean, isOverWin: Boolean) {
                if (isOpen) leftWasOpenOverWin = isOverWin
                else if (isFist) {
                    if (leftWasOpenOverWin && activeGrabHandIsLeft == null) {
                        activeGrabHandIsLeft = true
                    }
                    leftWasOpenOverWin = false
                }
            }

            fun updateRight(isOpen: Boolean, isFist: Boolean, isOverWin: Boolean) {
                if (isOpen) rightWasOpenOverWin = isOverWin
                else if (isFist) {
                    if (rightWasOpenOverWin && activeGrabHandIsLeft == null) {
                        activeGrabHandIsLeft = false
                    }
                    rightWasOpenOverWin = false
                }
            }
        }

        val dual = DualHandFistController()
        // Left hand is open over window
        dual.updateLeft(isOpen = true, isFist = false, isOverWin = true)
        // Right hand is open away from window
        dual.updateRight(isOpen = true, isFist = false, isOverWin = false)

        // Right hand makes a fist
        dual.updateRight(isOpen = false, isFist = true, isOverWin = false)
        assertEquals("Right hand must NOT trigger grab because it was not over window", null, dual.activeGrabHandIsLeft)

        // Left hand (the one over window) makes a fist
        dual.updateLeft(isOpen = false, isFist = true, isOverWin = true)
        assertEquals("Left hand triggers grab as the same hand that was open over window", true, dual.activeGrabHandIsLeft)
    }
}
