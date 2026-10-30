package com.spatial.xr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.spatial.xr.ai.GeminiSpatialAgent
import com.spatial.xr.bridge.NativeBridge
import com.spatial.xr.display.ExternalDisplayManager
import com.spatial.xr.display.PocketModeManager
import com.spatial.xr.display.PresentationManager
import com.spatial.xr.hardware.VitureCameraStreamer
import com.spatial.xr.tutorial.TutorialManager
import com.spatial.xr.tutorial.TutorialOverlayDialog
import com.spatial.xr.window.AppLauncherRepository
import com.spatial.xr.window.SpatialWindowManager
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(),
    ExternalDisplayManager.ExternalDisplayListener,
    PresentationManager.PresentationSurfaceCallback {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var displayManager: DisplayManager
    private lateinit var usbManager: UsbManager
    private lateinit var externalDisplayManager: ExternalDisplayManager
    private lateinit var presentationManager: PresentationManager
    private lateinit var tutorialManager: TutorialManager
    private lateinit var geminiAgent: GeminiSpatialAgent
    private lateinit var spatialWindowManager: SpatialWindowManager
    private lateinit var appLauncherRepository: AppLauncherRepository
    private lateinit var vitureCameraStreamer: VitureCameraStreamer
    private lateinit var pocketModeManager: PocketModeManager

    private var statusTextView: TextView? = null
    private var dockButton: Button? = null
    private var stereoButton: Button? = null
    private var pocketModeButton: Button? = null

    // Touchpad tracking variables
    private var initialPinchDist = 0f
    private var activeWindowId = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Spatial Systems
        spatialWindowManager = SpatialWindowManager()
        appLauncherRepository = AppLauncherRepository(this, packageManager)
        appLauncherRepository.loadLaunchableApps()

        tutorialManager = TutorialManager(this)
        geminiAgent = GeminiSpatialAgent(tutorialManager = tutorialManager)

        geminiAgent.setCustomExecutor(object : GeminiSpatialAgent.SpatialToolExecutor {
            override fun onLaunchApp(packageName: String, anchorMode: String): Boolean = true
            override fun onRepositionWindow(windowId: Int, action: String): Boolean = true
            override fun onOrganizeWorkspace(layoutStyle: String): Boolean = true
            override fun onAnalyzeScene(query: String): String = "Spatial Scene Analyzer Active"
            override fun onShowTutorial(topic: String): Boolean {
                runOnUiThread {
                    tutorialManager.startTutorialForQuery(topic)
                    showTutorialDialog()
                }
                return true
            }
        })

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        vitureCameraStreamer = VitureCameraStreamer(this, usbManager).apply {
            setOnFrameProcessedListener { tracked ->
                if (tracked) {
                    runOnUiThread {
                        if (statusTextView?.text?.contains("🖐️") != true) {
                            statusTextView?.append("\n🖐️ Optical Hand Tracking: ACTIVE")
                        }
                    }
                }
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        presentationManager = PresentationManager(
            this,
            callback = this,
            windowManager = spatialWindowManager,
            launcherRepository = appLauncherRepository
        )
        externalDisplayManager = ExternalDisplayManager(this, displayManager, usbManager, this)
        pocketModeManager = PocketModeManager(this, object : PocketModeManager.PocketModeListener {
            override fun onPocketModeChanged(isActive: Boolean) {
                runOnUiThread {
                    if (isActive) {
                        presentationManager.getRenderer()?.setPointer(0f, 0f, active = false, pinching = false)
                        initialPinchDist = 0f
                        if (statusTextView?.text?.contains("🌙") != true) {
                            statusTextView?.append("\n🌙 Pocket Mode Active (Screen Off & Touches Locked)")
                        }
                    }
                }
            }
        })

        // UI Setup: Spatial Controller Deck
        setupControllerDeckUi()

        checkPermissions()

        // First launch usage guide: check if tutorial completed
        if (!tutorialManager.isTutorialCompleted()) {
            showTutorialDialog()
        }
    }

    private fun setupControllerDeckUi() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0D1B"))
            setPadding(32, 48, 32, 48)
        }

        // Title and Status Card
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151A30"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#3000E5FF"))
            }
        }

        val titleView = TextView(this).apply {
            text = "⚡ SPATIAL XR DESKTOP"
            textSize = 20f
            setTextColor(Color.parseColor("#00E5FF"))
            paint.isFakeBoldText = true
        }
        headerCard.addView(titleView)

        statusTextView = TextView(this).apply {
            text = "Status: Detecting VITURE Glasses..."
            textSize = 14f
            setTextColor(Color.parseColor("#A0B0D0"))
            setPadding(0, 8, 0, 0)
        }
        headerCard.addView(statusTextView)
        rootLayout.addView(headerCard)

        // Action Buttons Grid
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 16)
        }

        dockButton = Button(this).apply {
            text = "🖐️ Apps Dock"
            setBackgroundColor(Color.parseColor("#1A237E"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
            }
            setOnClickListener {
                toggleAppsDock()
            }
        }
        buttonRow.addView(dockButton)

        val newWinButton = Button(this).apply {
            text = "🪟 New Window"
            setBackgroundColor(Color.parseColor("#283593"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12
            }
            setOnClickListener {
                spawnDemoWindow()
            }
        }
        buttonRow.addView(newWinButton)
        rootLayout.addView(buttonRow)

        val secondaryButtonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }

        stereoButton = Button(this).apply {
            text = "↔️ Mode: 3D SBS"
            setBackgroundColor(Color.parseColor("#00695C"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
            }
            setOnClickListener {
                toggleStereoMode()
            }
        }
        secondaryButtonRow.addView(stereoButton)

        val tutorialButton = Button(this).apply {
            text = "📖 Usage Guide"
            setBackgroundColor(Color.parseColor("#37474F"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12
            }
            setOnClickListener {
                showTutorialDialog()
            }
        }
        secondaryButtonRow.addView(tutorialButton)
        rootLayout.addView(secondaryButtonRow)

        // Utility Controls: Pocket Mode / Screen Off
        val utilityButtonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }

        pocketModeButton = Button(this).apply {
            text = "🌙 Turn Off Screen (Pocket Mode)"
            setTextColor(Color.parseColor("#00E5FF"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151A30"))
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#4000E5FF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                pocketModeManager.enterPocketMode()
            }
        }
        utilityButtonRow.addView(pocketModeButton)
        rootLayout.addView(utilityButtonRow)

        // Spatial Touchpad / Laser Pointer Area
        val touchpadLabel = TextView(this).apply {
            text = "📱 SPATIAL TRACKPAD (Drag to Aim • Tap to Pinch • 2-Finger Stretch)"
            textSize = 12f
            setTextColor(Color.parseColor("#7080A0"))
            setPadding(8, 0, 8, 8)
        }
        rootLayout.addView(touchpadLabel)

        val touchpadView = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#12162A"))
                cornerRadius = 24f
                setStroke(3, Color.parseColor("#4000E5FF"))
            }

            setOnTouchListener { v, event ->
                handleTouchpadEvent(v, event)
                true
            }
        }

        val touchpadCenterHint = TextView(this).apply {
            text = "✦\nAim Laser in Glasses"
            textSize = 16f
            setTextColor(Color.parseColor("#4000E5FF"))
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        }
        touchpadView.addView(touchpadCenterHint)
        rootLayout.addView(touchpadView)

        setContentView(rootLayout)
    }

    private fun handleTouchpadEvent(view: View, event: MotionEvent) {
        val renderer = presentationManager.getRenderer() ?: return
        val normX = (event.x / view.width.toFloat()).coerceIn(0f, 1f)
        val normY = (event.y / view.height.toFloat()).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                renderer.setPointer(normX, normY, active = true, pinching = false)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Two-finger pinch detected
                if (event.pointerCount >= 2) {
                    val dx = event.getX(0) - event.getX(1)
                    val dy = event.getY(0) - event.getY(1)
                    initialPinchDist = sqrt(dx * dx + dy * dy).coerceAtLeast(10f)
                    spatialWindowManager.startTwoHandDiagonalResize(activeWindowId, initialPinchDist / 400f)
                    renderer.setPointer(normX, normY, active = true, pinching = true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && initialPinchDist > 0f) {
                    val dx = event.getX(0) - event.getX(1)
                    val dy = event.getY(0) - event.getY(1)
                    val currentDist = sqrt(dx * dx + dy * dy)
                    spatialWindowManager.updateTwoHandDiagonalResize(activeWindowId, currentDist / 400f)
                    renderer.setPointer(normX, normY, active = true, pinching = true)
                } else {
                    renderer.setPointer(normX, normY, active = true, pinching = false)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    spatialWindowManager.endTwoHandDiagonalResize(activeWindowId)
                    initialPinchDist = 0f
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                renderer.setPointer(normX, normY, active = false, pinching = false)
                initialPinchDist = 0f
            }
        }
    }

    private fun toggleAppsDock() {
        val renderer = presentationManager.getRenderer()
        if (renderer != null) {
            renderer.toggleDock()
            val state = if (renderer.isDockExpanded) "OPEN" else "CLOSED"
            dockButton?.text = if (renderer.isDockExpanded) "🖐️ Close Dock" else "🖐️ Apps Dock"
            Toast.makeText(this, "Apps Dock: $state", Toast.LENGTH_SHORT).show()
        } else {
            // Also toggle window manager dock directly
            val newState = !spatialWindowManager.isWristDockVisible()
            spatialWindowManager.setWristDockVisible(newState)
            dockButton?.text = if (newState) "🖐️ Close Dock" else "🖐️ Apps Dock"
            Toast.makeText(this, "Dock: ${if (newState) "OPEN" else "CLOSED"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleStereoMode() {
        val renderer = presentationManager.getRenderer() ?: return
        renderer.isStereoSideBySide = !renderer.isStereoSideBySide
        if (renderer.isStereoSideBySide) {
            stereoButton?.text = "↔️ Mode: 3D SBS"
            NativeBridge.nativeSet3DMode(true)
            Toast.makeText(this, "3D Side-by-Side Mode Enabled", Toast.LENGTH_SHORT).show()
        } else {
            stereoButton?.text = "↔️ Mode: 2D Mono"
            NativeBridge.nativeSet3DMode(false)
            Toast.makeText(this, "2D Monoscopic Mode Enabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun spawnDemoWindow() {
        val nextId = (spatialWindowManager.getAllWindows().size + 1)
        activeWindowId = nextId
        val offsetX = (nextId % 2 == 0) then 0.35f ?: -0.35f
        spatialWindowManager.createWindow(
            windowId = nextId,
            packageName = "com.spatial.xr.window$nextId",
            displaySessionId = 100 + nextId,
            widthMeters = 0.90f,
            heightMeters = 0.55f
        ).apply {
            position = SpatialWindowManager.Vector3(offsetX, 0.1f, -1.3f)
        }
        Toast.makeText(this, "Spawned Hologram Window #$nextId", Toast.LENGTH_SHORT).show()
    }

    private infix fun <T> Boolean.then(value: T): T? = if (this) value else null

    private fun showTutorialDialog() {
        TutorialOverlayDialog(this, tutorialManager).show()
    }

    private fun checkPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            onPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                onPermissionsGranted()
            } else {
                Toast.makeText(this, "Microphone permission required for AI voice assistant", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onPermissionsGranted() {
        externalDisplayManager.startListening()
        vitureCameraStreamer.startStreaming()
    }

    override fun onResume() {
        super.onResume()
        externalDisplayManager.startListening()
        if (::pocketModeManager.isInitialized) {
            pocketModeManager.startListening()
        }
    }

    override fun onPause() {
        super.onPause()
        // Do NOT detach display on pause - XR glasses presentation must remain active
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::pocketModeManager.isInitialized) {
            pocketModeManager.stopListening()
        }
        vitureCameraStreamer.stopStreaming()
        presentationManager.detachDisplay()
        externalDisplayManager.stopListening()
        NativeBridge.nativeShutdownCompositor()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && ::pocketModeManager.isInitialized && pocketModeManager.onDispatchTouchEvent(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::pocketModeManager.isInitialized && pocketModeManager.onKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ExternalDisplayManager.ExternalDisplayListener
    override fun onExternalDisplayConnected(display: Display, isViture: Boolean) {
        runOnUiThread {
            val msg = "👓 VITURE Glasses Connected: ${display.name} (Display #${display.displayId})\nResolution: ${display.mode.physicalWidth}x${display.mode.physicalHeight} @ ${display.mode.refreshRate.toInt()}Hz"
            statusTextView?.text = msg
            presentationManager.attachDisplay(display)
        }
    }

    override fun onExternalDisplayDisconnected(displayId: Int) {
        runOnUiThread {
            statusTextView?.text = "Status: Glasses Disconnected. Waiting for connection..."
            presentationManager.detachDisplay()
            NativeBridge.nativeShutdownCompositor()
        }
    }

    // PresentationManager.PresentationSurfaceCallback
    override fun onSurfaceCreated(holder: SurfaceHolder) {
        NativeBridge.nativeInitCompositor(holder.surface)
        NativeBridge.nativeSet3DMode(true)
        runOnUiThread {
            statusTextView?.append("\n✅ 3D SBS Compositor Active (60 FPS)")
        }
    }

    override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun onSurfaceDestroyed(holder: SurfaceHolder) {
    }

    override fun onDismissed() {
    }
}
