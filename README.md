# SpatialXRDesktop

Android Userspace Spatial Compositor, Native Vulkan 1.3 Stereo Swapchain, and OpenXR/Jetpack XR Runtime Shim.

**Target Hardware:**
- **Host Device:** Samsung Galaxy Fold (Snapdragon 8 Gen 3/4)
- **Glasses:** VITURE Luma Ultra 6DoF AR Glasses (USB-C DisplayPort Alternate Mode + USB HID IMU + Stereo UVC Cameras)

---

## Architecture Overview

```
SpatialXRDesktop/
├── app/
│   ├── src/main/
│   │   ├── java/com/spatial/xr/
│   │   │   ├── MainActivity.kt                      // Entry point, permission orchestrator
│   │   │   ├── display/
│   │   │   │   ├── ExternalDisplayManager.kt        // USB-C DP Alt Mode & DeX bypass
│   │   │   │   └── PresentationManager.kt           // Android Presentation lifecycle
│   │   │   ├── window/
│   │   │   │   ├── SpatialWindowManager.kt          // 3D window transform, follower & hierarchy
│   │   │   │   ├── VirtualDisplayHost.kt            // VirtualDisplay instance pool
│   │   │   │   ├── WindowInputInjector.kt           // Ray-to-UV touch event injection
│   │   │   │   └── AppLauncherRepository.kt         // PackageManager query & curved dock model
│   │   │   ├── ime/
│   │   │   │   ├── SpatialInputMethodService.kt     // Custom system IME service
│   │   │   │   └── DictationEngine.kt               // On-device STT -> InputConnection
│   │   │   ├── ai/
│   │   │   │   ├── GeminiSpatialAgent.kt            // Gemini 2.0 Flash client & function calling
│   │   │   │   ├── VisualQAPipeline.kt              // UVC frame grabber + multimodal prompt
│   │   │   │   └── SpatialTTSManager.kt             // Audio TTS to glasses temple speakers
│   │   │   └── bridge/
│   │   │       └── NativeBridge.kt                  // JNI interface to native C++ core
│   │   ├── cpp/
│   │   │   ├── graphics/
│   │   │   │   ├── vulkan_compositor.hpp/.cpp       // Vulkan 1.3 dual-eye swapchain (3840x1080)
│   │   │   │   ├── multiview_pipeline.hpp/.cpp      // VK_KHR_multiview stereo rendering
│   │   │   │   ├── atw_reprojector.hpp/.cpp         // 120Hz Asynchronous TimeWarp pipeline
│   │   │   │   └── quad_renderer.hpp/.cpp           // 3D curved/planar window mesh & raycast
│   │   │   ├── hardware/
│   │   │   │   ├── viture_device.hpp/.cpp           // USB HID control & 3D SBS toggle
│   │   │   │   ├── imu_worker.hpp/.cpp              // 1000Hz HID reader thread
│   │   │   │   └── uvc_streamer.hpp/.cpp            // Linux V4L2 stereo frame capture
│   │   │   ├── tracking/
│   │   │   │   ├── vio_pose_fusion.hpp/.cpp         // 6DoF sensor fusion (IMU + Optical ES-EKF)
│   │   │   │   ├── pose_predictor.hpp/.cpp          // Latency compensation & extrapolation
│   │   │   │   └── hand_tracking_qnn.hpp/.cpp       // 21-point 3D joints & gesture recognizer
│   │   │   └── openxr/
│   │   │       └── openxr_shim.hpp/.cpp             // OpenXR 1.1 runtime dispatch tables
│   │   └── assets/
│   │       └── shaders/                             // GLSL Vulkan multiview shaders
```

---

## Features

1. **Hardware & Display Pipeline (Phase 1):**
   - External display detection (`Display.FLAG_PRESENTATION`).
   - Samsung DeX bypass automation.
   - Viture 3D Side-by-Side USB vendor control packet switching.
   - Native Vulkan 1.3 stereo compositor rendering at $3840 \times 1080$ ($1920 \times 1080$ per eye).

2. **6DoF Sensor Fusion & Late-Latch Prediction (Phase 2):**
   - 1000Hz USB HID IMU reading thread with real-time thread scheduling.
   - FAST corner feature detector and Error-State Extended Kalman Filter (ES-EKF).
   - 128-entry lock-free circular ring buffer with rotational forward extrapolation:
     $$Q_{pred} = Q_{curr} \otimes \exp\left(\frac{1}{2} \vec{\omega} \Delta t\right)$$

