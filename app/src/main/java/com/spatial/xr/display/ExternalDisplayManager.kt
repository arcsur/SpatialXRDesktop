package com.spatial.xr.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.Display

class ExternalDisplayManager(
    private val context: Context,
    private val displayManager: DisplayManager,
    private val usbManager: UsbManager,
    private val listener: ExternalDisplayListener? = null
) {

    companion object {
        const val VITURE_VENDOR_ID = 0x35CA
        const val VITURE_PRODUCT_NAME_KEYWORD = "VITURE"
        const val DEX_BYPASS_ADB_COMMAND = "settings put global dex_auto_start_with_hdmi 0"
    }

    interface ExternalDisplayListener {
        fun onExternalDisplayConnected(display: Display, isViture: Boolean)
        fun onExternalDisplayDisconnected(displayId: Int)
    }

    private var currentExternalDisplay: Display? = null
    private var isListening = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val display = displayManager.getDisplay(displayId) ?: return
            if (isPresentationDisplay(display)) {
                currentExternalDisplay = display
                val isViture = detectVitureGlasses()
                listener?.onExternalDisplayConnected(display, isViture)
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (currentExternalDisplay?.displayId == displayId) {
                currentExternalDisplay = null
                listener?.onExternalDisplayDisconnected(displayId)
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            // Handle resolution/orientation change if external display changed
        }
    }

    fun startListening() {
        if (!isListening) {
            displayManager.registerDisplayListener(displayListener, null)
            isListening = true
            // Check if already connected
            findConnectedPresentationDisplay()?.let { display ->
                currentExternalDisplay = display
                listener?.onExternalDisplayConnected(display, detectVitureGlasses())
            }
        }
    }

    fun stopListening() {
        if (isListening) {
            displayManager.unregisterDisplayListener(displayListener)
            isListening = false
        }
    }

    fun isPresentationDisplay(display: Display): Boolean {
        return (display.flags and Display.FLAG_PRESENTATION) != 0
    }

    fun detectVitureGlasses(): Boolean {
        val deviceList = usbManager.deviceList ?: return false
        for (device in deviceList.values) {
            if (isVitureDevice(device)) {
                return true
            }
        }
        return false
    }

    fun isVitureDevice(device: UsbDevice): Boolean {
        if (device.vendorId == VITURE_VENDOR_ID) {
            return true
        }
        val productName = device.productName ?: ""
        val manufacturerName = device.manufacturerName ?: ""
        return productName.contains(VITURE_PRODUCT_NAME_KEYWORD, ignoreCase = true) ||
                manufacturerName.contains(VITURE_PRODUCT_NAME_KEYWORD, ignoreCase = true)
    }

    fun findConnectedPresentationDisplay(): Display? {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (displays.isNotEmpty()) {
            return displays[0]
        }
        // Fallback search across all displays
        for (display in displayManager.displays) {
            if (isPresentationDisplay(display)) {
                return display
            }
        }
        return null
    }

    fun getCurrentDisplay(): Display? = currentExternalDisplay

    fun getDexMitigationInstructions(): String {
        return "To prevent Samsung DeX from intercepting the glasses display surface, run:\nadb shell $DEX_BYPASS_ADB_COMMAND"
    }
}
