package com.spatial.xr.window

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

class AppLauncherRepository(
    private val context: Context? = null,
    private val packageManager: PackageManager? = null
) {

    data class AppItem(
        val packageName: String,
        val label: String,
        val activityName: String,
        val icon: Drawable? = null
    )

    data class DockItem3D(
        val appItem: AppItem,
        val positionX: Float,
        val positionY: Float,
        val positionZ: Float,
        val rotationYRad: Float
    )

    private val cachedApps = mutableListOf<AppItem>()

    fun loadLaunchableApps(): List<AppItem> {
        cachedApps.clear()
        if (packageManager != null) {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            for (info in resolveInfos) {
                val label = info.loadLabel(packageManager).toString()
                val pkg = info.activityInfo.packageName
                val activity = info.activityInfo.name
                val icon = info.loadIcon(packageManager)
                cachedApps.add(AppItem(pkg, label, activity, icon))
            }
        }
        return cachedApps
    }

    fun setAppsForTesting(apps: List<AppItem>) {
        cachedApps.clear()
        cachedApps.addAll(apps)
    }

    fun getApps(): List<AppItem> = cachedApps

    /**
     * Task 5.1: Curved 3D horizontal dock arrangement at chest level.
     * Arranges app icons along an arc with radius R (meters) centered in front of user.
     */
    fun computeCurvedDockLayout(
        arcRadiusMeters: Float = 0.8f,
        yOffsetMeters: Float = -0.2f, // Chest level
        totalArcAngleDeg: Float = 60.0f
    ): List<DockItem3D> {
        val count = cachedApps.size
        if (count == 0) return emptyList()

        val dockItems = mutableListOf<DockItem3D>()
        val totalRad = Math.toRadians(totalArcAngleDeg.toDouble()).toFloat()
        val stepRad = if (count > 1) totalRad / (count - 1) else 0f
        val startRad = -totalRad * 0.5f

        for (i in 0 until count) {
            val angle = startRad + i * stepRad
            val x = arcRadiusMeters * sin(angle)
            val z = -arcRadiusMeters * cos(angle)
            dockItems.add(
                DockItem3D(
                    appItem = cachedApps[i],
                    positionX = x,
                    positionY = yOffsetMeters,
                    positionZ = z,
                    rotationYRad = -angle
                )
            )
        }
        return dockItems
    }
}
