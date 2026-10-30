# Spatial XR Desktop - Development Rules & Hardware Guidelines

## 1. XR Display & Presentation Lifecycle
- **Never detach presentation displays on `onPause()`**: System dialogs (e.g., USB permissions, runtime dialogs) pause the activity. Calling `presentationManager.detachDisplay()` in `onPause()` causes screen blanking in the glasses. Only detach displays in `onDestroy()` or when `DisplayListener.onDisplayRemoved()` fires.
- **Maintain active SurfaceView rendering**: External XR displays require an active hardware canvas render thread (`lockHardwareCanvas()`) at 60 FPS. Idle presentation surfaces risk being marked as `mSleeping=true` by SurfaceFlinger.
- **Decouple Compositor Lifecycle from Hardware Streaming**: Display mode toggles (2D to 3D SBS) re-enumerate the presentation surface. `nativeShutdownCompositor()` must NEVER stop `gVitureHandle`, `CarinaDeviceProvider`, or camera stream threads; hardware handles belong strictly to the physical USB connection lifecycle.

## 2. Android 14+ (API 34) USB Host Security
- **Explicit PendingIntents for USB permissions**: When using `PendingIntent.FLAG_MUTABLE` with `UsbManager.requestPermission()`, always set the explicit package:
  ```kotlin
  val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
  val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
  ```
- **BroadcastReceiver registration**: When listening for system USB events (`ACTION_USB_DEVICE_ATTACHED`) on API 33+, use `Context.RECEIVER_EXPORTED`.
- **Filter Detach Events by Tracking PIDs**: VITURE glasses expose composite USB interfaces (e.g., microphone `0x1102`, tracking `0x1104`). Never check only `vendorId == VITURE_VENDOR_ID` on `ACTION_USB_DEVICE_DETACHED`. Always verify `device.productId in VITURE_PIDS` so audio re-enumeration does not terminate camera tracking.

## 3. USB Tracking Cameras & Exclusive Egocentric Streaming
- **Validate `UsbRequest.initialize`**: Isochronous endpoints are not universally supported across all Android vendor HALs in Java `UsbRequest`. Always check:
  ```kotlin
  val req = UsbRequest()
  if (!req.initialize(connection, endpoint)) { req.close(); null }
  ```
- **Exclusive Egocentric Glasses Streaming**: Glasses USB tracking cameras (e.g. VITURE Carina stereo) are the exclusive tracking input. Phone Camera2 (selfie/front) is completely removed as egocentric spatial tracking requires head-mounted cameras.

## 4. Hardware Connectivity & Wireless ADB
- **Single USB-C Port Management**: When glasses occupy the physical USB-C port, always inspect wireless debugging via mDNS (`adb -s adb-<SERIAL>._adb-tls-connect._tcp ...`).
- **Command Quoting**: In Zsh environments, quote all wildcards (`"*:E"`, `"/dev/bus/usb/*"`) to avoid local shell expansion errors.

## 5. Android 15 (API 35) 16 KB ELF Page Alignment
- **16 KB Segment Alignment Invariant**: Android 15 requires all native `.so` files to have 16 KB page alignment (`2**14`). Compile native code with `-Wl,-z,max-page-size=16384` and verify APKs with `check_elf_alignment.sh`.
- **ABI Target Restrictions**: When integrating prebuilt vendor binaries, enforce `ndk.abiFilters += listOf("arm64-v8a")` in `app/build.gradle.kts` to avoid unaligned or missing 32-bit slices.

## 6. Vision Tracking Exposure & Dynamic Range
- **Prioritize Hardware Auto-Exposure**: Enable camera hardware AEC (`xr_device_provider_set_auto_exposure_carina`) before falling back to manual exposure.
- **Percentile LUT Grayscale Normalization**: Never use naive min/max contrast stretching on wide-FOV camera frames; room lights or hot spots prevent expansion. Use a 256-bin histogram percentile stretch (2nd to 98th percentile) with a 256-entry lookup table (LUT) to guarantee full [0, 255] dynamic range for MediaPipe.

