# Spatial XR Hand Tracking & Interaction Improvement Plan

## 1. Problem Diagnosis & Root Causes
- **Jumping Pointer & Jitter:** `HandTrackingQnn::processCameraFrame` uses a naive pixel-intensity threshold (`yVal > 60 && yVal < 240`) without an ML model (`assets/models/` contains 0-byte dummy files). Ambient lighting and phone camera motion cause random joint coordinates.
- **Apps Menu Flickering:** `isLookingAtPalm()` evaluates the normal of three fluctuating pseudo-joints against head gaze without debouncing. Threshold fluctuations randomly satisfy `< -0.75` every 1.5s, repeatedly triggering `toggleDock()`.
- **Wrong Camera Feed:** `VitureCameraStreamer.kt` streams the phone's front camera (`LENS_FACING_FRONT`) via Camera2. Its UVC reader reads single 3KB packets without full frame reassembly.
- **Single Pointer Flaw:** `SpatialStereoRenderer.kt` renders a single laser pointer from the bottom edge to one hand's cursor. When both hands pinch, it forces the pointer to `(0.5, 0.5)`.

## 2. API & Library Evaluation
- **VITURE Official SDK:**
  - Handles USB communication (`0x35CA`), 3DoF/6DoF IMU & VIO pose, display 3D SBS toggle, and raw UVC stream access.
  - Does **not** include an on-device 21-joint optical hand tracking engine for native Android apps (its hand gesture demo is restricted to Unity XR / Neckband OS).
  - Decision: Use VITURE protocols for device control, pose, and UVC stream acquisition; do not rely on it for hand landmarking.
- **Existing Gesture Tracking Libraries:**
  - Google **MediaPipe Tasks Vision - Hand Landmarker** (`com.google.mediapipe:tasks-vision`) is the optimal solution.
  - Detects 21 3D landmarks per hand for both hands simultaneously at 60 FPS via GPU/NPU delegate.
  - Feeds directly into existing native `HandSkeleton` structures (`Joint3D[21]`), pinch hysteresis math, and two-hand transforms.

## 3. VITURE Luma Ultra Camera Selection
- **Hardware Architecture:**
  - 2 Side Monochrome Cameras: Global shutter, ultra-wide FOV, high framerate (60-90 FPS), minimal motion blur.
  - 1 Center RGB Camera: Rolling shutter, color, narrower FOV (designed for video capture/passthrough).
- **Optimal Choice:** **Dual Side Monochrome Cameras**.
  - Global shutter prevents finger edge blur during rapid motion.
  - Wide FOV maintains hand lock when hands are raised or peripheral.
  - Direct grayscale input avoids YUV/RGB color conversion overhead.
- **Driver Action:** Enumerate UVC streaming interfaces on USB VID `0x35CA`, select the monochrome stereo stream descriptor (Y800/GREY or SBS format), and assemble full frames across UVC packets using SOF/EOF flags.

## 4. Interaction UX Redesign
- **Dual-Hand Tracking Dots:**
  - Replace the single laser beam.
  - Render dedicated visual dots for **Thumb Tip** and **Index/Forefinger Tip** on both left and right hands.
- **Pinch Feedback:**
  - Proximity glow/arc between thumb and forefinger as distance narrows from 40mm to 20mm.
  - Snap into a glowing pinch reticle when distance $< 20$mm.
- **Two-Hand 3D Manipulation (Iron Man Gesture):**
  - When both hands pinch, render a dynamic 3D connection beam and bounding box between the left and right pinch reticles showing live scale factor, 3D translation, and yaw rotation.
- **Debounced Palm Menu Toggle:**
  - Require stable palm gaze alignment held continuously for $\ge 350$ms before toggling the app dock.

## 5. Execution Steps & Verification Criteria
1. **Step 1: UVC Frame Reassembly & Camera Stream Selection**
   - Enumerate Viture UVC interfaces, target monochrome tracking stream, and assemble complete frame buffers from UVC packets.
   - *Verify:* Native frame processor receives intact 60 FPS grayscale frames; disable phone selfie camera fallback when glasses connected.
2. **Step 2: MediaPipe Hand Landmarker Integration**
   - Add `com.google.mediapipe:tasks-vision` dependency and configure `HandLandmarker` for 2 hands with GPU/NPU acceleration.
   - Pipe camera frames into MediaPipe; bridge 21 3D landmarks into native `HandSkeleton`.
   - *Verify:* High-confidence 21-joint skeleton tracking for both hands simultaneously; zero false-positive skin blobs.
3. **Step 3: Dual-Hand Visual Tracking Dots (Thumb & Forefinger)**
   - Update `SpatialStereoRenderer.kt` to track and draw independent dots for left and right thumb and index tips.
   - Add magnetic proximity glow and $< 20$mm pinch snap animation.
   - *Verify:* User sees distinct thumb and forefinger dots per hand moving smoothly without jitter.
4. **Step 4: Stabilized Two-Hand Manipulation & Palm Dock Toggle**
   - Connect both pinch dots with a 3D guideline during dual pinch to visualize window scaling and angling.
   - Add a 350ms continuous hold requirement to `isLookingAtPalm()` before calling `toggleDock()`.
   - *Verify:* Apps menu stops flickering; two-hand pinch smoothly scales and repositions 3D windows.
