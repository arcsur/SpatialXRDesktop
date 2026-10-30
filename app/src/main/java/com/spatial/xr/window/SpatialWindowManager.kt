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
        operator fun plus(other: Vector3): Vector3 = Vector3(x + other.x, y + other.y, z + other.z)
        operator fun minus(other: Vector3): Vector3 = Vector3(x - other.x, y - other.y, z - other.z)
    }

    data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {
        fun multiply(other: Quaternion): Quaternion {
            return Quaternion(
                w = w * other.w - x * other.x - y * other.y - z * other.z,
                x = w * other.x + x * other.w + y * other.z - z * other.y,
                y = w * other.y - x * other.z + y * other.w + z * other.x,
                z = w * other.z + x * other.y - y * other.x + z * other.w
            )
        }

        fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

        companion object {
            val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

            fun fromAxisAngle(axis: Vector3, angleRadians: Float): Quaternion {
                val norm = axis.normalized()
                val halfAngle = angleRadians * 0.5f
                val s = sin(halfAngle)
                return Quaternion(
                    w = cos(halfAngle),
                    x = norm.x * s,
                    y = norm.y * s,
                    z = norm.z * s
                )
            }

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
    private var wristDockOpenTimestamp = 0L
    var wristDockAutoCloseTimeoutMs = 12000L

    fun isWristDockVisible(): Boolean = isWristDockVisible
    fun setWristDockVisible(visible: Boolean, currentTime: Long = System.currentTimeMillis()) {
        isWristDockVisible = visible
        if (visible) {
            wristDockOpenTimestamp = currentTime
        }
    }

    fun checkWristDockTimeout(currentTime: Long = System.currentTimeMillis()): Boolean {
        if (isWristDockVisible && (currentTime - wristDockOpenTimestamp >= wristDockAutoCloseTimeoutMs)) {
            isWristDockVisible = false
            return true
        }
        return false
    }

    fun resetWristDockTimeout(currentTime: Long = System.currentTimeMillis()) {
        if (isWristDockVisible) {
            wristDockOpenTimestamp = currentTime
        }
    }

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

    fun findClosestWindow(x: Float, y: Float, z: Float): SpatialWindow? {
        val allWindows = windows.values
        if (allWindows.isEmpty()) return null

        var closestWin: SpatialWindow? = null
        var minDistanceSq = Float.MAX_VALUE

        for (win in allWindows) {
            val dx = x - win.position.x
            val dy = y - win.position.y
            val dz = z - win.position.z
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq < minDistanceSq) {
                minDistanceSq = distSq
                closestWin = win
            }
        }
        return closestWin
    }

    fun setFocusedWindow(windowId: Int) {
        if (windows.containsKey(windowId)) {
            focusedWindowId = windowId
            mruStack.remove(windowId)
            mruStack.add(0, windowId)
        }
    }

    fun removeWindow(windowId: Int) {
        val win = windows.remove(windowId)
        if (win != null) {
            virtualDisplayHost?.releaseDisplaySession(win.displaySessionId)
        }
        repositionSessions.remove(windowId)
        twoHandResizeSessions.remove(windowId)
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

    enum class TwoHandGestureType {
        CORNER_RESIZE,
        HORIZONTAL_ROTATION,
        VERTICAL_TILT
    }

    data class TwoHandResizeSession(
        val windowId: Int,
        val initialDistance: Float,
        val initialWidth: Float,
        val initialHeight: Float,
        val initialHand1Pos: Vector3 = Vector3(0f, 0f, 0f),
        val initialHand2Pos: Vector3 = Vector3(0f, 0f, 0f),
        val isHand1Left: Boolean = true,
        val initialMidpoint: Vector3 = Vector3(0f, 0f, 0f),
        val initialYawAngle: Float = 0f,
        val initialWindowPos: Vector3 = Vector3(0f, 0f, -1.2f),
        val initialOrientation: Quaternion = Quaternion.IDENTITY,
        val has3DTransform: Boolean = false,
        var lastAppliedDeltaYaw: Float = 0f,
        var lastAppliedScale: Float = 1.0f,
        val gestureType: TwoHandGestureType = TwoHandGestureType.CORNER_RESIZE,
        var lastAppliedAngle: Float = 0f
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
     * Two-hand diagonal corner-to-corner pinch stretch gesture.
     * Exclusively performs window resizing (width & height), strictly locking window orientation and position.
     */
    fun startTwoHandDiagonalTransform(
        windowId: Int,
        hand1Pos: Vector3,
        hand2Pos: Vector3
    ): Boolean {
        val window = windows[windowId] ?: return false
        if (window.isFullscreen) return false

        val dx = hand2Pos.x - hand1Pos.x
        val dy = hand2Pos.y - hand1Pos.y
        val dz = hand2Pos.z - hand1Pos.z
        val initialDistance = sqrt(dx * dx + dy * dy + dz * dz)
        if (initialDistance <= 0.05f) return false

        val isHand1Left = hand1Pos.x <= hand2Pos.x
        val leftHand = if (isHand1Left) hand1Pos else hand2Pos
        val rightHand = if (isHand1Left) hand2Pos else hand1Pos

        val initialMidpoint = Vector3(
            (hand1Pos.x + hand2Pos.x) * 0.5f,
            (hand1Pos.y + hand2Pos.y) * 0.5f,
            (hand1Pos.z + hand2Pos.z) * 0.5f
        )
        val initialYawAngle = kotlin.math.atan2(
            rightHand.z - leftHand.z,
            rightHand.x - leftHand.x
        )

        twoHandResizeSessions[windowId] = TwoHandResizeSession(
            windowId = windowId,
            initialDistance = initialDistance,
            initialWidth = window.widthMeters,
            initialHeight = window.heightMeters,
            initialHand1Pos = hand1Pos,
            initialHand2Pos = hand2Pos,
            isHand1Left = isHand1Left,
            initialMidpoint = initialMidpoint,
            initialYawAngle = initialYawAngle,
            initialWindowPos = window.position,
            initialOrientation = window.orientation,
            has3DTransform = true,
            lastAppliedDeltaYaw = 0f,
            lastAppliedScale = 1.0f,
            gestureType = TwoHandGestureType.CORNER_RESIZE,
            lastAppliedAngle = 0f
        )
        setFocusedWindow(windowId)
        return true
    }

    fun updateTwoHandDiagonalTransform(
        windowId: Int,
        hand1Pos: Vector3,
        hand2Pos: Vector3
    ): Boolean {
        val session = twoHandResizeSessions[windowId] ?: return false
        val window = windows[windowId] ?: return false
        if (session.initialDistance <= 0.001f) return false

        val dx = hand2Pos.x - hand1Pos.x
        val dy = hand2Pos.y - hand1Pos.y
        val dz = hand2Pos.z - hand1Pos.z
        val currentDistance = sqrt(dx * dx + dy * dy + dz * dz)

        // 1. Resize (scale width & height proportionally) with deadband to ignore tremor
        val rawScale = (currentDistance / session.initialDistance).coerceIn(0.2f, 4.0f)
        val scaleDiff = kotlin.math.abs(rawScale - session.lastAppliedScale)
        val scale = if (scaleDiff < 0.015f) {
            session.lastAppliedScale
        } else {
            rawScale.also { session.lastAppliedScale = it }
        }
        window.widthMeters = (session.initialWidth * scale).coerceIn(0.40f, 3.0f)
        window.heightMeters = (session.initialHeight * scale).coerceIn(0.25f, 2.0f)

        // Orientation and position are strictly locked during corner pinch resize (no rotation or tilt)
        window.orientation = session.initialOrientation
        window.position = session.initialWindowPos

        val pixelW = Math.round(window.widthMeters * window.pixelsPerMeter)
        val pixelH = Math.round(window.heightMeters * window.pixelsPerMeter)
        val currentSession = virtualDisplayHost?.getSession(window.displaySessionId)
        if (currentSession == null || Math.abs(currentSession.width - pixelW) >= 16 || Math.abs(currentSession.height - pixelH) >= 16) {
            virtualDisplayHost?.resizeDisplaySession(window.displaySessionId, pixelW, pixelH)
        }

        return true
    }

    fun endTwoHandDiagonalTransform(windowId: Int) {
        val window = windows[windowId]
        if (window != null) {
            val pixelW = Math.round(window.widthMeters * window.pixelsPerMeter)
            val pixelH = Math.round(window.heightMeters * window.pixelsPerMeter)
            virtualDisplayHost?.resizeDisplaySession(window.displaySessionId, pixelW, pixelH)
        }
        twoHandResizeSessions.remove(windowId)
    }

    /**
     * Side edge pinch grab gesture for horizontal rotation (yaw) or vertical tilt (pitch).
     * Hand depth difference controls angle (max ±60 degrees); hand depth position controls window Z position.
     */
    fun startTwoHandEdgeRotation(
        windowId: Int,
        edgeAxis: TwoHandGestureType,
        hand1Pos: Vector3,
        hand2Pos: Vector3
    ): Boolean {
        val window = windows[windowId] ?: return false
        if (window.isFullscreen) return false

        val dx = hand2Pos.x - hand1Pos.x
        val dy = hand2Pos.y - hand1Pos.y
        val dz = hand2Pos.z - hand1Pos.z
        val initialDistance = sqrt(dx * dx + dy * dy + dz * dz)

        twoHandResizeSessions[windowId] = TwoHandResizeSession(
            windowId = windowId,
            initialDistance = initialDistance,
            initialWidth = window.widthMeters,
            initialHeight = window.heightMeters,
            initialHand1Pos = hand1Pos,
            initialHand2Pos = hand2Pos,
            initialWindowPos = window.position,
            initialOrientation = window.orientation,
            gestureType = edgeAxis,
            lastAppliedAngle = 0f,
            lastAppliedScale = 1.0f
        )
        setFocusedWindow(windowId)
        return true
    }

    fun updateTwoHandEdgeRotation(
        windowId: Int,
        hand1Pos: Vector3,
        hand2Pos: Vector3
    ): Boolean {
        val session = twoHandResizeSessions[windowId] ?: return false
        val window = windows[windowId] ?: return false

        val maxAngleRad = (Math.PI / 3.0).toFloat() // ±60 degrees max from flat window

        when (session.gestureType) {
            TwoHandGestureType.HORIZONTAL_ROTATION -> {
                // Left and right edges: horizontal rotation & projection into Z axis
                val leftHand = if (hand1Pos.x <= hand2Pos.x) hand1Pos else hand2Pos
                val rightHand = if (hand1Pos.x <= hand2Pos.x) hand2Pos else hand1Pos
                val dx = (rightHand.x - leftHand.x).coerceAtLeast(0.05f)
                val dz = rightHand.z - leftHand.z

                // When right hand is deeper in Z (dz < 0), right side rotates back into Z
                val rawYaw = kotlin.math.atan2(-dz, dx).coerceIn(-maxAngleRad, maxAngleRad)
                val angleDiff = kotlin.math.abs(rawYaw - session.lastAppliedAngle)
                val deltaYaw = if (angleDiff < 0.026f) {
                    session.lastAppliedAngle
                } else {
                    rawYaw.also { session.lastAppliedAngle = it }
                }

                val rotQ = Quaternion.fromAxisAngle(Vector3(0f, 1f, 0f), deltaYaw)
                window.orientation = rotQ.multiply(session.initialOrientation)

                // Depth position of hands determines the Z positioning of the rotation
                val midZ = ((leftHand.z + rightHand.z) * 0.5f).coerceIn(-4.0f, -0.4f)
                window.position = Vector3(window.position.x, window.position.y, midZ)
            }
            TwoHandGestureType.VERTICAL_TILT -> {
                // Top and bottom edges: vertical tilt & projection into Z axis
                val topHand = if (hand1Pos.y >= hand2Pos.y) hand1Pos else hand2Pos
                val bottomHand = if (hand1Pos.y >= hand2Pos.y) hand2Pos else hand1Pos
                val dy = (topHand.y - bottomHand.y).coerceAtLeast(0.05f)
                val dz = topHand.z - bottomHand.z

                // When top hand is deeper in Z (dz < 0), top side rotates back into Z
                val rawPitch = kotlin.math.atan2(dz, dy).coerceIn(-maxAngleRad, maxAngleRad)
                val angleDiff = kotlin.math.abs(rawPitch - session.lastAppliedAngle)
                val deltaPitch = if (angleDiff < 0.026f) {
                    session.lastAppliedAngle
                } else {
                    rawPitch.also { session.lastAppliedAngle = it }
                }

                val rotQ = Quaternion.fromAxisAngle(Vector3(1f, 0f, 0f), deltaPitch)
                window.orientation = rotQ.multiply(session.initialOrientation)

                // Depth position of hands determines the Z positioning of the tilt
                val midZ = ((topHand.z + bottomHand.z) * 0.5f).coerceIn(-4.0f, -0.4f)
                window.position = Vector3(window.position.x, window.position.y, midZ)
            }
            TwoHandGestureType.CORNER_RESIZE -> {
                return updateTwoHandDiagonalTransform(windowId, hand1Pos, hand2Pos)
            }
        }
        return true
    }

    fun endTwoHandEdgeRotation(windowId: Int) {
        endTwoHandDiagonalTransform(windowId)
    }

    fun toLocalPoint(worldPoint: Vector3, window: SpatialWindow): Vector3 {
        val delta = Vector3(
            worldPoint.x - window.position.x,
            worldPoint.y - window.position.y,
            worldPoint.z - window.position.z
        )
        return rotateVector(delta, window.orientation.conjugate())
    }

    fun classifyTwoHandTouchpoints(
        window: SpatialWindow,
        hand1Pos: Vector3,
        hand2Pos: Vector3
    ): TwoHandGestureType {
        val p1 = toLocalPoint(hand1Pos, window)
        val p2 = toLocalPoint(hand2Pos, window)

        val corner1 = WindowInputInjector.getNearestResizeCorner(
            p1.x, p1.y, window.widthMeters, window.heightMeters, toleranceMeters = 0.22f
        )
        val corner2 = WindowInputInjector.getNearestResizeCorner(
            p2.x, p2.y, window.widthMeters, window.heightMeters, toleranceMeters = 0.22f
        )

        if (corner1 != null && corner2 != null) {
            return TwoHandGestureType.CORNER_RESIZE
        }

        val edge1 = WindowInputInjector.getSideEdgeGrab(
            p1.x, p1.y, window.widthMeters, window.heightMeters
        )
        val edge2 = WindowInputInjector.getSideEdgeGrab(
            p2.x, p2.y, window.widthMeters, window.heightMeters
        )

        if ((edge1 == WindowInputInjector.WindowEdge.LEFT && edge2 == WindowInputInjector.WindowEdge.RIGHT) ||
            (edge1 == WindowInputInjector.WindowEdge.RIGHT && edge2 == WindowInputInjector.WindowEdge.LEFT)) {
            return TwoHandGestureType.HORIZONTAL_ROTATION
        }

        if ((edge1 == WindowInputInjector.WindowEdge.TOP && edge2 == WindowInputInjector.WindowEdge.BOTTOM) ||
            (edge1 == WindowInputInjector.WindowEdge.BOTTOM && edge2 == WindowInputInjector.WindowEdge.TOP)) {
            return TwoHandGestureType.VERTICAL_TILT
        }

        // Fallback: differentiate horizontal vs vertical vs diagonal by coordinate span
        val dx = kotlin.math.abs(p1.x - p2.x)
        val dy = kotlin.math.abs(p1.y - p2.y)
        return when {
            dx > 1.35f * dy -> TwoHandGestureType.HORIZONTAL_ROTATION
            dy > 1.35f * dx -> TwoHandGestureType.VERTICAL_TILT
            else -> TwoHandGestureType.CORNER_RESIZE
        }
    }

    fun startTwoHandGesture(
        windowId: Int,
        hand1Pos: Vector3,
        hand2Pos: Vector3
    ): TwoHandGestureType? {
        val window = windows[windowId] ?: return null
        if (window.isFullscreen) return null

        val gestureType = classifyTwoHandTouchpoints(window, hand1Pos, hand2Pos)
        when (gestureType) {
            TwoHandGestureType.CORNER_RESIZE -> startTwoHandDiagonalTransform(windowId, hand1Pos, hand2Pos)
            TwoHandGestureType.HORIZONTAL_ROTATION,
            TwoHandGestureType.VERTICAL_TILT -> startTwoHandEdgeRotation(windowId, gestureType, hand1Pos, hand2Pos)
        }
        return gestureType
    }

    fun updateTwoHandGesture(
        windowId: Int,
        hand1Pos: Vector3,
        hand2Pos: Vector3
    ): Boolean {
        val session = twoHandResizeSessions[windowId] ?: return false
        return when (session.gestureType) {
            TwoHandGestureType.CORNER_RESIZE -> updateTwoHandDiagonalTransform(windowId, hand1Pos, hand2Pos)
            TwoHandGestureType.HORIZONTAL_ROTATION,
            TwoHandGestureType.VERTICAL_TILT -> updateTwoHandEdgeRotation(windowId, hand1Pos, hand2Pos)
        }
    }

    fun endTwoHandGesture(windowId: Int) {
        endTwoHandDiagonalTransform(windowId)
    }

    fun getActiveTwoHandSession(windowId: Int): TwoHandResizeSession? = twoHandResizeSessions[windowId]

    // Overloads for convenience / backward compatibility
    fun startTwoHandDiagonalResize(
        windowId: Int,
        hand1Pos: Vector3,
        hand2Pos: Vector3
    ): Boolean = startTwoHandDiagonalTransform(windowId, hand1Pos, hand2Pos)

    fun updateTwoHandDiagonalResize(
        windowId: Int,
        hand1Pos: Vector3,
        hand2Pos: Vector3
    ): Boolean = updateTwoHandDiagonalTransform(windowId, hand1Pos, hand2Pos)

    /**
     * Legacy distance-only resize overload.
     */
    fun startTwoHandDiagonalResize(windowId: Int, initialDistanceMeters: Float): Boolean {
        val window = windows[windowId] ?: return false
        if (window.isFullscreen || initialDistanceMeters <= 0.05f) return false

        twoHandResizeSessions[windowId] = TwoHandResizeSession(
            windowId = windowId,
            initialDistance = initialDistanceMeters,
            initialWidth = window.widthMeters,
            initialHeight = window.heightMeters,
            has3DTransform = false
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
        endTwoHandDiagonalTransform(windowId)
    }

    /**
     * Window fist grab 3D repositioning.
     * Grabbing a window by closing a fist moves it in 3D space (X, Y, Z) without rotation.
     * Releasing the fist sets the final window position.
     */
    fun startWindowGrabReposition(windowId: Int, handPos: Vector3): Boolean {
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

    fun updateWindowGrabReposition(
        windowId: Int,
        currentHandPos: Vector3,
        enablePerspectiveScaling: Boolean = true
    ): Boolean {
        val session = repositionSessions[windowId] ?: return false
        val window = windows[windowId] ?: return false

        // Compute 3D delta from hand movement (X, Y, Z) - window rotation is strictly locked
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

        // Perspective scaling: as window moves farther/closer, scale physical dimensions
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

    fun endWindowGrabReposition(windowId: Int) {
        repositionSessions.remove(windowId)
    }

    // Backward-compatible delegates for top bar repositioning API
    fun startTopBarReposition(windowId: Int, handPos: Vector3): Boolean = startWindowGrabReposition(windowId, handPos)
    fun updateTopBarReposition(windowId: Int, currentHandPos: Vector3, enablePerspectiveScaling: Boolean = true): Boolean =
        updateWindowGrabReposition(windowId, currentHandPos, enablePerspectiveScaling)
    fun endTopBarReposition(windowId: Int) = endWindowGrabReposition(windowId)

    fun rotateVector(v: Vector3, q: Quaternion): Vector3 {
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
