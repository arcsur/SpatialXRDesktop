package com.spatial.xr

import android.view.Display
import com.spatial.xr.bridge.NativeBridge
import com.spatial.xr.display.ExternalDisplayManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1DisplayTest {

    @Test
    fun testDexMitigationAdbString() {
        val expectedAdbCommand = "settings put global dex_auto_start_with_hdmi 0"
        assertEquals(expectedAdbCommand, ExternalDisplayManager.DEX_BYPASS_ADB_COMMAND)
        assertEquals(0x35CA, ExternalDisplayManager.VITURE_VENDOR_ID)
    }

    @Test
    fun testNativeBridgeContract() {
        // Assert that NativeBridge can be accessed and reports library status
        assertFalse(NativeBridge.isNativeLoaded()) // In host JVM unit tests, .so is not loaded
    }
}
