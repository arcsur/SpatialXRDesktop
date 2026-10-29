package com.spatial.xr.hardware

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.spatial.xr.bridge.NativeBridge
import java.nio.ByteBuffer
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
    }

    private val isRunning = AtomicBoolean(false)
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var uvcStreamThread: Thread? = null

    // Fallback/dual Camera2 tracking
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var onFrameProcessedListener: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.i(TAG, "USB Permission GRANTED for ${device.productName} (${device.vendorId}:${device.productId})")
                            startUvcDeviceStreaming(device)
                        }
                    } else {
                        Log.w(TAG, "USB Permission DENIED for ${device?.productName}")
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                checkAndConnectVitureCamera()
            }
        }
    }

    fun setOnFrameProcessedListener(listener: (Boolean) -> Unit) {
        onFrameProcessedListener = listener
    }

    fun startStreaming() {
        if (isRunning.getAndSet(true)) return

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        startBackgroundThread()

        // 1. Attempt connection to VITURE Glasses Stereo Camera
        checkAndConnectVitureCamera()

        // 2. Start Camera2 feed as baseline / dual tracking provider
        startCamera2Stream()
    }

    fun stopStreaming() {
        if (!isRunning.getAndSet(false)) return

        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }

        stopUvcStreaming()
        stopCamera2Stream()
        stopBackgroundThread()
    }

    fun isTrackingActive(): Boolean = isRunning.get()

    private fun checkAndConnectVitureCamera() {
        val device = findVitureCameraDevice()
        if (device != null) {
            usbDevice = device
            Log.i(TAG, "Found optical tracking camera: ${device.productName} [${device.vendorId}:${device.productId}]")
            if (usbManager.hasPermission(device)) {
                startUvcDeviceStreaming(device)
            } else {
                Log.i(TAG, "Requesting USB permission for ${device.productName}")
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
        } else {
            Log.d(TAG, "VITURE USB camera not found yet, operating on Camera2 feed")
        }
    }

    fun findVitureCameraDevice(): UsbDevice? {
        val list = usbManager.deviceList ?: return null
        for (device in list.values) {
            if (device.vendorId == SONIX_VENDOR_ID || device.vendorId == VITURE_VENDOR_ID) {
                // Check if device has video streaming interface (class 14 / 0x0E)
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    if (iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                        return device
                    }
                }
                if (device.vendorId == SONIX_VENDOR_ID) {
                    return device
                }
            }
        }
        return null
    }

    private fun startUvcDeviceStreaming(device: UsbDevice) {
        stopUvcStreaming()

        val connection = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open USB device: ${device.deviceName}")
            return
        }
        usbConnection = connection

        // Locate VideoStreaming interface (class 14, subclass 2)
        var streamingInterface: UsbInterface? = null
        var isocEndpoint: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO && iface.interfaceSubclass == 2) {
                streamingInterface = iface
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        isocEndpoint = ep
                        break
                    }
                }
                if (isocEndpoint != null) break
            }
        }

        if (streamingInterface == null) {
            // Fallback: claim interface 1 directly as reported by dumpsys
            if (device.interfaceCount > 1) {
                streamingInterface = device.getInterface(1)
                if (streamingInterface.endpointCount > 0) {
                    isocEndpoint = streamingInterface.getEndpoint(0)
                }
            }
        }

        if (streamingInterface != null) {
            connection.claimInterface(streamingInterface, true)
            // SET_INTERFACE control transfer to activate isochronous streaming alt-setting 4
            connection.controlTransfer(0x01, 0x0B, 4, streamingInterface.id, null, 0, 1000)
        }

        val targetEp = isocEndpoint
        val fd = connection.fileDescriptor
        Log.i(TAG, "UVC Camera device opened (fd=$fd, endpoint=${targetEp?.address})")

        uvcStreamThread = Thread({
            try {
                val buffer = ByteBuffer.allocateDirect(targetEp?.maxPacketSize ?: 3072)
                val request: UsbRequest? = if (targetEp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val req = UsbRequest()
                        if (req.initialize(connection, targetEp)) req else {
                            req.close()
                            null
                        }
                    } catch (e: Throwable) {
                        null
                    }
                } else null

                val width = 640
                val height = 480
                val yPlane = ByteArray(width * height)
                val uvPlane = ByteArray(width * height / 2)

                while (isRunning.get() && usbConnection != null) {
                    var packetReceived = false
                    if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        buffer.clear()
                        try {
                            if (request.queue(buffer)) {
                                val waitReq = connection.requestWait()
                                if (waitReq === request) {
                                    packetReceived = true
                                }
                            }
                        } catch (e: Throwable) {
                            // Suppress queue errors on non-supported endpoint transfers
                        }
                    }

                    if (!packetReceived) {
                        try {
                            Thread.sleep(16) // ~60 Hz polling
                        } catch (e: InterruptedException) {
                            break
                        }
                        continue
                    }

                    // Parse UVC packet header (Byte 0 = len, Byte 1 = flags)
                    buffer.flip()
                    val len = buffer.remaining()
                    if (len > 12) {
                        val headerLen = buffer.get().toInt() and 0xFF
                        val flags = buffer.get().toInt() and 0xFF
                        val isEof = (flags and 0x02) != 0

                        val payloadLen = len - headerLen
                        if (payloadLen > 0) {
                            buffer.position(headerLen)
                            buffer.get(yPlane, 0, payloadLen.coerceAtMost(yPlane.size))
                        }

                        if (isEof) {
                            val tracked = NativeBridge.nativeProcessCameraFrame(width, height, yPlane, uvPlane)
                            onFrameProcessedListener?.invoke(tracked)
                        }
                    }
                }

                request?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "UVC stream thread exception: ${t.message}")
            }
        }, "VitureUvcStreamThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopUvcStreaming() {
        uvcStreamThread?.interrupt()
        uvcStreamThread = null
        try {
            usbConnection?.close()
        } catch (e: Exception) {
            // Ignore close exception
        }
        usbConnection = null
    }

    private fun startCamera2Stream() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val frontCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return

            val w = 320
            val h = 240
            imageReader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val yBuffer = image.planes[0].buffer
                    val uvBuffer = if (image.planes.size > 1) image.planes[1].buffer else null

                    val yBytes = ByteArray(yBuffer.remaining())
                    yBuffer.get(yBytes)

                    val uvBytes = if (uvBuffer != null) {
                        val bytes = ByteArray(uvBuffer.remaining())
                        uvBuffer.get(bytes)
                        bytes
                    } else null

                    val tracked = NativeBridge.nativeProcessCameraFrame(w, h, yBytes, uvBytes)
                    onFrameProcessedListener?.invoke(tracked)
                } catch (e: Exception) {
                    // Suppress frame read exceptions during buffer recycle
                } finally {
                    image.close()
                }
            }, backgroundHandler)

            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Camera2 initialization exception: ${e.message}")
        }
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val readerSurface = imageReader?.surface ?: return

        try {
            camera.createCaptureSession(listOf(readerSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(readerSurface)
                    }
                    session.setRepeatingRequest(request.build(), null, backgroundHandler)
                    Log.i(TAG, "Camera2 hand tracking capture stream active @ 320x240")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.w(TAG, "Camera2 configure failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "createCaptureSession error: ${e.message}")
        }
    }

    private fun stopCamera2Stream() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            // Ignore cleanup exceptions
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("VitureCameraBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            // Ignore thread join interrupt
        }
    }
}
