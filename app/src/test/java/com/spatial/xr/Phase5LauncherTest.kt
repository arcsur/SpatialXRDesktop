package com.spatial.xr

import com.spatial.xr.window.AppLauncherRepository
import com.spatial.xr.window.AppLauncherRepository.AppItem
import com.spatial.xr.window.SpatialWindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5LauncherTest {

    @Test
    fun testCurvedDockLayout() {
        val repo = AppLauncherRepository()
        val apps = listOf(
            AppItem("com.android.chrome", "Chrome", "MainActivity"),
            AppItem("com.google.android.youtube", "YouTube", "WatchActivity"),
            AppItem("com.slack", "Slack", "HomeActivity")
        )
        repo.setAppsForTesting(apps)

        val dock = repo.computeCurvedDockLayout(arcRadiusMeters = 0.8f, yOffsetMeters = -0.2f, totalArcAngleDeg = 60f)
        assertEquals(3, dock.size)

        // Middle item should be at X ~ 0, Y = -0.2m, Z = -0.8m
        val middleItem = dock[1]
        assertEquals("YouTube", middleItem.appItem.label)
        assertEquals(0f, middleItem.positionX, 0.05f)
        assertEquals(-0.2f, middleItem.positionY, 1e-4f)
        assertEquals(-0.8f, middleItem.positionZ, 0.05f)

        // First item should be on left (X < 0), third on right (X > 0)
        assertTrue("First dock item must be at left X < 0", dock[0].positionX < -0.1f)
        assertTrue("Third dock item must be at right X > 0", dock[2].positionX > 0.1f)
    }

    @Test
    fun testFullscreenCurvedCinemaMode() {
        val wm = SpatialWindowManager()
        val win1 = wm.createWindow(1, "com.video.player", 101)
        val win2 = wm.createWindow(2, "com.browser", 102)

        // Enter fullscreen on win1
        val entered = wm.enterFullscreen(1)
        assertTrue(entered)

        val fullWin = wm.getWindow(1)!!
        val bgWin = wm.getWindow(2)!!

        assertTrue(fullWin.isFullscreen)
        assertEquals(2.0f, fullWin.curvatureRadius, 1e-4f)
        assertEquals(1.0f, fullWin.opacity, 1e-4f)

        // Other window must be dimmed
        assertFalse(bgWin.isFullscreen)
        assertEquals(0.15f, bgWin.opacity, 1e-4f)

        // Exit fullscreen
        val exited = wm.exitFullscreen(1)
        assertTrue(exited)
        assertFalse(fullWin.isFullscreen)
        assertEquals(0.0f, fullWin.curvatureRadius, 1e-4f)
        assertEquals(1.0f, bgWin.opacity, 1e-4f)
    }

    @Test
    fun testAirSwipeTaskSwitcher() {
        val wm = SpatialWindowManager()
        wm.createWindow(1, "com.app.first", 101)
        wm.createWindow(2, "com.app.second", 102)
        wm.createWindow(3, "com.app.third", 103)

        assertEquals(3, wm.getFocusedWindow()?.windowId)

        // Slow swipe (0.5 m/s < 1.2 m/s threshold) should NOT trigger switch
        val noSwitch = wm.onAirSwipe(0.5f)
        assertNull(noSwitch)
        assertEquals(3, wm.getFocusedWindow()?.windowId)

        // Rapid swipe (1.8 m/s > 1.2 m/s threshold) triggers task switch
        val switchedWinId = wm.onAirSwipe(1.8f)
        assertNotNull(switchedWinId)
        assertEquals(switchedWinId, wm.getFocusedWindow()?.windowId)
    }

    @Test
    fun testWristDockToggle() {
        val wm = SpatialWindowManager()
        assertFalse(wm.isWristDockVisible())
        wm.setWristDockVisible(true)
        assertTrue(wm.isWristDockVisible())
    }

    @Test
    fun testAppDrawerAutoCloseTimeout() {
        val wm = SpatialWindowManager()
        wm.wristDockAutoCloseTimeoutMs = 12000L // 12 seconds auto-close

        // Open app drawer at t = 1000ms
        wm.setWristDockVisible(true, currentTime = 1000L)
        assertTrue(wm.isWristDockVisible())

        // At t = 8000ms (7s elapsed < 12s) -> Still open
        val closedEarly = wm.checkWristDockTimeout(currentTime = 8000L)
        assertFalse(closedEarly)
        assertTrue(wm.isWristDockVisible())

        // At t = 13500ms (12.5s elapsed >= 12s) -> Auto-closes!
        val closedOnTimeout = wm.checkWristDockTimeout(currentTime = 13500L)
        assertTrue(closedOnTimeout)
        assertFalse("App drawer must automatically close after 10-15s timeout", wm.isWristDockVisible())
    }

    @Test
    fun testAppDrawerTimeoutResetOnInteraction() {
        val wm = SpatialWindowManager()
        wm.wristDockAutoCloseTimeoutMs = 12000L

        wm.setWristDockVisible(true)

        // At t = 10000ms, user interacts near dock, resetting timer
        wm.resetWristDockTimeout(currentTime = 10000L)

        // At t = 15000ms (only 5s since reset) -> Stays open
        assertFalse(wm.checkWristDockTimeout(currentTime = 15000L))
        assertTrue(wm.isWristDockVisible())

        // At t = 22500ms (12.5s since reset) -> Auto-closes
        assertTrue(wm.checkWristDockTimeout(currentTime = 22500L))
        assertFalse(wm.isWristDockVisible())
    }
}
