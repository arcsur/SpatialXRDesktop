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

    private var twoHandTransformActive = false
    private var activeTwoHandWindowId: Int = 1
    private var palmLookStartTime = 0L
    private var palmLookCooldownUntil = 0L
    private var wasPinchingLastFrame = false
    private var singleHandRepositionActive = false
    private var singleHandRepositionWindowId: Int = 1

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

    fun toggleDock() {
        isDockExpanded = !isDockExpanded
        windowManager.setWristDockVisible(isDockExpanded)
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
            val distZ = Math.abs(win.position.z).coerceAtLeast(0.4f)
            val winCx = cx + (win.position.x / distZ) * focal
            val winCy = cy - (win.position.y / distZ) * focal
            val winW = (win.widthMeters / distZ) * focal
            val winH = (win.heightMeters / distZ) * focal

            // Compute horizontal yaw tilt from orientation quaternion
            val rightVec = windowManager.rotateVector(SpatialWindowManager.Vector3(1f, 0f, 0f), win.orientation)
            val yawTilt = rightVec.z * 35f // Tilts left edge vs right edge

            val leftX = winCx - winW * 0.5f
            val rightX = winCx + winW * 0.5f
            val leftHalfH = winH * 0.5f - yawTilt
            val rightHalfH = winH * 0.5f + yawTilt

            val pTL = Pair(leftX, winCy - leftHalfH)
            val pTR = Pair(rightX, winCy - rightHalfH)
            val pBR = Pair(rightX, winCy + rightHalfH)
            val pBL = Pair(leftX, winCy + leftHalfH)

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

            // Top grab bar for 3D repositioning
            val topBarPath = Path().apply {
                moveTo(pTL.first + 12f, pTL.second + 4f)
                lineTo(pTR.first - 12f, pTR.second + 4f)
                lineTo(pTR.first - 12f, pTR.second + 24f)
                lineTo(pTL.first + 12f, pTL.second + 24f)
                close()
            }
            paint.color = Color.parseColor("#FF283593")
            canvas.drawPath(topBarPath, paint)

            // Top grab bar center pill handle
            paint.color = Color.parseColor("#FF00E5FF")
            val barMidY = (pTL.second + pTR.second) * 0.5f + 14f
            canvas.drawRoundRect(winCx - 30f, barMidY - 3f, winCx + 30f, barMidY + 3f, 3f, 3f, paint)

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

            // 4 Glowing Corner Grab Handles
            val corners = listOf(pTL, pTR, pBL, pBR)
            for ((cornerX, cornerY) in corners) {
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#FFFFD700") // Gold
                canvas.drawCircle(cornerX, cornerY, 8f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = Color.WHITE
                canvas.drawCircle(cornerX, cornerY, 11f, paint)
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

        // 2. Individual Tracking Dots for Thumb and Forefinger per Hand
        val hands = listOf(leftHandInfo, rightHandInfo)
        for (hand in hands) {
            if (!hand.isTracked) continue

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
                paint.color = Color.parseColor("#FFFF007F") // Vivid Magenta
                canvas.drawCircle(midPx, midPy, 9f, paint)

                paint.color = Color.WHITE
                canvas.drawCircle(midPx, midPy, 4f, paint)
            } else if (dist < 0.045f) {
                // PROXIMITY APPROACH: Magnetic attraction arc between thumb & forefinger
                val closeness = ((0.045f - dist) / 0.025f).coerceIn(0f, 1f)
                val alpha = (closeness * 220 + 35).toInt()

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f * closeness + 1f
                paint.color = Color.argb(alpha, 0, 229, 255)
                canvas.drawLine(tpx, tpy, ipx, ipy, paint)

                // Forefinger tip dot
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#FF00E5FF")
                canvas.drawCircle(ipx, ipy, 7f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.color = Color.WHITE
                canvas.drawCircle(ipx, ipy, 11f, paint)

                // Thumb tip dot
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                canvas.drawCircle(tpx, tpy, 6f, paint)
            } else {
                // OPEN HAND: Distinct tracking dots for thumb and forefinger
                // Forefinger tip dot (Cyan precision aim dot)
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#FF00E5FF")
                canvas.drawCircle(ipx, ipy, 6.5f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.color = Color.argb(180, 255, 255, 255)
                canvas.drawCircle(ipx, ipy, 10f, paint)

                // Thumb tip dot (Soft white anchor dot)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(200, 255, 255, 255)
                canvas.drawCircle(tpx, tpy, 5f, paint)
            }

            // Subtle aiming reticle on workspace focal plane
            val apx = cx + (hand.aimScreenX - 0.5f) * w
            val apy = cy + (hand.aimScreenY - 0.5f) * h
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = Color.argb(90, 0, 229, 255)
            canvas.drawCircle(apx, apy, 8f, paint)
        }
    }

    private fun drawHudHeader(canvas: Canvas, ox: Float, oy: Float, w: Float, h: Float, eyeName: String) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 15f
        textPaint.color = Color.parseColor("#9000E5FF")
        val handInfo = if (isHandsTracked) "🖐️ HANDS: ACTIVE" else "🖐️ OPTICAL TRACKING: ON"
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
        leftHandInfo.isTracked = (dotsMask and 1 != 0)
        if (leftHandInfo.isTracked) {
            val tx = leftDots[0]; val ty = leftDots[1]; val tz = leftDots[2]
            val ix = leftDots[3]; val iy = leftDots[4]; val iz = leftDots[5]
            val (tnx, tny) = project3DToScreen(tx, ty, tz)
            val (inx, iny) = project3DToScreen(ix, iy, iz)
            leftHandInfo.thumbScreenX = tnx
            leftHandInfo.thumbScreenY = tny
            leftHandInfo.indexScreenX = inx
            leftHandInfo.indexScreenY = iny
            val dx = ix - tx; val dy = iy - ty; val dz = iz - tz
            leftHandInfo.pinchDistanceMeters = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            leftHandInfo.isPinching = (leftDots[6] > 0.5f) || (pinchMask and 1 != 0)

            if (handMask and 1 != 0) {
                val ox = leftRay[0]; val oy = leftRay[1]; val oz = leftRay[2]
                val rdx = leftRay[3]; val rdy = leftRay[4]; val rdz = leftRay[5]
                val t = if (Math.abs(rdz) > 1e-4f) (-1.2f - oz) / rdz else 1.0f
                leftHandInfo.aimScreenX = ((ox + rdx * t) / 0.70f + 0.5f).coerceIn(0f, 1f)
                leftHandInfo.aimScreenY = (-(oy + rdy * t) / 0.50f + 0.5f).coerceIn(0f, 1f)
            } else {
                leftHandInfo.aimScreenX = inx
                leftHandInfo.aimScreenY = iny
            }
        } else {
            leftHandInfo.isPinching = false
        }

        // Update Right Hand Info
        rightHandInfo.isTracked = (dotsMask and 2 != 0)
        if (rightHandInfo.isTracked) {
            val tx = rightDots[0]; val ty = rightDots[1]; val tz = rightDots[2]
            val ix = rightDots[3]; val iy = rightDots[4]; val iz = rightDots[5]
            val (tnx, tny) = project3DToScreen(tx, ty, tz)
            val (inx, iny) = project3DToScreen(ix, iy, iz)
            rightHandInfo.thumbScreenX = tnx
            rightHandInfo.thumbScreenY = tny
            rightHandInfo.indexScreenX = inx
            rightHandInfo.indexScreenY = iny
            val dx = ix - tx; val dy = iy - ty; val dz = iz - tz
            rightHandInfo.pinchDistanceMeters = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            rightHandInfo.isPinching = (rightDots[6] > 0.5f) || (pinchMask and 2 != 0)

            if (handMask and 2 != 0) {
                val ox = rightRay[0]; val oy = rightRay[1]; val oz = rightRay[2]
                val rdx = rightRay[3]; val rdy = rightRay[4]; val rdz = rightRay[5]
                val t = if (Math.abs(rdz) > 1e-4f) (-1.2f - oz) / rdz else 1.0f
                rightHandInfo.aimScreenX = ((ox + rdx * t) / 0.70f + 0.5f).coerceIn(0f, 1f)
                rightHandInfo.aimScreenY = (-(oy + rdy * t) / 0.50f + 0.5f).coerceIn(0f, 1f)
            } else {
                rightHandInfo.aimScreenX = inx
                rightHandInfo.aimScreenY = iny
            }
        } else {
            rightHandInfo.isPinching = false
        }

        // 2. Iron Man Two-Hand Diagonal Corner Pinch (Simultaneous Resize, Reposition, Yaw Angle)
        val bothPinching = (leftHandInfo.isTracked && rightHandInfo.isTracked && leftHandInfo.isPinching && rightHandInfo.isPinching) ||
                ((pinchMask and 3) == 3 && (pinchPosMask and 3) == 3)

        if (bothPinching) {
            val h1 = SpatialWindowManager.Vector3(leftPinchPos[0], leftPinchPos[1], leftPinchPos[2])
            val h2 = SpatialWindowManager.Vector3(rightPinchPos[0], rightPinchPos[1], rightPinchPos[2])
            val targetWin = windowManager.getAllWindows().firstOrNull()
            if (targetWin != null) {
                activeTwoHandWindowId = targetWin.windowId
                if (!twoHandTransformActive) {
                    twoHandTransformActive = true
                    windowManager.startTwoHandDiagonalTransform(activeTwoHandWindowId, h1, h2)
                } else {
                    windowManager.updateTwoHandDiagonalTransform(activeTwoHandWindowId, h1, h2)
                }
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
                val normX = primaryHand.aimScreenX
                val normY = primaryHand.aimScreenY
                val hitX = (normX - 0.5f) * 0.70f
                val hitY = (0.5f - normY) * 0.50f
                val targetZ = -1.2f

                setPointer(normX, normY, active = true, pinching = isCurrentHandPinching)

                val pinchDown = isCurrentHandPinching && !wasPinchingLastFrame
                val pinchUp = !isCurrentHandPinching && wasPinchingLastFrame

                handlePointerInteractions(normX, normY, pinchDown, isCurrentHandPinching, pinchUp, hitX, hitY, targetZ)
                wasPinchingLastFrame = isCurrentHandPinching
            } else {
                wasPinchingLastFrame = false
                isPointerActive = false
                isPinching = false
            }
        }
    }

    private fun handlePointerInteractions(
        nx: Float, ny: Float,
        pinchDown: Boolean, isPinching: Boolean, pinchUp: Boolean,
        hitX: Float, hitY: Float, hitZ: Float
    ) {
        // 1. App Dock Selection
        if (isDockExpanded) {
            val dockItems = launcherRepository.computeCurvedDockLayout()
            if (ny > 0.65f && pinchDown && dockItems.isNotEmpty()) {
                val maxIcons = dockItems.size.coerceAtMost(7)
                val iconIdx = ((nx - 0.20f) / 0.60f * maxIcons).toInt().coerceIn(0, maxIcons - 1)
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
                }
            }
        } else {
            // Wrist dock anchor tap
            if (ny > 0.72f && pinchDown) {
                toggleDock()
            }
        }

        // 2. Window Top Grab Bar Repositioning (Perspective Scaled)
        val win = windowManager.getAllWindows().firstOrNull() ?: return
        val winDx = hitX - win.position.x
        val winDy = hitY - win.position.y

        val isOverTopBar = Math.abs(winDx) < (win.widthMeters * 0.55f) &&
                Math.abs(winDy - (win.heightMeters * 0.45f)) < 0.18f

        if (pinchDown && isOverTopBar) {
            singleHandRepositionActive = true
            singleHandRepositionWindowId = win.windowId
            val handPos = SpatialWindowManager.Vector3(hitX, hitY, hitZ)
            windowManager.startTopBarReposition(win.windowId, handPos)
        } else if (isPinching && singleHandRepositionActive) {
            val currentHandPos = SpatialWindowManager.Vector3(hitX, hitY, hitZ)
            windowManager.updateTopBarReposition(singleHandRepositionWindowId, currentHandPos)
        } else if (pinchUp && singleHandRepositionActive) {
            windowManager.endTopBarReposition(singleHandRepositionWindowId)
            singleHandRepositionActive = false
        }
    }
}
