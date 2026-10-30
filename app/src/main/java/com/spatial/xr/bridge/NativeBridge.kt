package com.spatial.xr.bridge

import android.view.Surface

object NativeBridge {

    private var isLoaded = false

    init {
        try {
            try { System.loadLibrary("cloud_protocol") } catch (_: Throwable) {}
            try { System.loadLibrary("carina_vio") } catch (_: Throwable) {}
            try { System.loadLibrary("glasses") } catch (_: Throwable) {}
            System.loadLibrary("spatial_xr")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            // Expected in pure JVM unit tests without native lib loaded
            isLoaded = false
        }
    }

    fun isNativeLoaded(): Boolean = isLoaded

    // Phase 1: Display & Compositor
    external fun nativeInitCompositor(surface: Surface): Boolean
    external fun nativeShutdownCompositor()
    external fun nativeSet3DMode(enabled: Boolean): Boolean
    external fun nativeSetElectrochromicFilm(enabled: Boolean): Boolean
    external fun nativeRenderCalibrationPattern()

    // Phase 2: 6DoF Pose & Sensor Fusion
    external fun nativeStartImuWorker(usbFd: Int): Boolean
    external fun nativeStopImuWorker()
    external fun nativeGetLatestPose(outPoseArray: FloatArray): Boolean // [x,y,z,qx,qy,qz,qw]
    external fun nativePredictPose(predictedTimestampNs: Long, outPoseArray: FloatArray): Boolean

    // Phase 3: Hand Tracking & Gestures
    external fun nativeProcessCameraFrame(width: Int, height: Int, yPlane: ByteArray, uvPlane: ByteArray?): Boolean
    external fun nativeUpdateHandJoints(isLeft: Boolean, jointCoords: FloatArray, confidence: Float): Boolean
    external fun nativeResetHand(isLeft: Boolean)
    external fun nativeGetHandTrackingDots(outLeftDots: FloatArray, outRightDots: FloatArray): Int
    external fun nativeGetHandPointers(outLeftRay: FloatArray, outRightRay: FloatArray): Int
    external fun nativeGetPinchState(): Int // Bit 0: Left, Bit 1: Right
    external fun nativeGetPinchPositions(outLeftPos: FloatArray, outRightPos: FloatArray): Int
    external fun nativeGetFistState(): Int // Bit 0: Left, Bit 1: Right
    external fun nativeGetOpenHandState(): Int // Bit 0: Left, Bit 1: Right
    external fun nativeGetHandPositions(outLeftPos: FloatArray, outRightPos: FloatArray): Int
    external fun nativeIsLookingAtPalm(isLeft: Boolean): Boolean
    external fun nativeIsThumbFacingViewpoint(isLeft: Boolean): Boolean
    external fun nativeDetectTwoHandClaspClose(primaryIsLeft: Boolean, maxDistanceMeters: Float = 0.14f): Boolean
    external fun nativeGetTwoHandTransform(
        initLeftPos: FloatArray,
        initRightPos: FloatArray,
        outTransform: FloatArray
    ): Boolean
    external fun nativeComputeStereoDepth(
        normWristX: Float, normWristY: Float,
        normMcpX: Float, normMcpY: Float
    ): Float

    // Phase 8: Asynchronous TimeWarp
    external fun nativeStartAtwThread(): Boolean
    external fun nativeStopAtwThread()
    external fun nativeSubmitEyeFrame(eyeIndex: Int, textureId: Int, poseTimestampNs: Long)

    // VITURE SDK Hardware & Stereo Camera
    external fun nativeVitureCreate(productId: Int, fd: Int): Boolean
    external fun nativeVitureStartCarina(): Boolean
    external fun nativeVitureStop(): Boolean
    external fun nativeVitureGetCarinaFrame(outLeftFrame: ByteArray): IntArray? // returns [width, height]
    external fun nativeVitureGetCameraVid(glassesPid: Int): Int
    external fun nativeVitureGetCameraPid(glassesPid: Int): Int
    external fun nativeVitureCameraCreate(vid: Int, pid: Int, fd: Int): Boolean
    external fun nativeVitureCameraStart(): Boolean
    external fun nativeVitureCameraStop(): Boolean
    external fun nativeVitureCameraGetFrame(): ByteArray?
}
