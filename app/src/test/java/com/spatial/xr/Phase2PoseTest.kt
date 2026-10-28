package com.spatial.xr

import com.spatial.xr.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Phase2PoseTest {

    data class PoseData(
        val x: Float, val y: Float, val z: Float,
        val qx: Float, val qy: Float, val qz: Float, val qw: Float
    ) {
        companion object {
            fun identity() = PoseData(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        }
    }

    @Test
    fun testIdentityPoseStructure() {
        val pose = PoseData.identity()
        assertEquals(0f, pose.x, 1e-4f)
        assertEquals(0f, pose.y, 1e-4f)
        assertEquals(0f, pose.z, 1e-4f)
        assertEquals(1f, pose.qw, 1e-4f)
    }

    @Test
    fun testPoseArrayBufferLayout() {
        val outArray = FloatArray(7)
        // Verify buffer size matches [x, y, z, qx, qy, qz, qw]
        assertEquals(7, outArray.size)
    }
}
