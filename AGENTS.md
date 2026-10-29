# Spatial XR Desktop - Development Rules & Hardware Guidelines

## 1. XR Display & Presentation Lifecycle
- **Never detach presentation displays on `onPause()`**: System dialogs (e.g., USB permissions, runtime dialogs) pause the activity. Calling `presentationManager.detachDisplay()` in `onPause()` causes screen blanking in the glasses. Only detach displays in `onDestroy()` or when `DisplayListener.onDisplayRemoved()` fires.
- **Maintain active SurfaceView rendering**: External XR displays require an active hardware canvas render thread (`lockHardwareCanvas()`) at 60 FPS. Idle presentation surfaces risk being marked as `mSleeping=true` by SurfaceFlinger.

## 2. Android 14+ (API 34) USB Host Security
- **Explicit PendingIntents for USB permissions**: When using `PendingIntent.FLAG_MUTABLE` with `UsbManager.requestPermission()`, always set the explicit package:
  ```kotlin
  val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
  val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
  ```
- **BroadcastReceiver registration**: When listening for system USB events (`ACTION_USB_DEVICE_ATTACHED`) on API 33+, use `Context.RECEIVER_EXPORTED`.

## 3. USB UVC Streaming & Isochronous Transfers
- **Validate `UsbRequest.initialize`**: Isochronous endpoints are not universally supported across all Android vendor HALs in Java `UsbRequest`. Always check:
  ```kotlin
  val req = UsbRequest()
  if (!req.initialize(connection, endpoint)) { req.close(); null }
  ```
- **Dual Tracking Fallback**: Pair USB camera feeds with a background Camera2 fallback (`ImageReader` YUV420) so optical tracking continues smoothly while USB permissions or streams negotiate.

## 4. Hardware Connectivity & Wireless ADB
- **Single USB-C Port Management**: When glasses occupy the physical USB-C port, always inspect wireless debugging via mDNS (`adb -s adb-<SERIAL>._adb-tls-connect._tcp ...`).
- **Command Quoting**: In Zsh environments, quote all wildcards (`"*:E"`, `"/dev/bus/usb/*"`) to avoid local shell expansion errors.
