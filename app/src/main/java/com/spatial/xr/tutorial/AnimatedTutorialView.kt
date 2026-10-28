package com.spatial.xr.tutorial

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

class AnimatedTutorialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentTopic: TutorialManager.TutorialTopic = TutorialManager.TutorialTopic.POINTER_PINCH
    private var animationProgress: Float = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            animationProgress = it.animatedValue as Float
            invalidate()
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    // Cyberpunk/spatial color palette
    private val colorCyan = Color.parseColor("#00E5FF")
    private val colorMagenta = Color.parseColor("#FF007F")
    private val colorBlue = Color.parseColor("#2979FF")
    private val colorGold = Color.parseColor("#FFD700")
    private val colorDarkBg = Color.parseColor("#12121E")

    init {
        animator.start()
    }

    fun setTopic(topic: TutorialManager.TutorialTopic) {
        currentTopic = topic
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = w * 0.5f
        val cy = h * 0.5f

        // Draw ambient holographic backdrop card
        paint.style = Paint.Style.FILL
        paint.color = colorDarkBg
        val cardRect = RectF(w * 0.05f, h * 0.05f, w * 0.95f, h * 0.95f)
        canvas.drawRoundRect(cardRect, 32f, 32f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = colorBlue
        canvas.drawRoundRect(cardRect, 32f, 32f, paint)

        when (currentTopic) {
            TutorialManager.TutorialTopic.POINTER_PINCH -> drawPointerPinchAnimation(canvas, cx, cy)
            TutorialManager.TutorialTopic.RESIZE_MOVE -> drawResizeMoveAnimation(canvas, cx, cy)
            TutorialManager.TutorialTopic.PALM_DOCK -> drawPalmDockAnimation(canvas, cx, cy)
            TutorialManager.TutorialTopic.AIR_SWIPE_CINEMA -> drawAirSwipeAnimation(canvas, cx, cy)
            TutorialManager.TutorialTopic.GEMINI_VOICE -> drawGeminiVoiceAnimation(canvas, cx, cy)
        }
    }

    private fun drawPointerPinchAnimation(canvas: Canvas, cx: Float, cy: Float) {
        // Hand origin at bottom-left
        val handX = cx - 120f
        val handY = cy + 100f

        // Target button at top-right
        val targetX = cx + 120f
        val targetY = cy - 60f

        // Laser beam from hand to target
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = colorCyan
        canvas.drawLine(handX, handY, targetX, targetY, paint)

        // Pulsing beam particle along ray
        val pulseX = handX + (targetX - handX) * animationProgress
        val pulseY = handY + (targetY - handY) * animationProgress
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(pulseX, pulseY, 8f, paint)

        // Target holographic button
        val targetRect = RectF(targetX - 50f, targetY - 30f, targetX + 50f, targetY + 30f)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#1A237E")
        canvas.drawRoundRect(targetRect, 16f, 16f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = colorCyan
        paint.strokeWidth = 4f
        canvas.drawRoundRect(targetRect, 16f, 16f, paint)

        // Pinch impact ring ripple when animationProgress > 0.6
        if (animationProgress > 0.5f) {
            val rippleRadius = (animationProgress - 0.5f) * 80f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * (1f - animationProgress)
            paint.color = colorMagenta
            canvas.drawCircle(targetX, targetY, rippleRadius, paint)
        }

        // Draw pointer hand cursor icon
        paint.style = Paint.Style.FILL
        paint.color = colorCyan
        canvas.drawCircle(handX, handY, 14f, paint)
    }

    private fun drawResizeMoveAnimation(canvas: Canvas, cx: Float, cy: Float) {
        // Window expands and contracts with sin wave
        val scale = 0.85f + 0.3f * sin(animationProgress * 2 * Math.PI.toFloat())
        val baseW = 180f * scale
        val baseH = 110f * scale

        val rect = RectF(cx - baseW * 0.5f, cy - baseH * 0.5f, cx + baseW * 0.5f, cy + baseH * 0.5f)

        // Window surface
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#1E2138")
        canvas.drawRoundRect(rect, 20f, 20f, paint)

        // Window frame
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = colorCyan
        canvas.drawRoundRect(rect, 20f, 20f, paint)

        // 4 glowing corner grab handles
        val corners = listOf(
            Pair(rect.left, rect.top),
            Pair(rect.right, rect.top),
            Pair(rect.left, rect.bottom),
            Pair(rect.right, rect.bottom)
        )

        for ((x, y) in corners) {
            paint.style = Paint.Style.FILL
            paint.color = colorGold
            canvas.drawCircle(x, y, 10f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 14f, paint)
        }
    }

    private fun drawPalmDockAnimation(canvas: Canvas, cx: Float, cy: Float) {
        // Wrist position at bottom center
        val wristX = cx
        val wristY = cy + 90f

        // Wrist badge
        paint.style = Paint.Style.FILL
        paint.color = colorMagenta
        canvas.drawCircle(wristX, wristY, 16f, paint)

        // Curved dock arc spanning above wrist
        val arcRadius = 140f
        val arcRect = RectF(cx - arcRadius, cy - arcRadius + 40f, cx + arcRadius, cy + arcRadius + 40f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = colorCyan
        canvas.drawArc(arcRect, 200f, 140f, false, paint)

        // App dock icon tiles floating along arc
        val appAngles = listOf(210.0, 240.0, 270.0, 300.0, 330.0)
        for (angleDeg in appAngles) {
            val rad = Math.toRadians(angleDeg)
            val iconX = cx + (arcRadius * cos(rad)).toFloat()
            val iconY = (cy + 40f) + (arcRadius * sin(rad)).toFloat()

            paint.style = Paint.Style.FILL
            paint.color = colorBlue
            canvas.drawRoundRect(iconX - 16f, iconY - 16f, iconX + 16f, iconY + 16f, 8f, 8f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            canvas.drawRoundRect(iconX - 16f, iconY - 16f, iconX + 16f, iconY + 16f, 8f, 8f, paint)
        }
    }

    private fun drawAirSwipeAnimation(canvas: Canvas, cx: Float, cy: Float) {
        // Horizontal speed lines and sliding windows
        val swipeOffset = (animationProgress - 0.5f) * 240f

        // Active app window shifting across screen
        val winW = 160f
        val winH = 100f
        val rect = RectF(cx - winW * 0.5f + swipeOffset, cy - winH * 0.5f, cx + winW * 0.5f + swipeOffset, cy + winH * 0.5f)

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#283593")
        canvas.drawRoundRect(rect, 16f, 16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = colorCyan
        canvas.drawRoundRect(rect, 16f, 16f, paint)

        // Speed trail lines
        paint.color = colorMagenta
        paint.strokeWidth = 4f
        for (i in -2..2) {
            val lineY = cy + i * 25f
            val lineStart = cx - 180f
            val lineEnd = cx - 60f + swipeOffset * 0.4f
            canvas.drawLine(lineStart, lineY, lineEnd, lineY, paint)
        }
    }

    private fun drawGeminiVoiceAnimation(canvas: Canvas, cx: Float, cy: Float) {
        // Center microphone pill
        val micRect = RectF(cx - 35f, cy - 50f, cx + 35f, cy + 20f)
        paint.style = Paint.Style.FILL
        paint.color = colorMagenta
        canvas.drawRoundRect(micRect, 20f, 20f, paint)

        // Dancing frequency audio bars on left and right
        paint.color = colorCyan
        paint.strokeWidth = 6f
        for (i in 1..4) {
            val barH = 15f + 35f * sin((animationProgress * 4f + i) * Math.PI.toFloat())
            // Left bars
            canvas.drawLine(cx - 40f - i * 18f, cy - barH * 0.5f, cx - 40f - i * 18f, cy + barH * 0.5f, paint)
            // Right bars
            canvas.drawLine(cx + 40f + i * 18f, cy - barH * 0.5f, cx + 40f + i * 18f, cy + barH * 0.5f, paint)
        }

        // Gemini 4-point spark star above mic
        val starY = cy - 85f
        val starSize = 16f + 6f * sin(animationProgress * 2 * Math.PI.toFloat())
        path.reset()
        path.moveTo(cx, starY - starSize)
        path.quadTo(cx, starY, cx + starSize, starY)
        path.quadTo(cx, starY, cx, starY + starSize)
        path.quadTo(cx, starY, cx - starSize, starY)
        path.quadTo(cx, starY, cx, starY - starSize)
        path.close()

        paint.style = Paint.Style.FILL
        paint.color = colorGold
        canvas.drawPath(path, paint)
    }
}
