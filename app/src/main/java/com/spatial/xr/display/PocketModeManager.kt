package com.spatial.xr.display

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class PocketModeManager(
    private val activity: Activity,
    private val listener: PocketModeListener? = null
) {

    companion object {
        const val PROXIMITY_THRESHOLD_CM = 4.0f
        const val WAKE_FALLBACK_KEY = KeyEvent.KEYCODE_VOLUME_UP

        fun evaluateIsNear(distance: Float, maxRange: Float): Boolean {
            return distance < maxRange && distance < PROXIMITY_THRESHOLD_CM
        }

        fun canWakeOnDoubleTap(isPocketMode: Boolean, isNearProximity: Boolean): Boolean {
            return isPocketMode && !isNearProximity
        }
    }

    interface PocketModeListener {
        fun onPocketModeChanged(isActive: Boolean)
    }

    var isPocketMode: Boolean = false
        private set

    var isNearProximity: Boolean = false
        private set

    private var originalBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var overlayView: FrameLayout? = null

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
                val distance = event.values[0]
                val maxRange = proximitySensor?.maximumRange ?: 5f
                isNearProximity = evaluateIsNear(distance, maxRange)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (canWakeOnDoubleTap(isPocketMode, isNearProximity)) {
                exitPocketMode()
                return true
            }
            return isPocketMode // consume event if in pocket mode
        }

        override fun onDown(e: MotionEvent): Boolean = isPocketMode
    })

    init {
        sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    fun startListening() {
        proximitySensor?.let { sensor ->
            sensorManager?.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stopListening() {
        sensorManager?.unregisterListener(proximityListener)
    }

    fun enterPocketMode() {
        if (isPocketMode) return
        isPocketMode = true

        val window = activity.window
        originalBrightness = window.attributes.screenBrightness

        // 1. Minimum screen brightness (OLED off)
        val lp = window.attributes
        lp.screenBrightness = 0.0f
        window.attributes = lp

        // 2. Hide system bars (immersive blackout)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 3. Keep screen awake so presentation and USB streaming are uninterrupted
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 4. Attach black overlay view
        showOverlay()

        activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onPocketModeChanged(true)
    }

    fun exitPocketMode() {
        if (!isPocketMode) return
        isPocketMode = false

        val window = activity.window

        // Restore brightness
        val lp = window.attributes
        lp.screenBrightness = originalBrightness
        window.attributes = lp

        // Restore system bars
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.systemBars())
        }

        hideOverlay()

        activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onPocketModeChanged(false)
    }

    /**
     * Intercepts touches before they reach any child views.
     * Returns true if event was consumed (blocking all other touch processing).
     */
    fun onDispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isPocketMode) {
            gestureDetector.onTouchEvent(ev)
            return true
        }
        return false
    }

    /**
     * Handles hardware keys. Provides Volume Up as hardware wake fallback.
     */
    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isPocketMode) {
            if (keyCode == WAKE_FALLBACK_KEY) {
                exitPocketMode()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // Ignore back key inside pocket to prevent accidental back navigation
                return true
            }
        }
        return false
    }

    private fun showOverlay() {
        if (overlayView == null) {
            overlayView = FrameLayout(activity).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                isFocusable = true
                val label = TextView(activity).apply {
                    text = "🔒 Screen Off (Pocket Mode)\nDouble-tap out of pocket or press Vol+ to wake"
                    textSize = 14f
                    setTextColor(Color.parseColor("#1AFFFFFF")) // very dim OLED text
                    gravity = Gravity.CENTER
                }
                addView(
                    label,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )
            }
            activity.addContentView(
                overlayView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        overlayView?.visibility = View.VISIBLE
        overlayView?.bringToFront()
    }

    private fun hideOverlay() {
        overlayView?.visibility = View.GONE
    }
}
