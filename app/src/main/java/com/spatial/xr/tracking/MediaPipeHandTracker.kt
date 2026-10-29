package com.spatial.xr.tracking

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.spatial.xr.bridge.NativeBridge
import java.util.concurrent.atomic.AtomicBoolean

class MediaPipeHandTracker(private val context: Context) {

    companion object {
        private const val TAG = "MediaPipeHandTracker"
        private const val MODEL_PATH = "models/hand_landmarker.task"
    }

    private var handLandmarker: HandLandmarker? = null
    private val isInitialized = AtomicBoolean(false)
    private var lastTimestampMs = 0L

    // Reusable buffers for grayscale to ARGB conversion
    private var cachedBitmap: Bitmap? = null
    private var pixelBuffer: IntArray? = null

    private var leftSmoothedDepth = -0.50f
    private var rightSmoothedDepth = -0.50f
    private var leftSmoothedSpan = 0f
    private var rightSmoothedSpan = 0f
    private var leftSmoothedNormX = 0.5f
    private var leftSmoothedNormY = 0.5f
    private var rightSmoothedNormX = 0.5f
    private var rightSmoothedNormY = 0.5f
    private val leftSmoothedJoints = FloatArray(21 * 3)
    private val rightSmoothedJoints = FloatArray(21 * 3)
    private var wasLeftTracked = false
    private var wasRightTracked = false

    init {
        initializeLandmarker()
    }