3. **Hand Tracking & Spatial Gestures (Phase 3):**
   - 21-joint 3D hand skeleton tracker.
   - Index pointer ray calculation.
   - Pinch gesture classifier with hysteresis ($< 20\text{ mm}$ down, $> 28\text{ mm}$ release).
   - Two-hand pinch scale detector.
   - Look-at-palm detector using normal-gaze alignment ($\vec{N}\cdot\vec{V} < -0.75$).

4. **Multi-Tasking Spatial Window Manager (Phase 4):**
   - Multi-instance Android `VirtualDisplay` pool.
   - Floating follower algorithm ($> 18^\circ$ gaze deviation smoothly slerps to $1.2\text{ m}$ distance).
   - 4-corner interactive 3D resize handles with dynamic display reflow.
   - Raycast hit-testing and normalized UV-to-pixel `MotionEvent` injection.

5. **App Launcher & Fullscreen Cinema (Phase 5):**
   - Curved 3D horizontal dock at chest level ($R = 0.8\text{ m}, Y = -0.2\text{ m}$).
   - Fullscreen cylindrical $90^\circ$ FOV cinema screen ($R = 2.0\text{ m}$) with background window dimming ($0.15$ opacity).
   - Air-swipe rapid task switcher ($|v_x| > 1.2\text{ m/s}$) through MRU window stack.

6. **Spatial IME & Dictation (Phase 6):**
   - Floating mic pill widget.
   - Streaming speech tokens with sentence boundary detection and $1.5\text{ s}$ pause auto-commit.
   - 3D ray-interactive QWERTY keyboard toggle.

7. **Gemini Spatial Assistant (Phase 7):**
   - Voice hotword ("Hey Gemini"), physical frame button, and 3-finger air-tap triggers.
   - Function calling tool router (`launch_app`, `organize_workspace`, `analyze_screen_or_scene`).
   - "Look & Ask" multimodal vision QA pipeline packaging camera frames to Gemini 2.0 Flash.
   - Floating 3D text overlay card and audio TTS to glasses temple speakers.

8. **Asynchronous TimeWarp (ATW) (Phase 8):**
   - Real-time $120\text{ Hz}$ reprojection thread.
   - Reprojection matrix computation:
     $$M_{warp} = P_{present} \cdot P_{render}^{-1}$$
   - Motion-to-photon latency under $18\text{ ms}$ (average $12.5\text{ ms}$).
   - OpenXR 1.1 runtime shim.

---

## Building & Testing

### Build Requirements
- Android SDK Platform 35 & Build-Tools 35.0.0
- Android NDK 30 (or 27+)
- JDK 21 (LTS)
- CMake 3.22.1+
- Clang++ C++20 compiler

### Run Unit Tests
```bash
# Android JVM Unit Tests
./gradlew test

# Native XR Core C++ Tests
clang++ -std=c++20 -DSPATIAL_COMBINED_TEST_RUNNER=1 \
  -Iapp/src/main/cpp \
  -Iapp/src/main/cpp/hardware \
  -Iapp/src/main/cpp/graphics \
  -Iapp/src/main/cpp/tracking \
  -Iapp/src/main/cpp/openxr \
  app/src/main/cpp/hardware/viture_device.cpp \
  app/src/main/cpp/hardware/imu_worker.cpp \
  app/src/main/cpp/hardware/uvc_streamer.cpp \
  app/src/main/cpp/graphics/vulkan_compositor.cpp \
  app/src/main/cpp/graphics/multiview_pipeline.cpp \
  app/src/main/cpp/graphics/quad_renderer.cpp \
  app/src/main/cpp/graphics/atw_reprojector.cpp \
  app/src/main/cpp/tracking/pose_predictor.cpp \
  app/src/main/cpp/tracking/vio_pose_fusion.cpp \
  app/src/main/cpp/tracking/hand_tracking_qnn.cpp \
  app/src/main/cpp/openxr/openxr_shim.cpp \
  tests/native/test_phase1.cpp \
  tests/native/test_phase2.cpp \
  tests/native/test_phase3.cpp \
  tests/native/test_phase8.cpp \
  tests/native/main_test_runner.cpp \
  -o run_tests && ./run_tests
```

### Build APK
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

---

## License
Apache-2.0
