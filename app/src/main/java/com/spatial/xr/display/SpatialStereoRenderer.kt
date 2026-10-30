package com.spatial.xr.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.SurfaceHolder
import com.spatial.xr.bridge.NativeBridge
import com.spatial.xr.window.AppLauncherRepository
import com.spatial.xr.window.SpatialWindowManager
import com.spatial.xr.window.WindowInputInjector
import kotlin.math.cos
import kotlin.math.sin

class SpatialStereoRenderer(
    private val context: Context,
    private val surfaceHolder: SurfaceHolder,
    private val windowManager: SpatialWindowManager,
    private val launcherRepository: AppLauncherRepository
) : Thread("SpatialStereoRenderer") {

    @Volatile
    private var isRunning = true

    @Volatile
    var isStereoSideBySide = true

    @Volatile
    var isDockExpanded = false

    @Volatile
    var pointerX: Float = 0f

    @Volatile
    var pointerY: Float = 0f

    @Volatile
    var isPointerActive: Boolean = false

    @Volatile
    var isPinching: Boolean = false

    @Volatile
    var isHandsTracked: Boolean = false

    data class HandRenderInfo(
        var isTracked: Boolean = false,
        var thumbScreenX: Float = 0.5f,
        var thumbScreenY: Float = 0.5f,
        var indexScreenX: Float = 0.5f,
        var indexScreenY: Float = 0.5f,
        var pinchDistanceMeters: Float = 0.1f,
        var isPinching: Boolean = false,
        var aimScreenX: Float = 0.5f,
        var aimScreenY: Float = 0.5f
    )

    val leftHandInfo = HandRenderInfo()
    val rightHandInfo = HandRenderInfo()
    private val leftDots = FloatArray(8)
    private val rightDots = FloatArray(8)

    private val leftRay = FloatArray(6)
    private val rightRay = FloatArray(6)
    private val leftPinchPos = FloatArray(3)
    private val rightPinchPos = FloatArray(3)

    // Interpolated 60 FPS tracking states
    private val smoothLeftPinchPos = FloatArray(3)
    private val smoothRightPinchPos = FloatArray(3)
    private var wasLeftPinchValid = false
    private var wasRightPinchValid = false
    private var wasLeftTracked = false
    private var wasRightTracked = false

    private fun smoothScreen(current: Float, target: Float, deadband: Float = 0.001f): Float {
        val diff = target - current
        val absDiff = Math.abs(diff)
        if (absDiff < deadband) return current
        if (absDiff > 0.30f) {
            // Jump > 30% screen: snap immediately, never drag across screen
            return target
        }
        val alpha = when {
            absDiff < 0.008f -> 0.30f // small tremor (< 8px): responsive suppression
            absDiff < 0.040f -> 0.55f // fluid interactive motion, low trailing
            absDiff < 0.150f -> 0.82f // fast follow
            else -> 0.98f             // instantaneous snap
        }
        return current + diff * alpha
    }

    private fun smoothMetric(current: Float, target: Float, deadband: Float = 0.002f): Float {
        val diff = target - current
        val absDiff = Math.abs(diff)
        if (absDiff < deadband) return current
        if (absDiff > 0.20f) {
            // Jump > 20cm: snap immediately, never drag across hands
            return target
        }
        val alpha = when {
            absDiff < 0.005f -> 0.30f // < 5mm
            absDiff < 0.035f -> 0.60f // 5mm - 35mm: tight follow
            absDiff < 0.120f -> 0.85f // 35mm - 120mm: immediate response
            else -> 0.98f             // fast arm movement
        }
        return current + diff * alpha
    }

    private var twoHandTransformActive = false
    private var activeTwoHandWindowId: Int = 1
    private var palmLookStartTime = 0L
    private var palmLookCooldownUntil = 0L
    private var wasPinchingLastFrame = false
    private var singleHandRepositionActive = false
    private var singleHandRepositionWindowId: Int = 1

    private var singleHandResizeActive = false
    private var singleHandResizeWindowId: Int = 1
    private var activeResizeCorner: SpatialWindowManager.ResizeCorner? = null
    private var grabStartScreenX = 0f
    private var grabStartScreenY = 0f
    private var grabStartWindowPos = SpatialWindowManager.Vector3(0f, 0f, -1.2f)
    private var grabStartWindowWidth = 0.96f
    private var grabStartWindowHeight = 0.54f

    private var hoveredResizeCorner: SpatialWindowManager.ResizeCorner? = null
    private var isHoveringTopBar: Boolean = false
    private var hoveredWindowId: Int? = null

    private var currentViewportW: Float = 960f
    private var currentViewportH: Float = 1080f

    var dockAutoCloseTimeoutMs: Long = 12000L
    private var dockOpenTimestamp: Long = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private var frameCount = 0L

    fun stopRendering() {
        isRunning = false
        interrupt()
    }

    fun openDock() {
        isDockExpanded = true
        dockOpenTimestamp = System.currentTimeMillis()
        windowManager.setWristDockVisible(true)
    }

    fun closeDock() {
        isDockExpanded = false
        windowManager.setWristDockVisible(false)
    }

    fun toggleDock() {
        if (isDockExpanded) {
            closeDock()
        } else {
            openDock()
        }
    }

    fun resetDockTimeout() {
        if (isDockExpanded) {
            dockOpenTimestamp = System.currentTimeMillis()
        }
    }

    fun setPointer(x: Float, y: Float, active: Boolean, pinching: Boolean = false) {
        pointerX = x
        pointerY = y
        isPointerActive = active
        isPinching = pinching
    }

    override fun run() {
        // Pre-load apps if empty
        if (launcherRepository.getApps().isEmpty()) {
            launcherRepository.loadLaunchableApps()
        }

        // Ensure at least one default holographic window exists
        if (windowManager.getAllWindows().isEmpty()) {
            windowManager.createWindow(
                windowId = 1,
                packageName = "com.spatial.xr.desktop",
                displaySessionId = 101,
                widthMeters = 1.0f,
                heightMeters = 0.6f
            ).apply {
                position = SpatialWindowManager.Vector3(0f, 0.05f, -1.2f)
            }
        }

        while (isRunning) {
            updateHandTracking()

            val canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    surfaceHolder.lockHardwareCanvas()
                } else {
                    surfaceHolder.lockCanvas()
                }
            } catch (e: Exception) {
                null
            }

            if (canvas != null) {
                try {
                    renderSpatialScene(canvas)
                    frameCount++
                } catch (e: Exception) {
                    // Suppress render exceptions during surface reconfiguration
                } finally {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        // Surface might have been destroyed
                    }
                }
            }

            try {
                sleep(16) // ~60 FPS
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun renderSpatialScene(canvas: Canvas) {
        val totalW = canvas.width
        val totalH = canvas.height
        if (totalW <= 0 || totalH <= 0) return

        // Clear background
        canvas.drawColor(Color.parseColor("#080B18"))

        if (isStereoSideBySide) {
            val eyeW = totalW / 2
            val eyeH = totalH
            currentViewportW = eyeW.toFloat()
            currentViewportH = eyeH.toFloat()

            // Left Eye: IPD offset -10px
            canvas.save()
            canvas.clipRect(0, 0, eyeW, eyeH)
            renderEyeView(canvas, 0f, 0f, eyeW.toFloat(), eyeH.toFloat(), ipdOffset = -10f, eyeName = "LEFT EYE")
            canvas.restore()

            // Right Eye: IPD offset +10px
            canvas.save()
            canvas.translate(eyeW.toFloat(), 0f)
            canvas.clipRect(0, 0, eyeW, eyeH)
            renderEyeView(canvas, 0f, 0f, eyeW.toFloat(), eyeH.toFloat(), ipdOffset = 10f, eyeName = "RIGHT EYE")
            canvas.restore()

            // Draw center dividing divider line for SBS calibration
            paint.color = Color.parseColor("#30FFFFFF")
            paint.strokeWidth = 2f
            canvas.drawLine(eyeW.toFloat(), 0f, eyeW.toFloat(), eyeH.toFloat(), paint)
        } else {
            // Monoscopic 2D output
            currentViewportW = totalW.toFloat()
            currentViewportH = totalH.toFloat()
            renderEyeView(canvas, 0f, 0f, totalW.toFloat(), totalH.toFloat(), ipdOffset = 0f, eyeName = "2D OVERVIEW")
        }
    }

    private fun renderEyeView(
        canvas: Canvas,
        ox: Float,
        oy: Float,
        w: Float,
        h: Float,
        ipdOffset: Float,
        eyeName: String
    ) {
        val cx = ox + w * 0.5f + ipdOffset
        val cy = oy + h * 0.5f

        // 1. Draw 3D Perspective Floor Grid
        drawPerspectiveFloor(canvas, cx, cy, w, h)

        // 2. Draw Center Crosshair Alignment Reticle
        drawCenterReticle(canvas, cx, cy)

        // 3. Draw Spatial Windows
        drawSpatialWindows(canvas, cx, cy, w, h, ipdOffset)

        // 4. Draw 3D Curved App Dock or Floating Wrist Anchor
        if (isDockExpanded || windowManager.isWristDockVisible()) {
            drawCurvedAppDock(canvas, cx, cy, w, h)
        } else {
            drawWristDockAnchor(canvas, cx, cy, w, h)
        }

        // 5. Draw Dual-Hand Tracking Dots & Gesture Feedback
        if (isHandsTracked || isPointerActive) {
            drawHandTrackingVisuals(canvas, cx, cy, w, h)
        }

        // 6. Draw HUD Info Header
        drawHudHeader(canvas, ox, oy, w, h, eyeName)
    }

    private fun drawPerspectiveFloor(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.parseColor("#1A00E5FF") // Subtle glowing cyan grid

        val horizonY = cy + h * 0.12f
        val bottomY = h

        // Longitudinal perspective lines vanishing to horizon
        val numLines = 14
        for (i in -numLines..numLines) {
            val bottomX = cx + (i * w / (numLines * 0.9f))
            canvas.drawLine(cx, horizonY, bottomX, bottomY, paint)
        }

        // Transverse horizontal depth lines with logarithmic spacing
        for (i in 1..8) {
            val progress = (i.toFloat() / 8f)
            val y = horizonY + (bottomY - horizonY) * (progress * progress)
            paint.alpha = (progress * 60).toInt()
            canvas.drawLine(0f, y, w, y, paint)
        }

        // Glowing horizon beam
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.parseColor("#4000E5FF")
        canvas.drawLine(0f, horizonY, w, horizonY, paint)
    }

    private fun drawCenterReticle(canvas: Canvas, cx: Float, cy: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.parseColor("#6000E5FF")

        // Outer ring
        canvas.drawCircle(cx, cy, 14f, paint)

        // Cardinal reticle ticks
        canvas.drawLine(cx - 24f, cy, cx - 16f, cy, paint)
        canvas.drawLine(cx + 16f, cy, cx + 24f, cy, paint)
        canvas.drawLine(cx, cy - 24f, cx, cy - 16f, paint)
        canvas.drawLine(cx, cy + 16f, cx, cy + 24f, paint)

        // Center dot
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, 3f, paint)
    }

    private fun drawSpatialWindows(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, ipdOffset: Float) {
        val focal = h * 0.95f

        for (win in windowManager.getAllWindows()) {
            val hw = win.widthMeters * 0.5f
            val hh = win.heightMeters * 0.5f

            // 4 corners in window local coordinates (Z = 0)
            val vTL = SpatialWindowManager.Vector3(-hw, hh, 0f)
            val vTR = SpatialWindowManager.Vector3(hw, hh, 0f)
            val vBR = SpatialWindowManager.Vector3(hw, -hh, 0f)
            val vBL = SpatialWindowManager.Vector3(-hw, -hh, 0f)

            // Rotate each corner vector by window orientation in 3D
            val rTL = windowManager.rotateVector(vTL, win.orientation)
            val rTR = windowManager.rotateVector(vTR, win.orientation)
            val rBR = windowManager.rotateVector(vBR, win.orientation)
            val rBL = windowManager.rotateVector(vBL, win.orientation)

            // True 3D world coordinates of all 4 corners
            val cTL = SpatialWindowManager.Vector3(win.position.x + rTL.x, win.position.y + rTL.y, win.position.z + rTL.z)
            val cTR = SpatialWindowManager.Vector3(win.position.x + rTR.x, win.position.y + rTR.y, win.position.z + rTR.z)
            val cBR = SpatialWindowManager.Vector3(win.position.x + rBR.x, win.position.y + rBR.y, win.position.z + rBR.z)
            val cBL = SpatialWindowManager.Vector3(win.position.x + rBL.x, win.position.y + rBL.y, win.position.z + rBL.z)

            // Perspective project each 3D corner according to its individual depth and stereoscopic parallax
            fun projectCorner(pt: SpatialWindowManager.Vector3): Pair<Float, Float> {
                val zDepth = Math.abs(pt.z).coerceAtLeast(0.35f)
                val parallax = (ipdOffset * 1.2f) / zDepth
                val sx = cx + (pt.x / zDepth) * focal + parallax
                val sy = cy - (pt.y / zDepth) * focal
                return Pair(sx, sy)
            }

            val pTL = projectCorner(cTL)
            val pTR = projectCorner(cTR)
            val pBR = projectCorner(cBR)
            val pBL = projectCorner(cBL)

            val winCx = (pTL.first + pTR.first + pBR.first + pBL.first) * 0.25f
            val winCy = (pTL.second + pTR.second + pBR.second + pBL.second) * 0.25f
            val distZ = Math.abs(win.position.z).coerceAtLeast(0.4f)

            val windowPath = Path().apply {
                moveTo(pTL.first, pTL.second)
                lineTo(pTR.first, pTR.second)
                lineTo(pBR.first, pBR.second)
                lineTo(pBL.first, pBL.second)
                close()
            }

            // Window Glass Body
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#E0151828")
            canvas.drawPath(windowPath, paint)

            val isWinHovered = (win.windowId == hoveredWindowId)
            val isWinActive = (singleHandResizeActive && win.windowId == singleHandResizeWindowId) ||
                    (singleHandRepositionActive && win.windowId == singleHandRepositionWindowId)

            // Top grab bar for 3D repositioning
            val topBarHovered = (isWinHovered && isHoveringTopBar) || (isWinActive && singleHandRepositionActive)
            val topBarPath = Path().apply {
                moveTo(pTL.first + 12f, pTL.second + 4f)
                lineTo(pTR.first - 12f, pTR.second + 4f)
                lineTo(pTR.first - 12f, pTR.second + (if (topBarHovered) 30f else 24f))
                lineTo(pTL.first + 12f, pTL.second + (if (topBarHovered) 30f else 24f))
                close()
            }
            paint.color = if (topBarHovered) Color.parseColor("#FF3949AB") else Color.parseColor("#FF283593")
            canvas.drawPath(topBarPath, paint)

            // Top grab bar center pill handle
            paint.color = if (topBarHovered) Color.parseColor("#FF00FFFF") else Color.parseColor("#FF00E5FF")
            val barMidY = (pTL.second + pTR.second) * 0.5f + 14f
            val pillHalfW = if (topBarHovered) 45f else 30f
            canvas.drawRoundRect(winCx - pillHalfW, barMidY - 4f, winCx + pillHalfW, barMidY + 4f, 4f, 4f, paint)

            // Window Frame
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.parseColor("#FF00E5FF")
            canvas.drawPath(windowPath, paint)

            // Diagonal corner-to-corner stretch guideline (Iron Man gesture)
            paint.strokeWidth = 1.5f
            paint.color = Color.parseColor("#80FF007F")
            paint.pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            canvas.drawLine(pTL.first, pTL.second, pBR.first, pBR.second, paint)
            paint.pathEffect = null

            // 4 Glowing Corner Grab Handles with generous snap rings
            val cornerList = listOf(
                Pair(SpatialWindowManager.ResizeCorner.TOP_LEFT, pTL),
                Pair(SpatialWindowManager.ResizeCorner.TOP_RIGHT, pTR),
                Pair(SpatialWindowManager.ResizeCorner.BOTTOM_LEFT, pBL),
                Pair(SpatialWindowManager.ResizeCorner.BOTTOM_RIGHT, pBR)
            )
            for ((cornerEnum, pt) in cornerList) {
                val (cornerX, cornerY) = pt
                val isHovered = (isWinHovered && hoveredResizeCorner == cornerEnum) ||
                        (isWinActive && singleHandResizeActive && activeResizeCorner == cornerEnum)
                val baseRadius = if (isHovered) 13f else 8f
                val ringRadius = if (isHovered) 18f else 11f

                paint.style = Paint.Style.FILL
                paint.color = if (isHovered) Color.parseColor("#FF00E5FF") else Color.parseColor("#FFFFD700") // Cyan when hovered, Gold otherwise
                canvas.drawCircle(cornerX, cornerY, baseRadius, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = if (isHovered) 3f else 2f
                paint.color = Color.WHITE
                canvas.drawCircle(cornerX, cornerY, ringRadius, paint)

                // Outer proximity tolerance ring
                paint.strokeWidth = 1f
                paint.color = if (isHovered) Color.argb(120, 0, 229, 255) else Color.argb(40, 255, 255, 255)
                canvas.drawCircle(cornerX, cornerY, ringRadius + 10f, paint)
            }

            // Two-hand corner pinch indicators
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#E0FF007F")
            canvas.drawCircle(pTL.first, pTL.second, 14f, paint)
            canvas.drawCircle(pBR.first, pBR.second, 14f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            paint.strokeWidth = 2.5f
            canvas.drawCircle(pTL.first, pTL.second, 14f, paint)
            canvas.drawCircle(pBR.first, pBR.second, 14f, paint)

            // Holographic Window Content
            textPaint.textSize = 24f
            textPaint.color = Color.parseColor("#FF00E5FF")
            canvas.drawText("SPATIAL HOLOGRAM WINDOW [ID #${win.windowId}]", winCx, winCy - 20f, textPaint)

            textPaint.textSize = 18f
            textPaint.color = Color.parseColor("#FFA0B0D0")
            canvas.drawText(
                "Size: ${String.format("%.2f", win.widthMeters)}m × ${String.format("%.2f", win.heightMeters)}m | Depth: ${String.format("%.2f", distZ)}m",
                winCx,
                winCy + 15f,
                textPaint
            )
            textPaint.textSize = 16f
            textPaint.color = Color.parseColor("#80FFFFFF")
            canvas.drawText("Pinch corners with 2 hands to resize & angle in 3D", winCx, winCy + 42f, textPaint)
        }
    }

    private fun drawCurvedAppDock(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val dockItems = launcherRepository.computeCurvedDockLayout(arcRadiusMeters = 0.9f, yOffsetMeters = -0.22f)
        if (dockItems.isEmpty()) return

        val focal = h * 0.95f
        val dockY = cy + h * 0.28f

        // Draw curved backdrop arc pill
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#D0101426")
        val dockW = (w * 0.75f).coerceAtMost(700f)
        val dockRect = RectF(cx - dockW * 0.5f, dockY - 45f, cx + dockW * 0.5f, dockY + 55f)
        canvas.drawRoundRect(dockRect, 30f, 30f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.parseColor("#8000E5FF")
        canvas.drawRoundRect(dockRect, 30f, 30f, paint)

        // Draw auto-close timeout indicator line at bottom of dock pill
        val elapsed = (System.currentTimeMillis() - dockOpenTimestamp).coerceAtLeast(0L)
        val remainingFraction = (1.0f - elapsed.toFloat() / dockAutoCloseTimeoutMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        if (remainingFraction > 0f) {
            paint.strokeWidth = 2.5f
            paint.color = Color.parseColor("#4000E5FF")
            val barY = dockRect.bottom - 4f
            val halfBarW = (dockW * 0.45f) * remainingFraction
            canvas.drawLine(cx - halfBarW, barY, cx + halfBarW, barY, paint)
        }

        // Draw individual dock app icons
        val maxIcons = dockItems.size.coerceAtMost(7)
        val spacing = dockW / (maxIcons + 1)
        val startX = cx - dockW * 0.5f + spacing

        for (i in 0 until maxIcons) {
            val item = dockItems[i]
            val iconX = startX + i * spacing
            val iconY = dockY

            // Icon background circle
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#FF1E2648")
            canvas.drawCircle(iconX, iconY, 24f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.parseColor("#FF00E5FF")
            canvas.drawCircle(iconX, iconY, 24f, paint)

            // App Label initial / icon letter
            textPaint.textSize = 20f
            textPaint.color = Color.WHITE
            val initial = if (item.appItem.label.isNotEmpty()) item.appItem.label.take(1).uppercase() else "A"
            canvas.drawText(initial, iconX, iconY + 7f, textPaint)

            // Short app name below icon
            textPaint.textSize = 12f
            textPaint.color = Color.parseColor("#CCFFFFFF")
            val shortName = if (item.appItem.label.length > 7) item.appItem.label.take(6) + "…" else item.appItem.label
            canvas.drawText(shortName, iconX, iconY + 40f, textPaint)
        }
    }

    private fun drawWristDockAnchor(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val anchorY = cy + h * 0.32f
        val pillW = 260f
        val pillH = 44f
        val rect = RectF(cx - pillW * 0.5f, anchorY - pillH * 0.5f, cx + pillW * 0.5f, anchorY + pillH * 0.5f)

        // Pulsing glow background
        val pulse = (sin(frameCount * 0.1f) * 0.2f + 0.8f).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#C01A237E")
        canvas.drawRoundRect(rect, 22f, 22f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * pulse
        paint.color = Color.parseColor("#FF00E5FF")
        canvas.drawRoundRect(rect, 22f, 22f, paint)

        textPaint.textSize = 16f
        textPaint.color = Color.WHITE
        canvas.drawText("🖐️ LOOK AT PALM FOR APPS", cx, anchorY + 5f, textPaint)
    }

    private fun project3DToScreen(x: Float, y: Float, z: Float): Pair<Float, Float> {
        val absZ = if (Math.abs(z) > 1e-4f) Math.abs(z) else 0.5f
        val fovFactor = 0.55f * absZ
        val nx = (x / fovFactor + 0.5f).coerceIn(0f, 1f)
        val ny = (-y / (fovFactor * 0.75f) + 0.5f).coerceIn(0f, 1f)
        return Pair(nx, ny)
    }

    private fun drawHandTrackingVisuals(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        // 1. Two-Hand 3D Transformation Beam (Iron Man Gesture)
        if (leftHandInfo.isTracked && rightHandInfo.isTracked && leftHandInfo.isPinching && rightHandInfo.isPinching) {
            val lpx = cx + (leftHandInfo.indexScreenX - 0.5f) * w
            val lpy = cy + (leftHandInfo.indexScreenY - 0.5f) * h
            val rpx = cx + (rightHandInfo.indexScreenX - 0.5f) * w
            val rpy = cy + (rightHandInfo.indexScreenY - 0.5f) * h

            // 3D dimension line connecting the two pinch points
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f
            paint.color = Color.parseColor("#FFFF007F")
            canvas.drawLine(lpx, lpy, rpx, rpy, paint)

            paint.strokeWidth = 1.5f
            paint.color = Color.WHITE
            canvas.drawLine(lpx, lpy, rpx, rpy, paint)

            val midX = (lpx + rpx) * 0.5f
            val midY = (lpy + rpy) * 0.5f
            textPaint.textSize = 13f
            textPaint.color = Color.parseColor("#FFFF007F")
            canvas.drawText("⚡ 3D TWO-HAND TRANSFORM", midX, midY - 18f, textPaint)
        }

        // 2. Individual Tracking Dots for Thumb and Forefinger per Hand with L/R Badges
        val hands = listOf(leftHandInfo, rightHandInfo)
        for (hand in hands) {
            if (!hand.isTracked) continue

            val isLeft = (hand === leftHandInfo)
            val handLabel = if (isLeft) "L" else "R"
            val accentColor = if (isLeft) Color.parseColor("#FF00E5FF") else Color.parseColor("#FFFF9100") // Electric Cyan (L) vs Vivid Amber (R)
            val badgeBgColor = Color.parseColor("#D00A0E17")

            val tpx = cx + (hand.thumbScreenX - 0.5f) * w
            val tpy = cy + (hand.thumbScreenY - 0.5f) * h
            val ipx = cx + (hand.indexScreenX - 0.5f) * w
            val ipy = cy + (hand.indexScreenY - 0.5f) * h

            val dist = hand.pinchDistanceMeters

            if (hand.isPinching || dist < 0.020f) {
                // PINCHED: Dots magnetically fuse into a single glowing pinch reticle
                val midPx = (tpx + ipx) * 0.5f
                val midPy = (tpy + ipy) * 0.5f

                // Outer pulsing glow ring
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f
                paint.color = Color.WHITE
                canvas.drawCircle(midPx, midPy, 15f, paint)

                paint.style = Paint.Style.FILL
                paint.color = accentColor
                canvas.drawCircle(midPx, midPy, 9f, paint)

                paint.color = Color.WHITE
                canvas.drawCircle(midPx, midPy, 4f, paint)

                // L / R Badge above fused pinch reticle
                val badgeY = midPy - 26f
                paint.style = Paint.Style.FILL
                paint.color = badgeBgColor
                canvas.drawCircle(midPx, badgeY, 11f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = accentColor
                canvas.drawCircle(midPx, badgeY, 11f, paint)

                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = 12f
                textPaint.isFakeBoldText = true
                textPaint.color = Color.WHITE
                val textOffset = (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(handLabel, midPx, badgeY - textOffset, textPaint)
            } else if (dist < 0.045f) {
                // PROXIMITY APPROACH: Magnetic attraction arc between thumb & forefinger
                val closeness = ((0.045f - dist) / 0.025f).coerceIn(0f, 1f)
                val alpha = (closeness * 220 + 35).toInt()

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f * closeness + 1f
                val r = Color.red(accentColor)
                val g = Color.green(accentColor)
                val b = Color.blue(accentColor)
                paint.color = Color.argb(alpha, r, g, b)
                canvas.drawLine(tpx, tpy, ipx, ipy, paint)

                // Forefinger tip dot
                paint.style = Paint.Style.FILL
                paint.color = accentColor
                canvas.drawCircle(ipx, ipy, 7f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.color = Color.WHITE
                canvas.drawCircle(ipx, ipy, 11f, paint)

                // L / R Badge above forefinger tip dot
                val fBadgeY = ipy - 22f
                paint.style = Paint.Style.FILL
                paint.color = badgeBgColor
                canvas.drawCircle(ipx, fBadgeY, 9.5f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.8f
                paint.color = accentColor
                canvas.drawCircle(ipx, fBadgeY, 9.5f, paint)

                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = 11f
                textPaint.isFakeBoldText = true
                textPaint.color = Color.WHITE
                val fTextOffset = (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(handLabel, ipx, fBadgeY - fTextOffset, textPaint)

                // Thumb tip dot
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                canvas.drawCircle(tpx, tpy, 6f, paint)

                // Small L / R badge above thumb tip
                val tBadgeY = tpy - 16f
                paint.style = Paint.Style.FILL
                paint.color = badgeBgColor
                canvas.drawCircle(tpx, tBadgeY, 7.5f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = Color.argb(180, 255, 255, 255)
                canvas.drawCircle(tpx, tBadgeY, 7.5f, paint)

                textPaint.textSize = 8.5f
                textPaint.isFakeBoldText = true
                textPaint.color = Color.WHITE
                val tTextOffset = (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(handLabel, tpx, tBadgeY - tTextOffset, textPaint)
            } else {
                // OPEN HAND: Distinct tracking dots for thumb and forefinger
                // Forefinger tip dot (Precision aim dot)
                paint.style = Paint.Style.FILL
                paint.color = accentColor
                canvas.drawCircle(ipx, ipy, 6.5f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.color = Color.argb(180, 255, 255, 255)
                canvas.drawCircle(ipx, ipy, 10f, paint)

                // L / R Badge above forefinger tip dot
                val fBadgeY = ipy - 20f
                paint.style = Paint.Style.FILL
                paint.color = badgeBgColor
                canvas.drawCircle(ipx, fBadgeY, 9.5f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.8f
                paint.color = accentColor
                canvas.drawCircle(ipx, fBadgeY, 9.5f, paint)

                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = 11f
                textPaint.isFakeBoldText = true
                textPaint.color = Color.WHITE
                val fTextOffset = (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(handLabel, ipx, fBadgeY - fTextOffset, textPaint)

                // Thumb tip dot (Soft white anchor dot)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(200, 255, 255, 255)
                canvas.drawCircle(tpx, tpy, 5f, paint)

                // Small L / R badge above thumb tip
                val tBadgeY = tpy - 15f
                paint.style = Paint.Style.FILL
                paint.color = badgeBgColor
                canvas.drawCircle(tpx, tBadgeY, 7.5f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = Color.argb(160, 255, 255, 255)
                canvas.drawCircle(tpx, tBadgeY, 7.5f, paint)

                textPaint.textSize = 8.5f
                textPaint.isFakeBoldText = true
                textPaint.color = Color.argb(220, 255, 255, 255)
                val tTextOffset = (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(handLabel, tpx, tBadgeY - tTextOffset, textPaint)
            }

            // Subtle aiming reticle on workspace focal plane
            val apx = cx + (hand.aimScreenX - 0.5f) * w
            val apy = cy + (hand.aimScreenY - 0.5f) * h
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            val r = Color.red(accentColor)
            val g = Color.green(accentColor)
            val b = Color.blue(accentColor)
            paint.color = Color.argb(90, r, g, b)
            canvas.drawCircle(apx, apy, 8f, paint)
        }
    }

    private fun drawHudHeader(canvas: Canvas, ox: Float, oy: Float, w: Float, h: Float, eyeName: String) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 15f
        textPaint.isFakeBoldText = false
        textPaint.color = Color.parseColor("#9000E5FF")
        val lStatus = if (leftHandInfo.isTracked) (if (leftHandInfo.isPinching) "L:🤏" else "L:🖐️") else "L:--"
        val rStatus = if (rightHandInfo.isTracked) (if (rightHandInfo.isPinching) "R:🤏" else "R:🖐️") else "R:--"
        val handInfo = if (isHandsTracked) "🖐️ [$lStatus  $rStatus]" else "🖐️ OPTICAL TRACKING: ON"
        val pinchInfo = if (isPinching) " • 🤏 PINCH" else ""
        canvas.drawText("● SPATIAL XR DESKTOP [$eyeName] • $handInfo$pinchInfo", ox + 24f, oy + 32f, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.parseColor("#70FFFFFF")
        canvas.drawText("VITURE LUMA ULTRA 6DOF • DISPLAY #6", ox + w - 24f, oy + 32f, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun updateHandTracking() {
        if (!NativeBridge.isNativeLoaded()) return

        val dotsMask = NativeBridge.nativeGetHandTrackingDots(leftDots, rightDots)
        val handMask = NativeBridge.nativeGetHandPointers(leftRay, rightRay)
        val pinchMask = NativeBridge.nativeGetPinchState()
        val pinchPosMask = NativeBridge.nativeGetPinchPositions(leftPinchPos, rightPinchPos)
        isHandsTracked = (dotsMask != 0 || handMask != 0)

        val now = System.currentTimeMillis()

        // Auto-close app drawer if open and no item selected after timeout (10-15s)
        if (isDockExpanded && (now - dockOpenTimestamp >= dockAutoCloseTimeoutMs)) {
            closeDock()
        }

        // 1. Debounced Look-at-Palm Gesture (Open/Close Apps Dock)
        // Requires holding palm facing eyes for at least 350ms continuously
        val leftPalm = (handMask and 1 != 0) && NativeBridge.nativeIsLookingAtPalm(true)
        val rightPalm = (handMask and 2 != 0) && NativeBridge.nativeIsLookingAtPalm(false)
        val isLookingAtPalmNow = leftPalm || rightPalm

        if (isLookingAtPalmNow) {
            if (palmLookStartTime == 0L) {
                palmLookStartTime = now
            } else if (now - palmLookStartTime >= 350L && now > palmLookCooldownUntil) {
                toggleDock()
                palmLookCooldownUntil = now + 1200L // 1.2s cooldown
                palmLookStartTime = 0L
            }
        } else {
            palmLookStartTime = 0L
        }

        // Update Left Hand Info
        val isLeftNowTracked = (dotsMask and 1 != 0)
        leftHandInfo.isTracked = isLeftNowTracked
        if (isLeftNowTracked) {
            val tx = leftDots[0]; val ty = leftDots[1]; val tz = leftDots[2]
            val ix = leftDots[3]; val iy = leftDots[4]; val iz = leftDots[5]
            val (rawTnx, rawTny) = project3DToScreen(tx, ty, tz)
            val (rawInx, rawIny) = project3DToScreen(ix, iy, iz)
            val dx = ix - tx; val dy = iy - ty; val dz = iz - tz
            val rawDist = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

            val rawAimX: Float
            val rawAimY: Float
            if (handMask and 1 != 0) {
                val ox = leftRay[0]; val oy = leftRay[1]; val oz = leftRay[2]
                val rdx = leftRay[3]; val rdy = leftRay[4]; val rdz = leftRay[5]
                val t = if (Math.abs(rdz) > 1e-4f) (-1.2f - oz) / rdz else 1.0f
                rawAimX = ((ox + rdx * t) / 0.70f + 0.5f).coerceIn(0f, 1f)
                rawAimY = (-(oy + rdy * t) / 0.50f + 0.5f).coerceIn(0f, 1f)
            } else {
                rawAimX = rawInx
                rawAimY = rawIny
            }

            if (!wasLeftTracked) {
                leftHandInfo.thumbScreenX = rawTnx
                leftHandInfo.thumbScreenY = rawTny
                leftHandInfo.indexScreenX = rawInx
                leftHandInfo.indexScreenY = rawIny
                leftHandInfo.aimScreenX = rawAimX
                leftHandInfo.aimScreenY = rawAimY
                leftHandInfo.pinchDistanceMeters = rawDist
                wasLeftTracked = true
            } else {
                leftHandInfo.thumbScreenX = smoothScreen(leftHandInfo.thumbScreenX, rawTnx)
                leftHandInfo.thumbScreenY = smoothScreen(leftHandInfo.thumbScreenY, rawTny)
                leftHandInfo.indexScreenX = smoothScreen(leftHandInfo.indexScreenX, rawInx)
                leftHandInfo.indexScreenY = smoothScreen(leftHandInfo.indexScreenY, rawIny)
                leftHandInfo.aimScreenX = smoothScreen(leftHandInfo.aimScreenX, rawAimX)
                leftHandInfo.aimScreenY = smoothScreen(leftHandInfo.aimScreenY, rawAimY)
                leftHandInfo.pinchDistanceMeters = leftHandInfo.pinchDistanceMeters * 0.70f + rawDist * 0.30f
            }
            leftHandInfo.isPinching = (leftDots[6] > 0.5f) || (pinchMask and 1 != 0)
        } else {
            leftHandInfo.isPinching = false
            wasLeftTracked = false
        }

        // Update Right Hand Info
        val isRightNowTracked = (dotsMask and 2 != 0)
        rightHandInfo.isTracked = isRightNowTracked
        if (isRightNowTracked) {
            val tx = rightDots[0]; val ty = rightDots[1]; val tz = rightDots[2]
            val ix = rightDots[3]; val iy = rightDots[4]; val iz = rightDots[5]
            val (rawTnx, rawTny) = project3DToScreen(tx, ty, tz)
            val (rawInx, rawIny) = project3DToScreen(ix, iy, iz)
            val dx = ix - tx; val dy = iy - ty; val dz = iz - tz
            val rawDist = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

            val rawAimX: Float
            val rawAimY: Float
            if (handMask and 2 != 0) {
                val ox = rightRay[0]; val oy = rightRay[1]; val oz = rightRay[2]
                val rdx = rightRay[3]; val rdy = rightRay[4]; val rdz = rightRay[5]
                val t = if (Math.abs(rdz) > 1e-4f) (-1.2f - oz) / rdz else 1.0f
                rawAimX = ((ox + rdx * t) / 0.70f + 0.5f).coerceIn(0f, 1f)
                rawAimY = (-(oy + rdy * t) / 0.50f + 0.5f).coerceIn(0f, 1f)
            } else {
                rawAimX = rawInx
                rawAimY = rawIny
            }

            if (!wasRightTracked) {
                rightHandInfo.thumbScreenX = rawTnx
                rightHandInfo.thumbScreenY = rawTny
                rightHandInfo.indexScreenX = rawInx
                rightHandInfo.indexScreenY = rawIny
                rightHandInfo.aimScreenX = rawAimX
                rightHandInfo.aimScreenY = rawAimY
                rightHandInfo.pinchDistanceMeters = rawDist
                wasRightTracked = true
            } else {
                rightHandInfo.thumbScreenX = smoothScreen(rightHandInfo.thumbScreenX, rawTnx)
                rightHandInfo.thumbScreenY = smoothScreen(rightHandInfo.thumbScreenY, rawTny)
                rightHandInfo.indexScreenX = smoothScreen(rightHandInfo.indexScreenX, rawInx)
                rightHandInfo.indexScreenY = smoothScreen(rightHandInfo.indexScreenY, rawIny)
                rightHandInfo.aimScreenX = smoothScreen(rightHandInfo.aimScreenX, rawAimX)
                rightHandInfo.aimScreenY = smoothScreen(rightHandInfo.aimScreenY, rawAimY)
                rightHandInfo.pinchDistanceMeters = rightHandInfo.pinchDistanceMeters * 0.70f + rawDist * 0.30f
            }
            rightHandInfo.isPinching = (rightDots[6] > 0.5f) || (pinchMask and 2 != 0)
        } else {
            rightHandInfo.isPinching = false
            wasRightTracked = false
        }

        // Interpolate 3D Pinch Positions at 60 FPS
        val leftPinchValid = (pinchPosMask and 1 != 0)
        if (leftPinchValid) {
            if (!wasLeftPinchValid) {
                smoothLeftPinchPos[0] = leftPinchPos[0]
                smoothLeftPinchPos[1] = leftPinchPos[1]
                smoothLeftPinchPos[2] = leftPinchPos[2]
                wasLeftPinchValid = true
            } else {
                smoothLeftPinchPos[0] = smoothMetric(smoothLeftPinchPos[0], leftPinchPos[0])
                smoothLeftPinchPos[1] = smoothMetric(smoothLeftPinchPos[1], leftPinchPos[1])
                smoothLeftPinchPos[2] = smoothMetric(smoothLeftPinchPos[2], leftPinchPos[2])
            }
        } else {
            wasLeftPinchValid = false
        }

        val rightPinchValid = (pinchPosMask and 2 != 0)
        if (rightPinchValid) {
            if (!wasRightPinchValid) {
                smoothRightPinchPos[0] = rightPinchPos[0]
                smoothRightPinchPos[1] = rightPinchPos[1]
                smoothRightPinchPos[2] = rightPinchPos[2]
                wasRightPinchValid = true
            } else {
                smoothRightPinchPos[0] = smoothMetric(smoothRightPinchPos[0], rightPinchPos[0])
                smoothRightPinchPos[1] = smoothMetric(smoothRightPinchPos[1], rightPinchPos[1])
                smoothRightPinchPos[2] = smoothMetric(smoothRightPinchPos[2], rightPinchPos[2])
            }
        } else {
            wasRightPinchValid = false
        }

        // 2. Iron Man Two-Hand Diagonal Corner Pinch (Simultaneous Resize, Reposition, Yaw Angle)
        val bothPinching = (leftHandInfo.isTracked && rightHandInfo.isTracked && leftHandInfo.isPinching && rightHandInfo.isPinching) ||
                ((pinchMask and 3) == 3 && wasLeftPinchValid && wasRightPinchValid)

        if (bothPinching) {
            val h1 = SpatialWindowManager.Vector3(smoothLeftPinchPos[0], smoothLeftPinchPos[1], smoothLeftPinchPos[2])
            val h2 = SpatialWindowManager.Vector3(smoothRightPinchPos[0], smoothRightPinchPos[1], smoothRightPinchPos[2])
            if (!twoHandTransformActive) {
                val midX = (h1.x + h2.x) * 0.5f
                val midY = (h1.y + h2.y) * 0.5f
                val midZ = (h1.z + h2.z) * 0.5f
                val targetWin = windowManager.findClosestWindow(midX, midY, midZ) ?: windowManager.getAllWindows().firstOrNull()
                if (targetWin != null) {
                    activeTwoHandWindowId = targetWin.windowId
                    twoHandTransformActive = true
                    windowManager.setFocusedWindow(activeTwoHandWindowId)
                    windowManager.startTwoHandDiagonalTransform(activeTwoHandWindowId, h1, h2)
                    isPinching = true
                }
            } else {
                windowManager.updateTwoHandDiagonalTransform(activeTwoHandWindowId, h1, h2)
                isPinching = true
            }
        } else {
            if (twoHandTransformActive) {
                windowManager.endTwoHandDiagonalTransform(activeTwoHandWindowId)
                twoHandTransformActive = false
            }

            // 3. Single-Hand Pointer Ray & Interactions
            val primaryHand = if (rightHandInfo.isTracked) rightHandInfo else if (leftHandInfo.isTracked) leftHandInfo else null
            if (primaryHand != null) {
                val isCurrentHandPinching = primaryHand.isPinching
                // During pinch hold/manipulation, track direct index finger tip position to eliminate ray angular leverage
                val normX = if (isCurrentHandPinching) primaryHand.indexScreenX else primaryHand.aimScreenX
                val normY = if (isCurrentHandPinching) primaryHand.indexScreenY else primaryHand.aimScreenY

                setPointer(normX, normY, active = true, pinching = isCurrentHandPinching)

                val pinchDown = isCurrentHandPinching && !wasPinchingLastFrame
                val pinchUp = !isCurrentHandPinching && wasPinchingLastFrame

                handlePointerInteractions(normX, normY, pinchDown, isCurrentHandPinching, pinchUp)
                wasPinchingLastFrame = isCurrentHandPinching
            } else {
                wasPinchingLastFrame = false
                isPointerActive = false
                isPinching = false
                hoveredWindowId = null
                hoveredResizeCorner = null
                isHoveringTopBar = false
                if (singleHandRepositionActive) {
                    windowManager.endTopBarReposition(singleHandRepositionWindowId)
                    singleHandRepositionActive = false
                }
                if (singleHandResizeActive) {
                    singleHandResizeActive = false
                    activeResizeCorner = null
                }
            }
        }
    }

    data class WindowHitCandidate(
        val window: SpatialWindowManager.SpatialWindow,
        val localX: Float,
        val localY: Float,
        val worldHitX: Float,
        val worldHitY: Float,
        val worldHitZ: Float,
        val isOverTopBar: Boolean,
        val nearestCorner: SpatialWindowManager.ResizeCorner?,
        val isOverWindowBody: Boolean,
        val handleDistance: Float
    )

    private fun findBestWindowCandidate(screenX: Float, screenY: Float): WindowHitCandidate? {
        val cx = currentViewportW * 0.5f
        val cy = currentViewportH * 0.5f
        val focal = currentViewportH * 0.95f

        val candidates = mutableListOf<WindowHitCandidate>()

        for (win in windowManager.getAllWindows()) {
            val distZ = Math.abs(win.position.z).coerceAtLeast(0.4f)
            val winScreenX = cx + (win.position.x / distZ) * focal
            val winScreenY = cy - (win.position.y / distZ) * focal

            val deltaScreenX = screenX - winScreenX
            val deltaScreenY = screenY - winScreenY

            val localX = (deltaScreenX / focal) * distZ
            val localY = -(deltaScreenY / focal) * distZ
            val worldHitX = win.position.x + localX
            val worldHitY = win.position.y + localY
            val worldHitZ = win.position.z

            val isOverTopBar = WindowInputInjector.isTopGrabBarHitWithSlack(
                localX, localY, win.widthMeters, win.heightMeters,
                barThicknessMeters = 0.14f,
                slackMeters = 0.22f
            )
            val nearestCorner = WindowInputInjector.getNearestResizeCorner(
                localX, localY, win.widthMeters, win.heightMeters,
                toleranceMeters = 0.24f
            ) ?: WindowInputInjector.getNearestEdgeResizeCorner(
                localX, localY, win.widthMeters, win.heightMeters,
                edgeSlackMeters = 0.20f
            )

            val halfW = win.widthMeters * 0.5f
            val halfH = win.heightMeters * 0.5f
            val isOverWindowBody = Math.abs(localX) <= (halfW + 0.05f) && Math.abs(localY) <= (halfH + 0.05f)

            var handleDist = Float.MAX_VALUE
            if (isOverTopBar) {
                handleDist = Math.abs(localY - halfH)
            } else if (nearestCorner != null) {
                val cornerPos = when (nearestCorner) {
                    SpatialWindowManager.ResizeCorner.TOP_RIGHT -> Pair(halfW, halfH)
                    SpatialWindowManager.ResizeCorner.TOP_LEFT -> Pair(-halfW, halfH)
                    SpatialWindowManager.ResizeCorner.BOTTOM_RIGHT -> Pair(halfW, -halfH)
                    SpatialWindowManager.ResizeCorner.BOTTOM_LEFT -> Pair(-halfW, -halfH)
                }
                val dx = localX - cornerPos.first
                val dy = localY - cornerPos.second
                handleDist = kotlin.math.sqrt(dx * dx + dy * dy)
            }

            if (isOverTopBar || nearestCorner != null || isOverWindowBody) {
                candidates.add(
                    WindowHitCandidate(
                        window = win,
                        localX = localX,
                        localY = localY,
                        worldHitX = worldHitX,
                        worldHitY = worldHitY,
                        worldHitZ = worldHitZ,
                        isOverTopBar = isOverTopBar,
                        nearestCorner = nearestCorner,
                        isOverWindowBody = isOverWindowBody,
                        handleDistance = handleDist
                    )
                )
            }
        }

        if (candidates.isEmpty()) return null

        val handleCandidates = candidates.filter { it.isOverTopBar || it.nearestCorner != null }
        if (handleCandidates.isNotEmpty()) {
            return handleCandidates.minWithOrNull(
                compareBy<WindowHitCandidate> { it.handleDistance }
                    .thenByDescending { it.window.position.z }
            )
        }

        return candidates.maxByOrNull { it.window.position.z }
    }

    private fun handlePointerInteractions(
        nx: Float, ny: Float,
        pinchDown: Boolean, isPinching: Boolean, pinchUp: Boolean
    ) {
        val cx = currentViewportW * 0.5f
        val cy = currentViewportH * 0.5f
        val focal = currentViewportH * 0.95f
        val screenX = cx + (nx - 0.5f) * currentViewportW
        val screenY = cy + (ny - 0.5f) * currentViewportH

        // 1. Ongoing single-hand window reposition (smooth tracking from fixed grab anchor)
        if (singleHandRepositionActive) {
            val win = windowManager.getWindow(singleHandRepositionWindowId)
            if (win != null) {
                val distZ = Math.abs(grabStartWindowPos.z).coerceAtLeast(0.4f)
                val deltaScreenX = screenX - grabStartScreenX
                val deltaScreenY = screenY - grabStartScreenY
                val deltaMetersX = (deltaScreenX / focal) * distZ
                val deltaMetersY = -(deltaScreenY / focal) * distZ

                if (isPinching) {
                    val targetX = grabStartWindowPos.x + deltaMetersX
                    val targetY = grabStartWindowPos.y + deltaMetersY
                    win.position = SpatialWindowManager.Vector3(
                        win.position.x * 0.18f + targetX * 0.82f,
                        win.position.y * 0.18f + targetY * 0.82f,
                        grabStartWindowPos.z
                    )
                }
            }
            if (pinchUp) {
                singleHandRepositionActive = false
            }
            return
        }

        // 2. Ongoing single-hand window resize (smooth 1:1 scaling from grab anchor)
        if (singleHandResizeActive) {
            val win = windowManager.getWindow(singleHandResizeWindowId)
            if (win != null) {
                val distZ = Math.abs(win.position.z).coerceAtLeast(0.4f)
                val deltaScreenX = screenX - grabStartScreenX
                val deltaScreenY = screenY - grabStartScreenY
                val deltaMetersX = (deltaScreenX / focal) * distZ
                val deltaMetersY = -(deltaScreenY / focal) * distZ

                val corner = activeResizeCorner ?: SpatialWindowManager.ResizeCorner.BOTTOM_RIGHT
                var newW = grabStartWindowWidth
                var newH = grabStartWindowHeight

                when (corner) {
                    SpatialWindowManager.ResizeCorner.BOTTOM_RIGHT -> {
                        newW += deltaMetersX
                        newH -= deltaMetersY
                    }
                    SpatialWindowManager.ResizeCorner.BOTTOM_LEFT -> {
                        newW -= deltaMetersX
                        newH -= deltaMetersY
                    }
                    SpatialWindowManager.ResizeCorner.TOP_RIGHT -> {
                        newW += deltaMetersX
                        newH += deltaMetersY
                    }
                    SpatialWindowManager.ResizeCorner.TOP_LEFT -> {
                        newW -= deltaMetersX
                        newH += deltaMetersY
                    }
                }
                if (isPinching) {
                    val targetW = newW.coerceIn(0.40f, 3.0f)
                    val targetH = newH.coerceIn(0.25f, 2.0f)
                    win.widthMeters = win.widthMeters * 0.18f + targetW * 0.82f
                    win.heightMeters = win.heightMeters * 0.18f + targetH * 0.82f
                }
            }
            if (pinchUp) {
                singleHandResizeActive = false
                activeResizeCorner = null
            }
            return
        }

        // 3. Find closest window handle or window body near pointer
        val candidate = findBestWindowCandidate(screenX, screenY)

        if (candidate != null && (candidate.isOverTopBar || candidate.nearestCorner != null)) {
            hoveredWindowId = candidate.window.windowId
            isHoveringTopBar = candidate.isOverTopBar
            hoveredResizeCorner = candidate.nearestCorner
        } else {
            hoveredWindowId = null
            isHoveringTopBar = false
            hoveredResizeCorner = null
        }

        if (candidate != null) {
            if (pinchDown) {
                if (candidate.isOverTopBar) {
                    singleHandRepositionActive = true
                    singleHandResizeActive = false
                    singleHandRepositionWindowId = candidate.window.windowId
                    windowManager.setFocusedWindow(candidate.window.windowId)
                    grabStartScreenX = screenX
                    grabStartScreenY = screenY
                    grabStartWindowPos = candidate.window.position
                    return
                } else if (candidate.nearestCorner != null) {
                    singleHandResizeActive = true
                    singleHandRepositionActive = false
                    singleHandResizeWindowId = candidate.window.windowId
                    activeResizeCorner = candidate.nearestCorner
                    grabStartScreenX = screenX
                    grabStartScreenY = screenY
                    grabStartWindowWidth = candidate.window.widthMeters
                    grabStartWindowHeight = candidate.window.heightMeters
                    windowManager.setFocusedWindow(candidate.window.windowId)
                    return
                } else if (candidate.isOverWindowBody) {
                    windowManager.setFocusedWindow(candidate.window.windowId)
                    return
                }
            } else {
                // Hovering over window handles or body: consume event so background dock is never hit!
                return
            }
        }

        // 4. App Dock / Wrist Anchor Interaction (ONLY when pointer is NOT over any window)
        val dockW = (currentViewportW * 0.75f).coerceAtMost(700f)
        val dockY = cy + currentViewportH * 0.28f
        val dockRect = RectF(cx - dockW * 0.5f, dockY - 45f, cx + dockW * 0.5f, dockY + 55f)

        if (isDockExpanded) {
            if (dockRect.contains(screenX, screenY)) {
                resetDockTimeout()
                if (pinchDown) {
                    val dockItems = launcherRepository.computeCurvedDockLayout()
                    if (dockItems.isNotEmpty()) {
                        val maxIcons = dockItems.size.coerceAtMost(7)
                        val iconIdx = ((screenX - dockRect.left) / dockRect.width() * maxIcons).toInt().coerceIn(0, maxIcons - 1)
                        val selected = dockItems.getOrNull(iconIdx)
                        if (selected != null) {
                            val nextId = windowManager.getAllWindows().size + 1
                            val offsetX = if (nextId % 2 == 0) 0.35f else -0.35f
                            windowManager.createWindow(
                                windowId = nextId,
                                packageName = selected.appItem.packageName,
                                displaySessionId = 100 + nextId,
                                widthMeters = 0.90f,
                                heightMeters = 0.55f
                            ).apply {
                                position = SpatialWindowManager.Vector3(offsetX, 0.1f, -1.25f)
                            }
                            closeDock()
                            return
                        }
                    }
                }
            }
        } else {
            // Wrist dock anchor tap
            val anchorY = cy + currentViewportH * 0.32f
            val pillW = 260f
            val pillH = 44f
            val anchorRect = RectF(cx - pillW * 0.5f, anchorY - pillH * 0.5f, cx + pillW * 0.5f, anchorY + pillH * 0.5f)
            if (anchorRect.contains(screenX, screenY) && pinchDown) {
                toggleDock()
                return
            }
        }
    }
}
