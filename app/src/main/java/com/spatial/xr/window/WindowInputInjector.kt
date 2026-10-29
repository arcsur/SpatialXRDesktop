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

        /**
         * Generous "close enough" hit detection for top grab handle.
         * Allows hitting outside the strict window boundary by [slackMeters].
         */
        fun isTopGrabBarHitWithSlack(
            localX: Float,
            localY: Float,
            quadWidthMeters: Float,
            quadHeightMeters: Float,
            barThicknessMeters: Float = 0.12f,
            slackMeters: Float = 0.22f
        ): Boolean {
            val halfW = quadWidthMeters * 0.5f
            val halfH = quadHeightMeters * 0.5f

            val isXInRange = abs(localX) <= (halfW + slackMeters)
            val isYInRange = localY >= (halfH - barThicknessMeters - slackMeters * 0.5f) &&
                    localY <= (halfH + slackMeters)
            return isXInRange && isYInRange
        }

        /**
         * Generous "close enough" hit detection for 4-corner resize handles.
         * Checks if (localX, localY) is within [toleranceMeters] of any corner.
         */
        fun getNearestResizeCorner(
            localX: Float,
            localY: Float,
            quadWidthMeters: Float,
            quadHeightMeters: Float,
            toleranceMeters: Float = 0.22f
        ): SpatialWindowManager.ResizeCorner? {
            val halfW = quadWidthMeters * 0.5f
            val halfH = quadHeightMeters * 0.5f

            val corners = listOf(
                Pair(SpatialWindowManager.ResizeCorner.TOP_RIGHT, Pair(halfW, halfH)),
                Pair(SpatialWindowManager.ResizeCorner.TOP_LEFT, Pair(-halfW, halfH)),
                Pair(SpatialWindowManager.ResizeCorner.BOTTOM_RIGHT, Pair(halfW, -halfH)),
                Pair(SpatialWindowManager.ResizeCorner.BOTTOM_LEFT, Pair(-halfW, -halfH))
            )

            var nearestCorner: SpatialWindowManager.ResizeCorner? = null
            var minDistance = Float.MAX_VALUE

            for ((corner, pos) in corners) {
                val dx = localX - pos.first
                val dy = localY - pos.second
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist <= toleranceMeters && dist < minDistance) {
                    minDistance = dist
                    nearestCorner = corner
                }
            }
            return nearestCorner
        }

        /**
         * Generous edge hit detection for window edges.
         * If the pinch is near left/right/bottom edges (within [edgeSlackMeters]),
         * maps to the corresponding ResizeCorner.
         */
        fun getNearestEdgeResizeCorner(
            localX: Float,
            localY: Float,
            quadWidthMeters: Float,
            quadHeightMeters: Float,
            edgeSlackMeters: Float = 0.18f
        ): SpatialWindowManager.ResizeCorner? {
            val halfW = quadWidthMeters * 0.5f
            val halfH = quadHeightMeters * 0.5f

            if (abs(localX) > halfW + edgeSlackMeters || abs(localY) > halfH + edgeSlackMeters) {
                return null
            }

            // Exclude the top region (reserved for top grab bar)
            if (localY > halfH - 0.08f) {
                return null
            }

            val distToRight = abs(localX - halfW)
            val distToLeft = abs(localX - (-halfW))
            val distToBottom = abs(localY - (-halfH))

            val minDist = minOf(distToRight, distToLeft, distToBottom)
            if (minDist > edgeSlackMeters) {
                return null
            }

            return when (minDist) {
                distToRight -> if (localY >= 0) SpatialWindowManager.ResizeCorner.TOP_RIGHT else SpatialWindowManager.ResizeCorner.BOTTOM_RIGHT
                distToLeft -> if (localY >= 0) SpatialWindowManager.ResizeCorner.TOP_LEFT else SpatialWindowManager.ResizeCorner.BOTTOM_LEFT
                else -> if (localX >= 0) SpatialWindowManager.ResizeCorner.BOTTOM_RIGHT else SpatialWindowManager.ResizeCorner.BOTTOM_LEFT
            }
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
