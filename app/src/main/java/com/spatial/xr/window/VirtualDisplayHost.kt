package com.spatial.xr.window

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class VirtualDisplayHost(
    private val context: Context,
    private val displayManager: DisplayManager
) {

    companion object {
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_DENSITY_DPI = 320
        const val VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    }

    data class VirtualDisplaySession(
        val sessionId: Int,
        val packageName: String,
        val virtualDisplay: VirtualDisplay?,
        val surfaceTexture: SurfaceTexture?,
        val surface: Surface?,
        var width: Int,
        var height: Int,
        var densityDpi: Int
    )

    private val sessionCounter = AtomicInteger(1)
    private val activeSessions = ConcurrentHashMap<Int, VirtualDisplaySession>()

    fun createDisplaySession(
        packageName: String,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        densityDpi: Int = DEFAULT_DENSITY_DPI
    ): VirtualDisplaySession {
        val sessionId = sessionCounter.getAndIncrement()

        val surfaceTexture = SurfaceTexture(sessionId).apply {
            setDefaultBufferSize(width, height)
        }
        val surface = Surface(surfaceTexture)

        val virtualDisplay = try {
            displayManager.createVirtualDisplay(
                "SpatialWindow-$sessionId-$packageName",
                width,
                height,
                densityDpi,
                surface,
                VIRTUAL_DISPLAY_FLAGS
            )
        } catch (e: Exception) {
            // Null-safe for headless testing environments without full system display service
            null
        }

        val session = VirtualDisplaySession(
            sessionId = sessionId,
            packageName = packageName,
            virtualDisplay = virtualDisplay,
            surfaceTexture = surfaceTexture,
            surface = surface,
            width = width,
            height = height,
            densityDpi = densityDpi
        )

        activeSessions[sessionId] = session
        return session
    }

    fun resizeDisplaySession(sessionId: Int, newWidth: Int, newHeight: Int, newDpi: Int = DEFAULT_DENSITY_DPI): Boolean {
        val session = activeSessions[sessionId] ?: return false
        val clampedWidth = newWidth.coerceIn(320, 3840)
        val clampedHeight = newHeight.coerceIn(240, 2160)

        session.width = clampedWidth
        session.height = clampedHeight
        session.densityDpi = newDpi
        session.surfaceTexture?.setDefaultBufferSize(clampedWidth, clampedHeight)
        session.virtualDisplay?.resize(clampedWidth, clampedHeight, newDpi)
        return true
    }

    fun releaseDisplaySession(sessionId: Int) {
        activeSessions.remove(sessionId)?.let { session ->
            session.virtualDisplay?.release()
            session.surface?.release()
            session.surfaceTexture?.release()
        }
    }

    fun getSession(sessionId: Int): VirtualDisplaySession? = activeSessions[sessionId]

    fun getAllSessions(): Collection<VirtualDisplaySession> = activeSessions.values

    fun releaseAll() {
        activeSessions.keys.toList().forEach { releaseDisplaySession(it) }
    }
}
