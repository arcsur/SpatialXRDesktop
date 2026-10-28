package com.spatial.xr

import android.view.MotionEvent
import com.spatial.xr.window.SpatialWindowManager
import com.spatial.xr.window.SpatialWindowManager.*
import com.spatial.xr.window.WindowInputInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4WindowManagerTest {

    @Test
    fun testWindowCreationAndFocus() {
        val wm = SpatialWindowManager()
        val win1 = wm.createWindow(1, "com.example.browser", 101)
        assertEquals(1, win1.windowId)
        assertEquals(0.96f, win1.widthMeters, 1e-4f)
        assertEquals(0.54f, win1.heightMeters, 1e-4f)
        assertEquals(1, wm.getFocusedWindow()?.windowId)

        val win2 = wm.createWindow(2, "com.example.notes", 102)
        assertEquals(2, wm.getFocusedWindow()?.windowId)

        wm.setFocusedWindow(1)
        assertEquals(1, wm.getFocusedWindow()?.windowId)
    }

    @Test
    fun testFloatingFollowerAlgorithm() {
        val wm = SpatialWindowManager()
        val win = wm.createWindow(1, "com.example.app", 101, anchorMode = AnchorMode.FLOATING)

        // Head looking straight forward
        val headPos = Vector3(0f, 0f, 0f)
        val headRotForward = Quaternion.IDENTITY

        // Update when head is forward: angle = 0 (< 18 deg), no movement needed
        wm.updateFollowerWindows(headPos, headRotForward, 0.016f)
        assertEquals(0f, win.position.x, 1e-4f)
        assertEquals(0f, win.position.y, 1e-4f)
        assertEquals(-1.2f, win.position.z, 1e-4f)

        // Turn head 45 degrees to the right (Yaw around Y axis)
        // Q = (cos(22.5 deg), 0, sin(22.5 deg), 0) ~ (0.9239, 0, 0.3827, 0)
        val yaw45 = Quaternion(0.92388f, 0f, 0.38268f, 0f)

        // Update follower window over multiple frames
        for (i in 0 until 60) {
            wm.updateFollowerWindows(headPos, yaw45, 0.016f)
        }

        // Window should have slerped towards new head gaze
        assertTrue("Window must follow gaze in X axis", Math.abs(win.position.x) > 0.1f)
        // Distance from head must remain ~1.2m
        val dist = win.position.length()
        assertEquals(1.2f, dist, 0.05f)
    }

    @Test
    fun testCornerResizeHandles() {
        val wm = SpatialWindowManager()
        val win = wm.createWindow(1, "com.example.app", 101, widthMeters = 1.0f, heightMeters = 0.6f)

        // Drag bottom-right corner outwards by +0.2m in X and -0.1m in Y
        val resized = wm.resizeWindowByHandle(1, ResizeCorner.BOTTOM_RIGHT, 0.2f, -0.1f)
        assertTrue(resized)
        assertEquals(1.2f, win.widthMeters, 1e-4f)
        assertEquals(0.7f, win.heightMeters, 1e-4f)
    }

    @Test
    fun testRaycastToPixelMapping() {
        // Quad 1.0m x 1.0m, display 1280 x 720
        // Ray origin at (0, 0, 2), dir = (0, 0, -1) -> hits dead center
        val centerRay = WindowInputInjector.Ray(0f, 0f, 2f, 0f, 0f, -1f)
        val hit = WindowInputInjector.raycastQuad(centerRay, 1.0f, 1.0f, 1280, 720)

        assertTrue(hit.isHit)
        assertEquals(2.0f, hit.distance, 1e-4f)
        assertEquals(0.5f, hit.u, 1e-4f)
        assertEquals(0.5f, hit.v, 1e-4f)
        assertEquals(640f, hit.pixelX, 1.0f)
        assertEquals(360f, hit.pixelY, 1.0f)

        // Raycast missing quad to the right
        val missRay = WindowInputInjector.Ray(2.0f, 0f, 2f, 0f, 0f, -1f)
        val missHit = WindowInputInjector.raycastQuad(missRay, 1.0f, 1.0f, 1280, 720)
        assertFalse(missHit.isHit)
    }

    @Test
    fun testIronManTwoHandDiagonalStretchAndShrink() {
        val wm = SpatialWindowManager()
        val win = wm.createWindow(1, "com.example.hologram", 101, widthMeters = 1.0f, heightMeters = 0.6f)

        // Initial diagonal distance between opposing corner pinches
        val initialDist = 1.166f // sqrt(1.0^2 + 0.6^2) ~ 1.166m
        val started = wm.startTwoHandDiagonalResize(1, initialDist)
        assertTrue(started)

        // Stretch hands apart by 1.5x (to 1.749m)
        val stretched = wm.updateTwoHandDiagonalResize(1, initialDist * 1.5f)
        assertTrue(stretched)
        assertEquals(1.5f, win.widthMeters, 1e-3f)
        assertEquals(0.9f, win.heightMeters, 1e-3f)

        // Shrink hands together to 0.5x (to 0.583m)
        val shrunk = wm.updateTwoHandDiagonalResize(1, initialDist * 0.5f)
        assertTrue(shrunk)
        assertEquals(0.5f, win.widthMeters, 1e-3f)
        assertEquals(0.3f, win.heightMeters, 1e-3f)

        wm.endTwoHandDiagonalResize(1)
    }

    @Test
    fun testTopBar3DRepositioningWithPerspectiveScaling() {
        val wm = SpatialWindowManager()
        val win = wm.createWindow(1, "com.example.window", 101, widthMeters = 1.0f, heightMeters = 0.6f)
        win.position = Vector3(0f, 0f, -1.2f) // Standard 1.2m viewing distance

        // Pinch top bar at hand position (0, 0.3, -1.2)
        val handStart = Vector3(0f, 0.3f, -1.2f)
        val grabStarted = wm.startTopBarReposition(1, handStart)
        assertTrue(grabStarted)

        // Push window farther away from -1.2m to -2.4m (double distance) and shift slightly right
        val handFar = Vector3(0.4f, 0.4f, -2.4f)
        val updated = wm.updateTopBarReposition(1, handFar, enablePerspectiveScaling = true)
        assertTrue(updated)

        // Position moved in 3D
        assertEquals(0.4f, win.position.x, 1e-4f)
        assertEquals(0.1f, win.position.y, 1e-4f)
        assertEquals(-2.4f, win.position.z, 1e-4f)

        // Perspective scaling: distance doubled from 1.2m to 2.4m, dimensions doubled to maintain angular readability
        assertEquals(2.0f, win.widthMeters, 1e-3f)
        assertEquals(1.2f, win.heightMeters, 1e-3f)

        // Pull window closer to -0.6m (half distance)
        val handClose = Vector3(0f, 0.3f, -0.6f)
        wm.updateTopBarReposition(1, handClose, enablePerspectiveScaling = true)
        assertEquals(-0.6f, win.position.z, 1e-4f)
        assertEquals(0.5f, win.widthMeters, 1e-3f)
        assertEquals(0.3f, win.heightMeters, 1e-3f)

        wm.endTopBarReposition(1)
    }

    @Test
    fun testTopBarHitDetection() {
        // Quad 1.0m x 1.0m (halfH = 0.5m). Top 0.08m bar is Y in [0.42m, 0.50m]
        // Ray aimed near top edge (Y = +0.46m)
        val topRay = WindowInputInjector.Ray(0f, 0.46f, 2f, 0f, 0f, -1f)
        val topHit = WindowInputInjector.raycastQuad(topRay, 1.0f, 1.0f, 1280, 720)
        assertTrue(topHit.isHit)
        assertTrue(WindowInputInjector.isTopGrabBarHit(topHit, 1.0f, 0.08f))

        // Ray aimed at center (Y = 0.0m)
        val centerRay = WindowInputInjector.Ray(0f, 0f, 2f, 0f, 0f, -1f)
        val centerHit = WindowInputInjector.raycastQuad(centerRay, 1.0f, 1.0f, 1280, 720)
        assertTrue(centerHit.isHit)
        assertFalse(WindowInputInjector.isTopGrabBarHit(centerHit, 1.0f, 0.08f))
    }
}
