package com.spatial.xr.window

import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs

class WindowInputInjector {

    data class Ray(
        val originX: Float, val originY: Float, val originZ: Float,
        val dirX: Float, val dirY: Float, val dirZ: Float
    )

    data class HitResult(
        val isHit: Boolean,
        val distance: Float,
        val localX: Float,
        val localY: Float,
        val u: Float,
        val v: Float,
        val pixelX: Float,
        val pixelY: Float
    )

    companion object {
        fun raycastQuad(
            ray: Ray,
            quadWidthMeters: Float,
            quadHeightMeters: Float,
            displayWidthPx: Int,
            displayHeightPx: Int
        ): HitResult {
            // Assume quad is on plane Z = 0 facing +Z
            if (abs(ray.dirZ) < 1e-6f) {
                return HitResult(false, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }

            val t = -ray.originZ / ray.dirZ
            if (t < 0f) {
                return HitResult(false, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }

            val hitX = ray.originX + t * ray.dirX
            val hitY = ray.originY + t * ray.dirY

            val halfW = quadWidthMeters * 0.5f
            val halfH = quadHeightMeters * 0.5f

            if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) {
                return HitResult(false, t, hitX, hitY, 0f, 0f, 0f, 0f)
            }

            val u = (hitX + halfW) / quadWidthMeters
            val v = 1f - ((hitY + halfH) / quadHeightMeters) // 0 at top, 1 at bottom

            val pixelX = (u * displayWidthPx).coerceIn(0f, (displayWidthPx - 1).toFloat())
            val pixelY = (v * displayHeightPx).coerceIn(0f, (displayHeightPx - 1).toFloat())

            return HitResult(
                isHit = true,
                distance = t,
                localX = hitX,
                localY = hitY,
                u = u,
                v = v,
                pixelX = pixelX,
                pixelY = pixelY
            )
        }

        fun isTopGrabBarHit(
            hitResult: HitResult,
            quadHeightMeters: Float,
            barThicknessMeters: Float = 0.08f
        ): Boolean {
            if (!hitResult.isHit) return false
            val halfH = quadHeightMeters * 0.5f
            return hitResult.localY >= (halfH - barThicknessMeters)
        }

        fun createMotionEvent(
            action: Int,
            pixelX: Float,
            pixelY: Float,
            downTime: Long = SystemClock.uptimeMillis(),
            eventTime: Long = SystemClock.uptimeMillis()
        ): MotionEvent {
            return MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                pixelX,
                pixelY,
                0
            )
        }
    }
}
