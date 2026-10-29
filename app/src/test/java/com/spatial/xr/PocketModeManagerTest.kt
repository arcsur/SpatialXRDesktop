package com.spatial.xr

import android.view.KeyEvent
import com.spatial.xr.display.PocketModeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketModeManagerTest {

    @Test
    fun testProximityThresholdConstants() {
        assertEquals(4.0f, PocketModeManager.PROXIMITY_THRESHOLD_CM, 1e-4f)
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, PocketModeManager.WAKE_FALLBACK_KEY)
    }

    @Test
    fun testEvaluateIsNearInPocket() {
        // Distance 1.5cm with 5cm sensor range -> Inside pocket
        assertTrue(PocketModeManager.evaluateIsNear(1.5f, 5.0f))
        // Distance 0.0cm -> Deep pocket contact
        assertTrue(PocketModeManager.evaluateIsNear(0.0f, 5.0f))
    }

    @Test
    fun testEvaluateIsNearOutOfPocket() {
        // Distance 5.0cm (at max range) -> Outside pocket
        assertFalse(PocketModeManager.evaluateIsNear(5.0f, 5.0f))
        // Distance 4.5cm -> Above threshold
        assertFalse(PocketModeManager.evaluateIsNear(4.5f, 5.0f))
    }

    @Test
    fun testCanWakeOnDoubleTapGuard() {
        // In pocket mode and inside pocket: MUST NOT wake (blocks accidental cloth double taps)
        assertFalse(PocketModeManager.canWakeOnDoubleTap(isPocketMode = true, isNearProximity = true))

        // In pocket mode and out of pocket: Allowed to wake
        assertTrue(PocketModeManager.canWakeOnDoubleTap(isPocketMode = true, isNearProximity = false))

        // Not in pocket mode: Double tap does not trigger pocket wake
        assertFalse(PocketModeManager.canWakeOnDoubleTap(isPocketMode = false, isNearProximity = false))
        assertFalse(PocketModeManager.canWakeOnDoubleTap(isPocketMode = false, isNearProximity = true))
    }
}
