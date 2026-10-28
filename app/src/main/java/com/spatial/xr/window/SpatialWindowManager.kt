package com.spatial.xr.window

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SpatialWindowManager(
    private val virtualDisplayHost: VirtualDisplayHost? = null
) {

    enum class AnchorMode {
        WORLD_LOCKED,
        FLOATING
    }

    enum class ResizeCorner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    data class Vector3(val x: Float, val y: Float, val z: Float) {
        fun length(): Float = sqrt(x * x + y * y + z * z)
        fun normalized(): Vector3 {
            val len = length()
            return if (len < 1e-6f) Vector3(0f, 0f, 1f) else Vector3(x / len, y / len, z / len)
        }
        fun dot(other: Vector3): Float = x * other.x + y * other.y + z * other.z
    }

    data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {
        companion object {
            val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

            fun slerp(q1: Quaternion, q2: Quaternion, t: Float): Quaternion {
                var dot = q1.w * q2.w + q1.x * q2.x + q1.y * q2.y + q1.z * q2.z
                var target = q2
                if (dot < 0f) {
                    dot = -dot
                    target = Quaternion(-q2.w, -q2.x, -q2.y, -q2.z)
                }

                if (dot > 0.9995f) {
                    // Linear interpolation for small angles
                    val w = q1.w + t * (target.w - q1.w)
                    val x = q1.x + t * (target.x - q1.x)
                    val y = q1.y + t * (target.y - q1.y)
                    val z = q1.z + t * (target.z - q1.z)
                    val len = sqrt(w * w + x * x + y * y + z * z)
                    return Quaternion(w / len, x / len, y / len, z / len)
                }

                val theta0 = acos(dot.coerceIn(-1f, 1f))
                val theta = theta0 * t
                val sinTheta = sin(theta)
                val sinTheta0 = sin(theta0)

                val s1 = cos(theta) - dot * sinTheta / sinTheta0
                val s2 = sinTheta / sinTheta0

                return Quaternion(
                    q1.w * s1 + target.w * s2,
                    q1.x * s1 + target.x * s2,
                    q1.y * s1 + target.y * s2,
                    q1.z * s1 + target.z * s2
                )
            }
        }
    }

    data class SpatialWindow(
        val windowId: Int,
        val packageName: String,
        val displaySessionId: Int,
        var position: Vector3 = Vector3(0f, 0f, -1.2f),
        var orientation: Quaternion = Quaternion.IDENTITY,
        var widthMeters: Float = 0.96f,
        var heightMeters: Float = 0.54f,
        var anchorMode: AnchorMode = AnchorMode.FLOATING,
        var isFullscreen: Boolean = false,
        var opacity: Float = 1.0f,
        var curvatureRadius: Float = 0.0f
    ) {
        val pixelsPerMeter: Float = 1280f / 0.96f // ~1333.33 px/m
    }

    companion object {
        const val FLOATING_VIEWING_DISTANCE = 1.2f // 1.2 meters
        const val FOLLOWER_THRESHOLD_ANGLE_DEG = 18.0f // 18 degrees
        const val FOLLOWER_ALPHA = 4.0f // Slerp responsiveness
        val FOLLOWER_THRESHOLD_COS = cos(Math.toRadians(FLOATING_VIEWING_DISTANCE.toDouble())).toFloat()
    }

    private val windows = ConcurrentHashMap<Int, SpatialWindow>()
    private var focusedWindowId: Int? = null
    private val mruStack = mutableListOf<Int>()
    private var isWristDockVisible = false

    fun isWristDockVisible(): Boolean = isWristDockVisible
    fun setWristDockVisible(visible: Boolean) { isWristDockVisible = visible }

    fun createWindow(
        windowId: Int,
        packageName: String,
        displaySessionId: Int,
        anchorMode: AnchorMode = AnchorMode.FLOATING,
        widthMeters: Float = 0.96f,
        heightMeters: Float = 0.54f
    ): SpatialWindow {
        val window = SpatialWindow(
            windowId = windowId,
            packageName = packageName,
            displaySessionId = displaySessionId,
            anchorMode = anchorMode,
            widthMeters = widthMeters,
            heightMeters = heightMeters
        )
        windows[windowId] = window
        mruStack.remove(windowId)
        mruStack.add(0, windowId)
        focusedWindowId = windowId
        return window
    }

    fun getWindow(windowId: Int): SpatialWindow? = windows[windowId]
    fun getAllWindows(): Collection<SpatialWindow> = windows.values
    fun getFocusedWindow(): SpatialWindow? = focusedWindowId?.let { windows[it] }
    fun getMruStack(): List<Int> = mruStack.toList()

    fun setFocusedWindow(windowId: Int) {
        if (windows.containsKey(windowId)) {
            focusedWindowId = windowId
            mruStack.remove(windowId)
            mruStack.add(0, windowId)
        }
    }

    fun removeWindow(windowId: Int) {
        windows.remove(windowId)
        mruStack.remove(windowId)
        if (focusedWindowId == windowId) {
            focusedWindowId = mruStack.firstOrNull()
        }
    }

    /**
     * Task 5.2: Fullscreen Curved Virtual Cinema Screen (R = 2.0m, 90 deg FOV)
     * Dims other windows to 0.15 opacity.
     */
    fun enterFullscreen(windowId: Int): Boolean {
        val target = windows[windowId] ?: return false
        setFocusedWindow(windowId)

        for (win in windows.values) {
            if (win.windowId == windowId) {
                win.isFullscreen = true
                win.curvatureRadius = 2.0f // 2.0m radius cylindrical curve
                win.opacity = 1.0f
                win.position = Vector3(0f, 0f, -2.0f)
                win.orientation = Quaternion.IDENTITY
            } else {
                win.isFullscreen = false
                win.opacity = 0.15f // Dim surrounding windows
            }
        }
        return true
    }

    /**
     * Exit fullscreen mode and restore all window opacities.
     */
    fun exitFullscreen(windowId: Int): Boolean {
        val target = windows[windowId] ?: return false
        target.isFullscreen = false
        target.curvatureRadius = 0.0f
        target.position = Vector3(0f, 0f, -FLOATING_VIEWING_DISTANCE)

        for (win in windows.values) {
            win.opacity = 1.0f
        }
        return true
    }

    /**
     * Task 5.3: Rapid Task Switcher via Air-Swipe
     * Swipe velocity |vx| > 1.2 m/s cycles through MRU stack.
     */
    fun onAirSwipe(velocityX: Float): Int? {
        if (Math.abs(velocityX) < 1.2f || mruStack.size <= 1) {
            return null
        }

        val currentIndex = mruStack.indexOf(focusedWindowId)
        val nextIndex = if (velocityX > 0) {
            // Swipe right: cycle forward
            (currentIndex + 1) % mruStack.size
        } else {
            // Swipe left: cycle backward
            if (currentIndex - 1 < 0) mruStack.size - 1 else currentIndex - 1
        }

        val nextWindowId = mruStack[nextIndex]
        setFocusedWindow(nextWindowId)

        // If currently in fullscreen mode, transition fullscreen to the new window
        val currentWin = windows[focusedWindowId]
        if (currentWin?.isFullscreen == true) {
            enterFullscreen(nextWindowId)
        }
        return nextWindowId
    }

    /**
     * Task 4.2: Floating follower algorithm.
     * Keeps floating window in user's FOV when gaze deviates > 18 degrees.
     */
    fun updateFollowerWindows(headPosition: Vector3, headOrientation: Quaternion, dt: Float) {
        for (window in windows.values) {
            if (window.anchorMode != AnchorMode.FLOATING || window.isFullscreen) {
                continue
            }

            // Window forward vector relative to head
            val toWindow = Vector3(
                window.position.x - headPosition.x,
                window.position.y - headPosition.y,
                window.position.z - headPosition.z
            ).normalized()

            // Head forward vector (rotate (0,0,-1) by headOrientation)
            val headForward = rotateVector(Vector3(0f, 0f, -1f), headOrientation)

            val cosAngle = toWindow.dot(headForward).coerceIn(-1f, 1f)
            val angleDeg = Math.toDegrees(acos(cosAngle.toDouble())).toFloat()

            if (angleDeg > FOLLOWER_THRESHOLD_ANGLE_DEG) {
                // Slerp orientation towards head
                val t = (FOLLOWER_ALPHA * dt).coerceIn(0f, 1f)
                window.orientation = Quaternion.slerp(window.orientation, headOrientation, t)

                // Position at fixed 1.2m viewing distance along target orientation
                val targetDir = rotateVector(Vector3(0f, 0f, -FLOATING_VIEWING_DISTANCE), window.orientation)
                window.position = Vector3(
                    headPosition.x + targetDir.x,
                    headPosition.y + targetDir.y,
                    headPosition.z + targetDir.z
                )
            }
        }
    }

    /**
     * Task 4.3: 4-Corner 3D Resize Handles
     */
    fun resizeWindowByHandle(
        windowId: Int,
        corner: ResizeCorner,
        deltaMetersX: Float,
        deltaMetersY: Float
    ): Boolean {
        val window = windows[windowId] ?: return false
        if (window.isFullscreen) return false

        var newW = window.widthMeters
        var newH = window.heightMeters

        when (corner) {
            ResizeCorner.BOTTOM_RIGHT -> {
                newW += deltaMetersX
                newH -= deltaMetersY
            }
            ResizeCorner.BOTTOM_LEFT -> {
                newW -= deltaMetersX
                newH -= deltaMetersY
            }
            ResizeCorner.TOP_RIGHT -> {
                newW += deltaMetersX
                newH += deltaMetersY
            }
            ResizeCorner.TOP_LEFT -> {
                newW -= deltaMetersX
                newH += deltaMetersY
            }
        }

        // Clamp minimum and maximum spatial window dimensions
        window.widthMeters = newW.coerceIn(0.40f, 3.0f)
        window.heightMeters = newH.coerceIn(0.25f, 2.0f)

        // Recompute pixel dimensions for Android VirtualDisplay
        val pixelW = Math.round(window.widthMeters * window.pixelsPerMeter)
        val pixelH = Math.round(window.heightMeters * window.pixelsPerMeter)

        virtualDisplayHost?.resizeDisplaySession(window.displaySessionId, pixelW, pixelH)
        return true
    }

    data class TwoHandResizeSession(
        val windowId: Int,
        val initialDistance: Float,
        val initialWidth: Float,
        val initialHeight: Float
    )

    data class RepositionSession(
        val windowId: Int,
        val grabStartHandPos: Vector3,
        val initialWindowPos: Vector3,
        val initialWidth: Float,
        val initialHeight: Float
    )

    private val twoHandResizeSessions = ConcurrentHashMap<Int, TwoHandResizeSession>()
    private val repositionSessions = ConcurrentHashMap<Int, RepositionSession>()

    /**
     * Iron Man style two-hand diagonal corner-to-corner pinch & stretch/shrink gesture.
     */
    fun startTwoHandDiagonalResize(windowId: Int, initialDistanceMeters: Float): Boolean {
        val window = windows[windowId] ?: return false
        if (window.isFullscreen || initialDistanceMeters <= 0.05f) return false

        twoHandResizeSessions[windowId] = TwoHandResizeSession(
            windowId = windowId,
            initialDistance = initialDistanceMeters,
            initialWidth = window.widthMeters,
            initialHeight = window.heightMeters
        )
        setFocusedWindow(windowId)
        return true
    }

    fun updateTwoHandDiagonalResize(windowId: Int, currentDistanceMeters: Float): Boolean {
        val session = twoHandResizeSessions[windowId] ?: return false
        val window = windows[windowId] ?: return false
        if (session.initialDistance <= 0.001f) return false

        val scale = (currentDistanceMeters / session.initialDistance).coerceIn(0.2f, 4.0f)
        window.widthMeters = (session.initialWidth * scale).coerceIn(0.40f, 3.0f)
        window.heightMeters = (session.initialHeight * scale).coerceIn(0.25f, 2.0f)

        val pixelW = Math.round(window.widthMeters * window.pixelsPerMeter)
        val pixelH = Math.round(window.heightMeters * window.pixelsPerMeter)
        virtualDisplayHost?.resizeDisplaySession(window.displaySessionId, pixelW, pixelH)
        return true
    }

    fun endTwoHandDiagonalResize(windowId: Int) {
        twoHandResizeSessions.remove(windowId)
    }

    /**
     * Top grab bar 3D repositioning.
     * Grabbing the top of the window moves it in 3D space (X, Y, Z).
     * Moving it farther/closer dynamically resizes the window due to perspective.
     */
    fun startTopBarReposition(windowId: Int, handPos: Vector3): Boolean {
        val window = windows[windowId] ?: return false
        if (window.isFullscreen) return false

        repositionSessions[windowId] = RepositionSession(
            windowId = windowId,
            grabStartHandPos = handPos,
            initialWindowPos = window.position,
            initialWidth = window.widthMeters,
            initialHeight = window.heightMeters
        )
        setFocusedWindow(windowId)
        return true
    }

    fun updateTopBarReposition(
        windowId: Int,
        currentHandPos: Vector3,
        enablePerspectiveScaling: Boolean = true
    ): Boolean {
        val session = repositionSessions[windowId] ?: return false
        val window = windows[windowId] ?: return false

        // Compute 3D delta from hand movement
        val deltaX = currentHandPos.x - session.grabStartHandPos.x
        val deltaY = currentHandPos.y - session.grabStartHandPos.y
        val deltaZ = currentHandPos.z - session.grabStartHandPos.z

        // Update 3D position
        val newZ = (session.initialWindowPos.z + deltaZ).coerceIn(-4.0f, -0.4f)
        window.position = Vector3(
            session.initialWindowPos.x + deltaX,
            session.initialWindowPos.y + deltaY,
            newZ
        )

        // Perspective scaling: as window moves farther, scale physical dimensions
        // so apparent readability and angular scale stay proportional
        if (enablePerspectiveScaling) {
            val initialDist = Math.abs(session.initialWindowPos.z).coerceAtLeast(0.4f)
            val currentDist = Math.abs(window.position.z).coerceAtLeast(0.4f)
            val perspectiveRatio = currentDist / initialDist

            window.widthMeters = (session.initialWidth * perspectiveRatio).coerceIn(0.40f, 3.0f)
            window.heightMeters = (session.initialHeight * perspectiveRatio).coerceIn(0.25f, 2.0f)

            val pixelW = Math.round(window.widthMeters * window.pixelsPerMeter)
            val pixelH = Math.round(window.heightMeters * window.pixelsPerMeter)
            virtualDisplayHost?.resizeDisplaySession(window.displaySessionId, pixelW, pixelH)
        }

        return true
    }

    fun endTopBarReposition(windowId: Int) {
        repositionSessions.remove(windowId)
    }

    private fun rotateVector(v: Vector3, q: Quaternion): Vector3 {
        // v' = v + 2 * r x (r x v + w * v)
        val tx = 2f * (q.y * v.z - q.z * v.y)
        val ty = 2f * (q.z * v.x - q.x * v.z)
        val tz = 2f * (q.x * v.y - q.y * v.x)

        return Vector3(
            v.x + q.w * tx + (q.y * tz - q.z * ty),
            v.y + q.w * ty + (q.z * tx - q.x * tz),
            v.z + q.w * tz + (q.x * ty - q.y * tx)
        )
    }
}
