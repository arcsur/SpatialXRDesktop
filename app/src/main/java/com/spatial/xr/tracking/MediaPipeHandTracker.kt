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

    private fun onHandLandmarkerResult(result: HandLandmarkerResult, mpImage: MPImage) {
        val landmarksList = result.landmarks()
        val worldLandmarksList = result.worldLandmarks()
        val handednesses = result.handednesses()

        var leftDetected = false
        var rightDetected = false

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

        for (handIndex in 0 until landmarksList.size) {
            val landmarks = landmarksList[handIndex]
            val worldLandmarks = if (handIndex < worldLandmarksList.size) worldLandmarksList[handIndex] else null
            val handednessCategory = handednesses.getOrNull(handIndex)?.firstOrNull()

            // In front-facing / ego camera: MediaPipe mirrors handedness label
            // 'Left' category in ego camera corresponds to user's left hand
            val categoryName = handednessCategory?.categoryName() ?: "Right"
            val isLeft = categoryName.equals("Left", ignoreCase = true)
            val confidence = handednessCategory?.score() ?: 0.9f

            if (isLeft) leftDetected = true else rightDetected = true

            // Hand root center in normalized camera view
            val wrist = landmarks[0]
            val normX = wrist.x()
            val normY = wrist.y()

            // Project 2D normalized camera ray to 3D metric camera space (50cm in front of eyes)
            val baseZ = -0.50f
            val baseSpatialX = (normX - 0.5f) * 0.65f
            val baseSpatialY = (0.5f - normY) * 0.50f

            val jointsArray = FloatArray(21 * 3)

            for (j in 0 until 21) {
                if (worldLandmarks != null && j < worldLandmarks.size) {
                    val w = worldLandmarks[j]
                    // World landmarks are in metric meters centered at the wrist
                    jointsArray[j * 3 + 0] = baseSpatialX + w.x()
                    jointsArray[j * 3 + 1] = baseSpatialY - w.y() // Flip Y from image down to 3D up
                    jointsArray[j * 3 + 2] = baseZ + w.z()
                } else {
                    val l = landmarks[j]
                    jointsArray[j * 3 + 0] = (l.x() - 0.5f) * 0.65f
                    jointsArray[j * 3 + 1] = (0.5f - l.y()) * 0.50f
                    jointsArray[j * 3 + 2] = baseZ + (l.z() * 0.15f)
                }
            }

            NativeBridge.nativeUpdateHandJoints(isLeft, jointsArray, confidence)
        }

        if (!leftDetected) {
            NativeBridge.nativeResetHand(isLeft = true)
        }
        if (!rightDetected) {
            NativeBridge.nativeResetHand(isLeft = false)
        }
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
