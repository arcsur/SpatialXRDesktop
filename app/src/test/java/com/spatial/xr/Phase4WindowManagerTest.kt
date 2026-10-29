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
    fun testIronManSimultaneousResizeRepositionAndHorizontalAngling() {
        val wm = SpatialWindowManager()
        val win = wm.createWindow(1, "com.example.ironman", 101, widthMeters = 1.0f, heightMeters = 0.6f)
        win.position = Vector3(0f, 0f, -1.2f)

        // Hand 1 at top-left (-0.5, 0.3, -1.2), Hand 2 at bottom-right (0.5, -0.3, -1.2)
        val h1Start = Vector3(-0.5f, 0.3f, -1.2f)
        val h2Start = Vector3(0.5f, -0.3f, -1.2f)

        val started = wm.startTwoHandDiagonalTransform(1, h1Start, h2Start)
        assertTrue(started)

        // Simultaneous manipulation:
        // 1. Move midpoint by (+0.2, +0.1, -0.5) -> new center at (0.2, 0.1, -1.7)
        // 2. Stretch diagonal distance
        // 3. Push right hand deeper in Z, pull left hand closer -> horizontal angle tilt
        val h1Curr = Vector3(-0.55f, 0.55f, -1.3f)
        val h2Curr = Vector3(0.95f, -0.35f, -2.1f)

        val updated = wm.updateTwoHandDiagonalTransform(1, h1Curr, h2Curr)
        assertTrue(updated)

        // 1. Verify 3D repositioning
        assertEquals(0.2f, win.position.x, 1e-4f)
        assertEquals(0.1f, win.position.y, 1e-4f)
        assertEquals(-1.7f, win.position.z, 1e-4f)

        // 2. Verify resizing (distance stretched)
        assertTrue(win.widthMeters > 1.3f)
        assertTrue(win.heightMeters > 0.8f)

        // 3. Verify horizontal angling (yaw rotation)
        // Right vector (1, 0, 0) rotated by window orientation:
        val rotatedRight = wm.rotateVector(Vector3(1f, 0f, 0f), win.orientation)
        // Right side should be angled deeper in Z (negative Z)
        assertTrue(rotatedRight.z < -0.1f)

        // Left vector (-1, 0, 0) rotated by window orientation:
        val rotatedLeft = wm.rotateVector(Vector3(-1f, 0f, 0f), win.orientation)
        // Left side should be angled closer to user (positive Z)
        assertTrue(rotatedLeft.z > 0.1f)

        wm.endTwoHandDiagonalTransform(1)
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

    @Test
    fun testGenerousCloseEnoughTopGrabBarHitWithSlack() {
        val quadW = 1.0f // halfW = 0.5f
        val quadH = 0.6f // halfH = 0.3f

        // Point strictly inside top bar (e.g. Y = 0.28m)
        assertTrue(WindowInputInjector.isTopGrabBarHitWithSlack(0.1f, 0.28f, quadW, quadH))

        // Point 15cm ABOVE top edge in empty air (Y = +0.45m > halfH 0.3m) -> Close enough!
        assertTrue(WindowInputInjector.isTopGrabBarHitWithSlack(0.0f, 0.45f, quadW, quadH))

        // Point 15cm past right side of top bar (X = +0.65m > halfW 0.5m) -> Close enough!
        assertTrue(WindowInputInjector.isTopGrabBarHitWithSlack(0.65f, 0.32f, quadW, quadH))

        // Point far above window (Y = +0.70m > halfH + slack) -> Outside
        assertFalse(WindowInputInjector.isTopGrabBarHitWithSlack(0.0f, 0.70f, quadW, quadH))

        // Point at center of window (Y = 0.0m) -> Not top bar
        assertFalse(WindowInputInjector.isTopGrabBarHitWithSlack(0.0f, 0.0f, quadW, quadH))
    }

    @Test
    fun testGenerousCloseEnoughCornerResizeHandles() {
        val quadW = 1.0f // halfW = 0.5f
        val quadH = 0.6f // halfH = 0.3f

        // Bottom-Right corner is at (+0.5, -0.3)
        // Pinching 14cm outside bottom-right corner at (+0.60, -0.38) -> Snaps to BOTTOM_RIGHT
        val brCorner = WindowInputInjector.getNearestResizeCorner(0.60f, -0.38f, quadW, quadH, toleranceMeters = 0.24f)
        assertEquals(ResizeCorner.BOTTOM_RIGHT, brCorner)

        // Top-Left corner is at (-0.5, +0.3)
        // Pinching 12cm outside top-left at (-0.58, +0.38) -> Snaps to TOP_LEFT
        val tlCorner = WindowInputInjector.getNearestResizeCorner(-0.58f, 0.38f, quadW, quadH, toleranceMeters = 0.24f)
        assertEquals(ResizeCorner.TOP_LEFT, tlCorner)

        // Center of window -> Not near any corner
        val centerCorner = WindowInputInjector.getNearestResizeCorner(0f, 0f, quadW, quadH, toleranceMeters = 0.24f)
        org.junit.Assert.assertNull(centerCorner)
    }

    @Test
    fun testGenerousCloseEnoughEdgeResizeHandles() {
        val quadW = 1.0f // halfW = 0.5f
        val quadH = 0.6f // halfH = 0.3f

        // Right edge is at X = +0.5m, Y in [-0.3, +0.3]
        // Pinching 8cm outside right edge at (+0.58, 0.05) -> Snaps to right edge resize
        val rightEdgeCorner = WindowInputInjector.getNearestEdgeResizeCorner(0.58f, 0.05f, quadW, quadH, edgeSlackMeters = 0.20f)
        assertEquals(ResizeCorner.TOP_RIGHT, rightEdgeCorner)

        // Bottom edge is at Y = -0.3m
        // Pinching 9cm below bottom edge at (0.1, -0.39) -> Snaps to bottom edge resize
        val bottomEdgeCorner = WindowInputInjector.getNearestEdgeResizeCorner(0.10f, -0.39f, quadW, quadH, edgeSlackMeters = 0.20f)
        assertEquals(ResizeCorner.BOTTOM_RIGHT, bottomEdgeCorner)

        // Far away outside window -> Null
        val farOutside = WindowInputInjector.getNearestEdgeResizeCorner(1.2f, 0.0f, quadW, quadH, edgeSlackMeters = 0.20f)
        org.junit.Assert.assertNull(farOutside)
    }

    @Test
    fun testMultipleWindowsSelectionNearPinchingFingers() {
        val wm = SpatialWindowManager()
        val win1 = wm.createWindow(1, "com.spatial.left", 101, widthMeters = 0.8f, heightMeters = 0.5f)
        win1.position = Vector3(-0.55f, 0.05f, -1.2f)

        val win2 = wm.createWindow(2, "com.spatial.right", 102, widthMeters = 0.8f, heightMeters = 0.5f)
        win2.position = Vector3(0.55f, 0.05f, -1.2f)

        // Pinching fingers near right window
        val rightPinch = wm.findClosestWindow(0.50f, 0.0f, -1.2f)
        assertNotNull(rightPinch)
        assertEquals(2, rightPinch?.windowId)

        // Pinching fingers near left window
        val leftPinch = wm.findClosestWindow(-0.50f, 0.0f, -1.2f)
        assertNotNull(leftPinch)
        assertEquals(1, leftPinch?.windowId)
    }

    @Test
    fun testTwoHandTransformTargetsWindowNearPinchingFingers() {
        val wm = SpatialWindowManager()
        val win1 = wm.createWindow(1, "com.spatial.left", 101, widthMeters = 0.8f, heightMeters = 0.5f)
        win1.position = Vector3(-0.6f, 0.0f, -1.2f)

        val win2 = wm.createWindow(2, "com.spatial.right", 102, widthMeters = 0.8f, heightMeters = 0.5f)
        win2.position = Vector3(0.6f, 0.0f, -1.2f)

        // Pinching with two hands around Window 2
        val h1 = Vector3(0.3f, 0.2f, -1.2f)
        val h2 = Vector3(0.9f, -0.2f, -1.2f)
        val midX = (h1.x + h2.x) * 0.5f // 0.6f
        val midY = (h1.y + h2.y) * 0.5f // 0.0f
        val midZ = (h1.z + h2.z) * 0.5f // -1.2f

        val targetWin = wm.findClosestWindow(midX, midY, midZ)
        assertEquals(2, targetWin?.windowId)

        wm.startTwoHandDiagonalTransform(targetWin!!.windowId, h1, h2)

        // Stretch Window 2
        val h1Far = Vector3(0.15f, 0.3f, -1.2f)
        val h2Far = Vector3(1.05f, -0.3f, -1.2f)
        wm.updateTwoHandDiagonalTransform(targetWin.windowId, h1Far, h2Far)

        // Window 2 resized, Window 1 unchanged
        assertTrue("Window 2 must be resized", win2.widthMeters > 0.9f)
        assertEquals(0.8f, win1.widthMeters, 1e-4f)
        assertEquals(0.5f, win1.heightMeters, 1e-4f)

        wm.endTwoHandDiagonalTransform(targetWin.windowId)
    }

    @Test
    fun testTwoHandYawDeadbandSuppressesDepthChatter() {
        val wm = SpatialWindowManager()
        val win = wm.createWindow(1, "com.example.deadband", 101, widthMeters = 1.0f, heightMeters = 0.6f)
        win.position = Vector3(0f, 0f, -1.2f)

        val h1 = Vector3(-0.5f, 0.3f, -1.2f)
        val h2 = Vector3(0.5f, -0.3f, -1.2f)
        wm.startTwoHandDiagonalTransform(1, h1, h2)
        val initialOrientation = win.orientation

        // Micro-tremor in depth: right hand fluctuates by 8mm (-1.208 vs -1.2)
        // Angle diff: atan2(-0.008, 1.0) = -0.008 rad (~0.45 deg), well below 0.026 rad (1.5 deg) deadband
        val h1Jitter = Vector3(-0.5f, 0.3f, -1.2f)
        val h2Jitter = Vector3(0.5f, -0.3f, -1.208f)
        wm.updateTwoHandDiagonalTransform(1, h1Jitter, h2Jitter)

        // Orientation MUST remain strictly locked (no chatter)
        assertEquals(initialOrientation.w, win.orientation.w, 1e-4f)
        assertEquals(initialOrientation.y, win.orientation.y, 1e-4f)

        // Deliberate angular tilt: right hand pushed 25cm deeper (-1.45 vs -1.2)
        // Angle diff: atan2(-0.25, 1.0) = -0.245 rad (~14 deg), well above deadband
        val h2Tilt = Vector3(0.5f, -0.3f, -1.45f)
        wm.updateTwoHandDiagonalTransform(1, h1, h2Tilt)

        // Orientation MUST update to reflect intentional turn
        assertTrue(kotlin.math.abs(win.orientation.y - initialOrientation.y) > 0.05f)

        wm.endTwoHandDiagonalTransform(1)
    }

    @Test
    fun testTwoHandScaleDeadbandSuppressesTremor() {
        val wm = SpatialWindowManager()
        val win = wm.createWindow(1, "com.example.scaledeadband", 101, widthMeters = 1.0f, heightMeters = 0.6f)
        win.position = Vector3(0f, 0f, -1.2f)

        val h1 = Vector3(-0.5f, 0.3f, -1.2f)
        val h2 = Vector3(0.5f, -0.3f, -1.2f)
        wm.startTwoHandDiagonalTransform(1, h1, h2)

        val initialW = win.widthMeters
        val initialH = win.heightMeters

        // Micro-tremor: ~0.4% distance change (< 1.5% deadband)
        val h1Tremor = Vector3(-0.502f, 0.3f, -1.2f)
        val h2Tremor = Vector3(0.502f, -0.3f, -1.2f)
        wm.updateTwoHandDiagonalTransform(1, h1Tremor, h2Tremor)

        assertEquals(initialW, win.widthMeters, 1e-4f)
        assertEquals(initialH, win.heightMeters, 1e-4f)

        // Intentional stretch: 30% distance change
        val h1Stretch = Vector3(-0.65f, 0.39f, -1.2f)
        val h2Stretch = Vector3(0.65f, -0.39f, -1.2f)
        wm.updateTwoHandDiagonalTransform(1, h1Stretch, h2Stretch)

        assertTrue(win.widthMeters > 1.2f)

        wm.endTwoHandDiagonalTransform(1)
    }
}
