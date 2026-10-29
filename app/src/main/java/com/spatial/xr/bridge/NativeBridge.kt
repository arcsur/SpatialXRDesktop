package com.spatial.xr.bridge

import android.view.Surface

object NativeBridge {

    private var isLoaded = false

    init {
        try {
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
    external fun nativeRenderCalibrationPattern()

    // Phase 2: 6DoF Pose & Sensor Fusion
    external fun nativeStartImuWorker(usbFd: Int): Boolean
    external fun nativeStopImuWorker()
    external fun nativeGetLatestPose(outPoseArray: FloatArray): Boolean // [x,y,z,qx,qy,qz,qw]
    external fun nativePredictPose(predictedTimestampNs: Long, outPoseArray: FloatArray): Boolean

    // Phase 3: Hand Tracking & Gestures
    external fun nativeProcessCameraFrame(width: Int, height: Int, yPlane: ByteArray, uvPlane: ByteArray?): Boolean
    external fun nativeGetHandPointers(outLeftRay: FloatArray, outRightRay: FloatArray): Int
    external fun nativeGetPinchState(): Int // Bit 0: Left, Bit 1: Right
    external fun nativeGetPinchPositions(outLeftPos: FloatArray, outRightPos: FloatArray): Int
    external fun nativeIsLookingAtPalm(isLeft: Boolean): Boolean
    external fun nativeGetTwoHandTransform(
        initLeftPos: FloatArray,
        initRightPos: FloatArray,
        outTransform: FloatArray
    ): Boolean

    // Phase 8: Asynchronous TimeWarp
    external fun nativeStartAtwThread(): Boolean
    external fun nativeStopAtwThread()
    external fun nativeSubmitEyeFrame(eyeIndex: Int, textureId: Int, poseTimestampNs: Long)
}
