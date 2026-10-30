package com.spatial.xr.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.spatial.xr.bridge.NativeBridge
import com.spatial.xr.tracking.MediaPipeHandTracker
import java.util.concurrent.atomic.AtomicBoolean

class VitureCameraStreamer(
    private val context: Context,
    private val usbManager: UsbManager
) {
    companion object {
        private const val TAG = "VitureCameraStreamer"
        const val ACTION_USB_PERMISSION = "com.spatial.xr.USB_PERMISSION"
        const val SONIX_VENDOR_ID = 3141 // 0x0C45
        const val SONIX_PRODUCT_ID = 25451 // 0x636B
        const val VITURE_VENDOR_ID = 13770 // 0x35CA

        val VITURE_PIDS = setOf(
            0x1011, 0x1013, 0x1015, 0x1017, 0x101B,
            0x1019, 0x101D,
            0x1121, 0x1131, 0x1141, 0x1151,
            0x1101, 0x1104, // Luma Ultra
            0x1201, 0x1211,
            0x1221
        )
    }

    private val isRunning = AtomicBoolean(false)
    private val isGlassesStreaming = AtomicBoolean(false)

    private var glassesConnection: UsbDeviceConnection? = null
    private var cameraConnection: UsbDeviceConnection? = null
    private var glassesStreamThread: Thread? = null
    private var mediaPipeTracker: MediaPipeHandTracker? = null


    private var onFrameProcessedListener: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.i(TAG, "USB Permission GRANTED for ${device.productName} (${device.vendorId}:${device.productId})")
                            onUsbDevicePermitted(device)
                        }
                    } else {
                        Log.w(TAG, "USB Permission DENIED for ${device?.productName}")
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                checkAndConnectDevices()
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                if (device != null && device.vendorId == VITURE_VENDOR_ID && device.productId in VITURE_PIDS) {
                    Log.i(TAG, "VITURE glasses detached: ${device.productName}.")
                    stopGlassesStreaming()
                }
            }
        }
    }

    fun setOnFrameProcessedListener(listener: (Boolean) -> Unit) {
        onFrameProcessedListener = listener
    }

    fun startStreaming() {
        if (isRunning.getAndSet(true)) return

        mediaPipeTracker = MediaPipeHandTracker(context)

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        val glasses = findVitureGlassesDevice()
        if (glasses != null) {
            Log.i(TAG, "VITURE glasses detected on startup: ${glasses.productName}")
            checkAndConnectDevices()
        } else {
            Log.i(TAG, "No glasses attached yet. Waiting for USB attachment.")
        }
    }

    fun stopStreaming() {
        if (!isRunning.getAndSet(false)) return

        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}

        stopGlassesStreaming()

        mediaPipeTracker?.close()
        mediaPipeTracker = null
    }

    fun isTrackingActive(): Boolean = isRunning.get()

    private fun checkAndConnectDevices() {
        val glasses = findVitureGlassesDevice()
        if (glasses != null) {
            if (usbManager.hasPermission(glasses)) {
                startGlassesStreaming(glasses)
            } else {
                requestDevicePermission(glasses)
            }
        }
    }

    fun findVitureGlassesDevice(): UsbDevice? {
        val list = usbManager.deviceList ?: return null
        for (device in list.values) {
            if (device.vendorId == VITURE_VENDOR_ID && device.productId in VITURE_PIDS) {
                return device
            }
            val name = (device.productName ?: "") + " " + (device.manufacturerName ?: "")
            if (name.contains("VITURE", ignoreCase = true) && device.vendorId == VITURE_VENDOR_ID) {
                return device
            }
        }
        return null
    }

    fun findVitureRgbCameraDevice(): UsbDevice? {
        val list = usbManager.deviceList ?: return null
        return list.values.firstOrNull { it.vendorId == SONIX_VENDOR_ID && it.productId == SONIX_PRODUCT_ID }
    }

    private fun requestDevicePermission(device: UsbDevice) {
        Log.i(TAG, "Requesting USB permission for ${device.productName} [0x${Integer.toHexString(device.vendorId)}:0x${Integer.toHexString(device.productId)}]")
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, intent, flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun onUsbDevicePermitted(device: UsbDevice) {
        if (device.vendorId == VITURE_VENDOR_ID || (device.productName ?: "").contains("VITURE", ignoreCase = true)) {
            startGlassesStreaming(device)
        }
    }

    private fun startGlassesStreaming(device: UsbDevice) {
        if (isGlassesStreaming.get()) return
        stopGlassesStreaming()

        val connection = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open USB glasses device: ${device.deviceName}")
            return
        }
        glassesConnection = connection
        val fd = connection.fileDescriptor
        Log.i(TAG, "Initializing VITURE glasses SDK with PID=0x${Integer.toHexString(device.productId)}, fd=$fd")

        if (!NativeBridge.nativeVitureCreate(device.productId, fd)) {
            Log.e(TAG, "nativeVitureCreate failed")
            connection.close()
            glassesConnection = null
            return
        }

        if (!NativeBridge.nativeVitureStartCarina()) {
            Log.e(TAG, "nativeVitureStartCarina failed")
            NativeBridge.nativeVitureStop()
            connection.close()
            glassesConnection = null
            return
        }

        isGlassesStreaming.set(true)
        Log.i(TAG, "VITURE Carina stereo cameras active.")

        glassesStreamThread = Thread({
            val frameBuf = ByteArray(1280 * 800)
            var frameCount = 0
            while (isRunning.get() && isGlassesStreaming.get()) {
                val dims = NativeBridge.nativeVitureGetCarinaFrame(frameBuf)
                if (dims != null && dims.size >= 2) {
                    val w = dims[0]
                    val h = dims[1]
                    frameCount++
                    if (frameCount % 60 == 1) {
                        var sum = 0L
                        var minVal = 255
                        var maxVal = 0
                        val sampleStep = 16
                        val totalSamples = (w * h) / sampleStep
                        for (i in 0 until (w * h) step sampleStep) {
                            val v = frameBuf[i].toInt() and 0xFF
                            sum += v
                            if (v < minVal) minVal = v
                            if (v > maxVal) maxVal = v
                        }
                        val avg = if (totalSamples > 0) sum / totalSamples else 0
                        Log.i(TAG, "Viture Carina stereo camera frame received #$frameCount (${w}x${h}) [lum: min=$minVal, max=$maxVal, avg=$avg]")
                    }
                    mediaPipeTracker?.processGrayscaleFrame(w, h, frameBuf)
                    onFrameProcessedListener?.invoke(true)
                } else {
                    try {
                        Thread.sleep(8) // ~120 Hz poll
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "VitureCarinaStreamThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun startRgbCameraStreaming(device: UsbDevice) {
        val connection = usbManager.openDevice(device) ?: return
        cameraConnection = connection
        val fd = connection.fileDescriptor
        if (NativeBridge.nativeVitureCameraCreate(device.vendorId, device.productId, fd)) {
            if (NativeBridge.nativeVitureCameraStart()) {
                Log.i(TAG, "VITURE RGB Center Camera streaming active")
            }
        }
    }

    private fun stopGlassesStreaming() {
        isGlassesStreaming.set(false)
        glassesStreamThread?.interrupt()
        glassesStreamThread = null
        NativeBridge.nativeVitureStop()

        try {
            glassesConnection?.close()
        } catch (_: Exception) {}
        glassesConnection = null

        try {
            cameraConnection?.close()
        } catch (_: Exception) {}
        cameraConnection = null
    }
}