    private fun initializeLandmarker() {
        try {
            // First attempt with GPU delegate, fallback to CPU if needed
            val baseOptions = try {
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_PATH)
                    .setDelegate(Delegate.GPU)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate unavailable, using CPU: ${e.message}")
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_PATH)
                    .setDelegate(Delegate.CPU)
                    .build()
            }

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.30f)
                .setMinTrackingConfidence(0.30f)
                .setMinHandPresenceConfidence(0.30f)
                .setResultListener { result, mpImage ->
                    onHandLandmarkerResult(result, mpImage)
                }
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe Landmarker error: ${error.message}")
                }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            isInitialized.set(true)
            Log.i(TAG, "MediaPipe HandLandmarker initialized successfully with model: $MODEL_PATH")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe HandLandmarker: ${e.message}", e)
        }
    }

    fun isReady(): Boolean = isInitialized.get() && handLandmarker != null

    private var frameDumpCount = 0

    /**
     * Process a grayscale Y-plane camera frame (e.g. from Viture B&W tracking camera).
     */
    fun processGrayscaleFrame(width: Int, height: Int, yPlane: ByteArray) {
        if (!isReady()) return

        var timestampMs = SystemClock.uptimeMillis()
        if (timestampMs <= lastTimestampMs) {
            timestampMs = lastTimestampMs + 1
        }
        lastTimestampMs = timestampMs

        try {
            var bitmap = cachedBitmap
            var pixels = pixelBuffer
            if (bitmap == null || bitmap.width != width || bitmap.height != height) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                cachedBitmap = bitmap
                pixels = IntArray(width * height)
                pixelBuffer = pixels
            }

            val totalPixels = width * height
            val currentPixels = pixels ?: IntArray(totalPixels).also {
                pixels = it
                pixelBuffer = it
            }
            val limit = minOf(totalPixels, yPlane.size)

            // Fast 256-bin histogram (subsample step 4 for high speed)
            val hist = IntArray(256)
            var sampleCount = 0
            for (i in 0 until limit step 4) {
                hist[yPlane[i].toInt() and 0xFF]++
                sampleCount++
            }

            // Percentile stretch (2nd to 98th percentile) to reject single hot/dead pixels
            val lowThreshold = (sampleCount * 0.02f).toInt()
            val highThreshold = (sampleCount * 0.98f).toInt()

            var acc = 0
            var pLow = 0
            for (i in 0 until 256) {
                acc += hist[i]
                if (acc >= lowThreshold) {
                    pLow = i
                    break
                }
            }
            acc = 0
            var pHigh = 255
            for (i in 255 downTo 0) {
                acc += hist[i]
                if (acc >= (sampleCount - highThreshold)) {
                    pHigh = i
                    break
                }
            }

            val range = maxOf(1, pHigh - pLow)
            // Precompute 256-entry lookup table for fast conversion
            val lut = IntArray(256)
            for (i in 0 until 256) {
                val scaled = ((i - pLow) * 255 / range).coerceIn(0, 255)
                lut[i] = -0x1000000 or (scaled shl 16) or (scaled shl 8) or scaled
            }

            // Convert grayscale Y values to ARGB pixels using LUT
            for (i in 0 until limit) {
                currentPixels[i] = lut[yPlane[i].toInt() and 0xFF]
            }

            bitmap.setPixels(currentPixels, 0, width, 0, 0, width, height)

            // Periodic snapshot to disk for real-time inspection (~every 3 seconds)
            if (++frameDumpCount % 75 == 0) {
                try {
                    val file = java.io.File("/sdcard/Download/carina_frame_latest.png")
                    val tmpFile = java.io.File("/sdcard/Download/carina_frame_latest.tmp")
                    java.io.FileOutputStream(tmpFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    tmpFile.renameTo(file)
                } catch (_: Exception) {}
            }

            val mpImage: MPImage = BitmapImageBuilder(bitmap).build()

            handLandmarker?.detectAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            Log.w(TAG, "Frame processing error: ${e.message}")
        }
    }

    /**
     * Process an existing RGB/ARGB Bitmap (e.g. from camera or test feed).
     */
    fun processBitmap(bitmap: Bitmap) {
        if (!isReady()) return

        var timestampMs = SystemClock.uptimeMillis()
        if (timestampMs <= lastTimestampMs) {
            timestampMs = lastTimestampMs + 1
        }
        lastTimestampMs = timestampMs

        try {
            val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
            handLandmarker?.detectAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            Log.w(TAG, "Bitmap processing error: ${e.message}")
        }
    }

    private var detectionCount = 0
    private var totalResultFrames = 0

    private data class HandCandidate(
        val handIndex: Int,
        val centerX: Float,
        val rawCategoryIsLeft: Boolean,
        val confidence: Float
    )

    private fun onHandLandmarkerResult(result: HandLandmarkerResult, mpImage: MPImage) {
        val landmarksList = result.landmarks()
        val worldLandmarksList = result.worldLandmarks()
        val handednesses = result.handednesses()

        totalResultFrames++
        if (totalResultFrames % 60 == 1) {
            Log.i(TAG, "MediaPipe inference callback #$totalResultFrames: detected=${landmarksList.size} hands")
        }

        if (landmarksList.isNotEmpty()) {
            detectionCount++
            if (detectionCount == 1 || detectionCount % 30 == 0) {
                Log.i(TAG, "MediaPipe detected ${landmarksList.size} hand(s) #$detectionCount")
            }
        }

        val numHands = landmarksList.size
        if (numHands == 0) {
            if (wasLeftTracked) {
                NativeBridge.nativeResetHand(isLeft = true)
                leftSmoothedDepth = -0.50f
                leftSmoothedSpan = 0f
                wasLeftTracked = false
            }
            if (wasRightTracked) {
                NativeBridge.nativeResetHand(isLeft = false)
                rightSmoothedDepth = -0.50f
                rightSmoothedSpan = 0f
                wasRightTracked = false
            }
            return
        }

        // Build candidates with camera normalized horizontal center
        val candidates = ArrayList<HandCandidate>(numHands)
        for (i in 0 until numHands) {
            val landmarks = landmarksList[i]
            val wrist = landmarks[0]
            val middleMcp = landmarks.getOrNull(9) ?: landmarks.getOrNull(5) ?: wrist
            val centerX = (wrist.x() + middleMcp.x()) * 0.5f

            val handednessCategory = handednesses.getOrNull(i)?.firstOrNull()
            val categoryName = handednessCategory?.categoryName() ?: "Right"
            val rawIsLeft = categoryName.equals("Left", ignoreCase = true)
            val score = handednessCategory?.score() ?: 0.9f

            candidates.add(HandCandidate(i, centerX, rawIsLeft, score))
        }

        var leftCandidate: HandCandidate? = null
        var rightCandidate: HandCandidate? = null

        if (numHands == 1) {
            val cand = candidates[0]
            // Spatial assignment based on camera FOV position and temporal continuity
            val assignIsLeft = when {
                cand.centerX < 0.42f -> true  // Strongly in left half of user view
                cand.centerX > 0.58f -> false // Strongly in right half of user view
                wasLeftTracked && !wasRightTracked -> true // Persistent left hand
                wasRightTracked && !wasLeftTracked -> false // Persistent right hand
                else -> cand.rawCategoryIsLeft
            }
            if (assignIsLeft) {
                leftCandidate = cand
            } else {
                rightCandidate = cand
            }
        } else {
            // Two or more hands in view: sort candidates strictly by horizontal coordinate
            // In egocentric AR glasses, left hand is on user's left (smaller X), right on user's right (larger X)
            candidates.sortBy { it.centerX }
            val candLeft = candidates[0]
            val candRight = candidates[1]

            // Temporal continuity check for rare hand-crossing situations
            if (wasLeftTracked && wasRightTracked) {
                val costNormal = Math.abs(candLeft.centerX - leftSmoothedNormX) + Math.abs(candRight.centerX - rightSmoothedNormX)
                val costSwapped = Math.abs(candRight.centerX - leftSmoothedNormX) + Math.abs(candLeft.centerX - rightSmoothedNormX)
                if (costSwapped < costNormal * 0.55f) {
                    leftCandidate = candRight
                    rightCandidate = candLeft
                } else {
                    leftCandidate = candLeft
                    rightCandidate = candRight
                }
            } else {
                leftCandidate = candLeft
                rightCandidate = candRight
            }
        }

        // Process assigned Left hand strictly in left buffer
        if (leftCandidate != null) {
            val idx = leftCandidate.handIndex
            val landmarks = landmarksList[idx]
            val worldLandmarks = if (idx < worldLandmarksList.size) worldLandmarksList[idx] else null
            processSingleHand(isLeft = true, landmarks, worldLandmarks, leftCandidate.confidence)
        } else if (wasLeftTracked) {
            NativeBridge.nativeResetHand(isLeft = true)
            leftSmoothedDepth = -0.50f
            leftSmoothedSpan = 0f
            wasLeftTracked = false
        }

        // Process assigned Right hand strictly in right buffer
        if (rightCandidate != null) {
            val idx = rightCandidate.handIndex
            val landmarks = landmarksList[idx]
            val worldLandmarks = if (idx < worldLandmarksList.size) worldLandmarksList[idx] else null
            processSingleHand(isLeft = false, landmarks, worldLandmarks, rightCandidate.confidence)
        } else if (wasRightTracked) {
            NativeBridge.nativeResetHand(isLeft = false)
            rightSmoothedDepth = -0.50f
            rightSmoothedSpan = 0f
            wasRightTracked = false
        }
    }

    private fun processSingleHand(
        isLeft: Boolean,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        worldLandmarks: List<com.google.mediapipe.tasks.components.containers.Landmark>?,
        confidence: Float
    ) {
        val wrist = landmarks[0]
        val normX = wrist.x()
        val normY = wrist.y()

        // Dynamic optical depth estimation from palm span (wrist to middle MCP)
        val middleMcp = landmarks.getOrNull(9) ?: landmarks.getOrNull(5) ?: wrist
        val spanDx = middleMcp.x() - wrist.x()
        val spanDy = middleMcp.y() - wrist.y()
        val rawPalmSpan = Math.sqrt((spanDx * spanDx + spanDy * spanDy).toDouble()).toFloat().coerceIn(0.04f, 0.45f)

        // Denoise palm span against single-pixel camera flutter
        val prevSpan = if (isLeft) leftSmoothedSpan else rightSmoothedSpan
        val filteredSpan = if (prevSpan > 0.01f) {
            val spanDiff = Math.abs(rawPalmSpan - prevSpan)
            if (spanDiff < 0.002f) prevSpan else prevSpan * 0.80f + rawPalmSpan * 0.20f
        } else {
            rawPalmSpan
        }
        if (isLeft) leftSmoothedSpan = filteredSpan else rightSmoothedSpan = filteredSpan

        // Pinhole camera depth: smaller palm span in image -> deeper in front of camera
        val rawDepth = -(0.0825f / filteredSpan).coerceIn(-1.30f, -0.25f)
        val prevDepth = if (isLeft) leftSmoothedDepth else rightSmoothedDepth
        val depthDiff = Math.abs(rawDepth - prevDepth)
        // Deadband: small depth fluctuations (< 1.5 cm) are suppressed to eliminate depth chatter
        val targetDepth = if (depthDiff < 0.015f) prevDepth else rawDepth
        val smoothedDepth = prevDepth * 0.75f + targetDepth * 0.25f
        if (isLeft) leftSmoothedDepth = smoothedDepth else rightSmoothedDepth = smoothedDepth
        val baseZ = smoothedDepth

        // Smooth 2D wrist normalized coordinates to stabilize base spatial origin
        val prevNormX = if (isLeft) leftSmoothedNormX else rightSmoothedNormX
        val prevNormY = if (isLeft) leftSmoothedNormY else rightSmoothedNormY
        val nxDiff = Math.abs(normX - prevNormX)
        val nyDiff = Math.abs(normY - prevNormY)
        val smoothedNormX = if (nxDiff < 0.002f) prevNormX else prevNormX * 0.60f + normX * 0.40f
        val smoothedNormY = if (nyDiff < 0.002f) prevNormY else prevNormY * 0.60f + normY * 0.40f
        if (isLeft) {
            leftSmoothedNormX = smoothedNormX
            leftSmoothedNormY = smoothedNormY
        } else {
            rightSmoothedNormX = smoothedNormX
            rightSmoothedNormY = smoothedNormY
        }

        // Metric spatial X and Y scaled with true hand depth
        val absZ = Math.abs(baseZ)
        val baseSpatialX = (smoothedNormX - 0.5f) * absZ * 1.30f
        val baseSpatialY = (0.5f - smoothedNormY) * absZ * 1.00f

        val jointsArray = FloatArray(21 * 3)

        for (j in 0 until 21) {
            if (worldLandmarks != null && j < worldLandmarks.size) {
                val w = worldLandmarks[j]
                jointsArray[j * 3 + 0] = baseSpatialX + w.x()
                jointsArray[j * 3 + 1] = baseSpatialY - w.y() // Flip Y from image down to 3D up
                jointsArray[j * 3 + 2] = baseZ + w.z()
            } else {
                val l = landmarks[j]
                jointsArray[j * 3 + 0] = (l.x() - 0.5f) * absZ * 1.30f
                jointsArray[j * 3 + 1] = (0.5f - l.y()) * absZ * 1.00f
                jointsArray[j * 3 + 2] = baseZ + (l.z() * 0.15f)
            }
        }

        // Temporal velocity-adaptive smoothing across all 21 joint coordinates (toned down ~20% for faster responsiveness)
        val smoothedJoints = if (isLeft) leftSmoothedJoints else rightSmoothedJoints
        val wasTracked = if (isLeft) wasLeftTracked else wasRightTracked
        if (!wasTracked) {
            System.arraycopy(jointsArray, 0, smoothedJoints, 0, 63)
            if (isLeft) wasLeftTracked = true else wasRightTracked = true
        } else {
            for (k in 0 until 63) {
                val delta = jointsArray[k] - smoothedJoints[k]
                val absDelta = Math.abs(delta)
                if (absDelta > 0.20f) {
                    // Large jump (> 20cm): snap immediately, never drag across screen
                    smoothedJoints[k] = jointsArray[k]
                } else {
                    val alpha = when {
                        absDelta < 0.003f -> 0.22f // < 3mm: suppress detector jitter
                        absDelta < 0.030f -> 0.58f // 3-30mm: fluid interactive tracking
                        absDelta < 0.080f -> 0.85f // 30-80mm: responsive follow
                        else -> 0.98f              // fast motion
                    }
                    smoothedJoints[k] += delta * alpha
                    jointsArray[k] = smoothedJoints[k]
                }
            }
        }

        NativeBridge.nativeUpdateHandJoints(isLeft, jointsArray, confidence)
    }

    fun close() {
        isInitialized.set(false)
        try {
            handLandmarker?.close()
        } catch (e: Exception) {
            // Ignore close exception
        }
        handLandmarker = null
        cachedBitmap?.recycle()
        cachedBitmap = null
        pixelBuffer = null
    }
}