## 7. Egocentric Hand Tracking & Smoothing Invariants
- **Spatial Hand Disambiguation over Classifier Labels**: MediaPipe HandLandmarker labels ("Left"/"Right") are trained primarily on third-person/selfie views and frequently invert or flicker when hands are viewed dorsally from egocentric AR glasses. Never route tracking data directly by raw classifier label. Enforce horizontal spatial partitioning in camera FOV ($X_{\text{left}} < X_{\text{right}}$) and isolate temporal smoothing buffers strictly per hand so left and right tracking points cannot swap or cross-interpolate.
- **Egocentric Handedness Inversion & Visual Badging**: In unmirrored outward-facing AR cameras, MediaPipe's raw handedness category is inverted (MediaPipe "Right" corresponds to egocentric Left hand; MediaPipe "Left" corresponds to egocentric Right hand). When a single hand enters center FOV ($0.42 \le X \le 0.58$), invert raw handedness for fallback assignment. Present distinct visual badges ("L" in Cyan `#FF00E5FF` vs "R" in Vivid Amber `#FFFF9100`) on finger tracking points and HUD indicators to facilitate visual verification during development.
- **Discontinuity Snap Guards on Re-acquisition**: When hand tracking points teleport, swap, or re-enter the frame with a delta $> 20\text{cm}$ in metric space or $> 30\%$ across the presentation viewport, snap filters immediately rather than dragging low-pass interpolators across the workspace.
- **Angular & Scale Deadbands on Two-Hand Transforms**: Subtle depth noise ($< 1.5\text{cm}$) between hands causes rapid trigonometric yaw chatter when computing $\text{atan2}(\Delta Z, \Delta X)$. Apply a deadband threshold ($\sim 0.026\text{ rad} / 1.5^\circ$) to yaw updates and a $\sim 1.5\%$ deadband to two-hand scale to keep window orientations locked during stationary holds.
- **Responsive Pointer Follow vs. Jitter Suppression**: Maintain a high dynamic range in adaptive smoothing alphas. Heavy dampening ($\alpha \approx 0.20\text{--}0.30$) belongs strictly in stationary micro-jitter ranges ($< 3\text{mm}$ / $< 8\text{px}$). Interactive motion must use responsive alphas ($\alpha \ge 0.55\text{--}0.65$ on 60 FPS render thread, $\ge 0.80$ during pinch grabs) to prevent trailing pointer lag.
- **Fist vs. Pinch Gesture Arbitration**: In egocentric tracking, curling fingers into a tight fist brings the thumb tip into close proximity ($< 20\text{mm}$) with the index PIP knuckle, triggering false pinch detections. Fist detection ($\ge 3$ curled fingers with 0 extended) must strictly take precedence over and suppress active pinch states.

## 8. Dual-Camera Stereoscopic Depth Triangulation
- **Asymmetric Stereo Pipeline over Dual ML Inferences**: Off-the-shelf mobile hand landmarkers (e.g. MediaPipe) are strictly monocular models. Never duplicate full neural network inference across both stereo camera frames ($2\times$ compute cost, drops frame rates to $\sim 15\text{ FPS}$, and magnifies depth noise from independent 2D keypoint jitter). Run 2D landmark inference exclusively on the primary camera feed (Left) and resolve metric depth via fast 1D epipolar disparity correlation on the secondary feed (Right).
- **Zero-Copy Epipolar Disparity Search in C++**: Keep secondary camera frames resident in native C++ memory without JNI byte array allocations. Perform 1D horizontal Sum of Absolute Differences (SAD) with sub-pixel parabolic refinement across epipolar scanlines ($d \in [16, 115]\text{ px}$ for $[0.22\text{m}, 1.30\text{m}]$ reach) to triangulate physical metric depth ($Z = -\frac{f_{\text{px}} \cdot B}{d_{\text{sub}}}$).
- **Graceful Monocular Fallback Hierarchy**: Maintain single-camera palm span perspective depth ($Z \propto -1 / S_{\text{palm}}$) strictly as a graceful fallback when the secondary camera frame is obstructed or feature patches fall out of bounds.

## 9. Spatial Window Manipulation & Gesture Interactions
- **Fist-Grab 3D Repositioning State Machine**: Window repositioning via fist grab must enforce an open-hand prerequisite. The grab session triggers strictly when transitioning from an open hand positioned over the target window quad to a closed fist on the same hand. Re-entering FOV as a fist or sweeping a closed fist across a window must never initiate a grab.
- **Orientation Lock during Translation**: 3D fist grab translates windows freely in $X, Y, \text{and } Z$ dimensions while strictly locking the window orientation quaternion. Angular rotations are reserved exclusively for deliberate two-hand transforms.
- **Top-Edge Grab Hit Detection**: The visual top grab bar is removed. Fist-grab repositioning must track based strictly on aiming or placing the hand over the top edge of the window (`WindowInputInjector.isTopGrabBarHitWithSlack` with top ~16cm thickness and ~20cm slack), never the entire window quad. Grabbing the window center or bottom must be rejected to keep app content interactive for touch and laser pointer injection.
- **Two-Hand Clasp Window-Close State Machine**:
  - **Prerequisite**: Exactly one hand must be actively grabbing the top of a window in a closed fist.
  - **Clasp Contact**: Second hand closes on top of the first hand into a fist within metric proximity ($D \le 0.14\text{m}$).
  - **Thumb Orientation Alignment**: First hand must rotate along its radial axis so its closed thumb faces back toward the user viewpoint ($\mathbf{V}_{\text{thumb}} \cdot \mathbf{V}_{\text{user}} > 0.25$).
  - **400ms Confirmation Hold & Abort**: Enforce a continuous 400ms hold timer with a visual danger red frame and circular progress arc. Separating hands ($> 14\text{cm}$) or releasing either fist before 400ms immediately aborts confirmation, resetting progress to 0% with no side effects. Window removal and display session teardown trigger only upon reaching the 400ms threshold.

## 10. Headless JVM Testing & Architecture Invariants
- **Null-Safe Context Defaults for Pure JVM Testing**: `android.content.Context` is an abstract class in Android SDK stubs, not an interface (`Proxy.newProxyInstance` fails with `IllegalArgumentException`). Renderer, tracking, and manager classes must declare `context: Context? = null` with default parameter when context APIs are not actively invoked during rendering, allowing headless JVM unit tests to instantiate components directly without Robolectric or bytecode-instrumented mocks.



