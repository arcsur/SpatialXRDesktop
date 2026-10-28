package com.spatial.xr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.spatial.xr.bridge.NativeBridge
import com.spatial.xr.display.ExternalDisplayManager
import com.spatial.xr.display.PresentationManager

class MainActivity : AppCompatActivity(),
    ExternalDisplayManager.ExternalDisplayListener,
    PresentationManager.PresentationSurfaceCallback {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var displayManager: DisplayManager
    private lateinit var usbManager: UsbManager
    private lateinit var externalDisplayManager: ExternalDisplayManager
    private lateinit var presentationManager: PresentationManager
    private var statusTextView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusTextView = TextView(this).apply {
            text = "Spatial XR Desktop Initializing...\nConnect VITURE Glasses via USB-C DP Alt Mode."
            textSize = 16f
            setPadding(32, 64, 32, 64)
        }
        setContentView(statusTextView)

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        presentationManager = PresentationManager(this, this)
        externalDisplayManager = ExternalDisplayManager(this, displayManager, usbManager, this)

        checkPermissions()
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
                Toast.makeText(this, "Permissions required for camera/audio tracking", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onPermissionsGranted() {
        externalDisplayManager.startListening()
    }

    override fun onResume() {
        super.onResume()
        externalDisplayManager.startListening()
    }

    override fun onPause() {
        super.onPause()
        externalDisplayManager.stopListening()
        presentationManager.detachDisplay()
    }

    override fun onDestroy() {
        super.onDestroy()
        NativeBridge.nativeShutdownCompositor()
    }

    // ExternalDisplayManager.ExternalDisplayListener
    override fun onExternalDisplayConnected(display: Display, isViture: Boolean) {
        val msg = "External Display Connected: ${display.name} (ID: ${display.displayId}, Viture: $isViture)"
        statusTextView?.text = msg
        presentationManager.attachDisplay(display)
    }

    override fun onExternalDisplayDisconnected(displayId: Int) {
        statusTextView?.text = "External Display Disconnected. Waiting for glasses..."
        presentationManager.detachDisplay()
        NativeBridge.nativeShutdownCompositor()
    }

    // PresentationManager.PresentationSurfaceCallback
    override fun onSurfaceCreated(holder: SurfaceHolder) {
        val success = NativeBridge.nativeInitCompositor(holder.surface)
        if (success) {
            NativeBridge.nativeSet3DMode(true)
            NativeBridge.nativeRenderCalibrationPattern()
            statusTextView?.append("\nVulkan Compositor initialized (3840x1080 3D SBS).")
        }
    }

    override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun onSurfaceDestroyed(holder: SurfaceHolder) {
        NativeBridge.nativeShutdownCompositor()
    }

    override fun onDismissed() {
        NativeBridge.nativeShutdownCompositor()
    }
}
