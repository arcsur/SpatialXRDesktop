#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <memory>
#include "graphics/vulkan_compositor.hpp"
#include "hardware/viture_device.hpp"
#include "tracking/hand_tracking_qnn.hpp"

#define LOG_TAG "SpatialXR_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::unique_ptr<spatial::graphics::VulkanCompositor> gCompositor;
    std::unique_ptr<spatial::hardware::VitureDevice> gVitureDevice;
    std::unique_ptr<spatial::tracking::HandTrackingQnn> gHandTracker;
    ANativeWindow* gNativeWindow = nullptr;

    spatial::tracking::HandTrackingQnn* getOrCreateHandTracker() {
        if (!gHandTracker) {
            gHandTracker = std::make_unique<spatial::tracking::HandTrackingQnn>();
            gHandTracker->initialize();
            LOGI("HandTrackingQnn initialized");
        }
        return gHandTracker.get();
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeInitCompositor(JNIEnv* env, jobject /*thiz*/, jobject surface) {
    if (!surface) {
        LOGE("nativeInitCompositor: surface is null");
        return JNI_FALSE;
    }

    if (gNativeWindow) {
        ANativeWindow_release(gNativeWindow);
        gNativeWindow = nullptr;
    }

    gNativeWindow = ANativeWindow_fromSurface(env, surface);
    if (!gNativeWindow) {
        LOGE("nativeInitCompositor: ANativeWindow_fromSurface failed");
        return JNI_FALSE;
    }

    gCompositor = std::make_unique<spatial::graphics::VulkanCompositor>();
    if (!gCompositor->initialize(gNativeWindow)) {
        LOGE("nativeInitCompositor: Vulkan compositor init failed");
        return JNI_FALSE;
    }

    if (!gVitureDevice) {
        gVitureDevice = std::make_unique<spatial::hardware::VitureDevice>();
        gVitureDevice->initialize();
    }

    LOGI("Compositor initialized successfully at %dx%d",
         spatial::graphics::StereoFrameConfig::TOTAL_WIDTH,
         spatial::graphics::StereoFrameConfig::TOTAL_HEIGHT);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeShutdownCompositor(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (gCompositor) {
        gCompositor->shutdown();
        gCompositor.reset();
    }
    if (gVitureDevice) {
        gVitureDevice->set3DMode(spatial::hardware::Display3DMode::Mode2D);
        gVitureDevice->disconnect();
        gVitureDevice.reset();
    }
    if (gHandTracker) {
        gHandTracker.reset();
    }
    if (gNativeWindow) {
        ANativeWindow_release(gNativeWindow);
        gNativeWindow = nullptr;
    }
    LOGI("Compositor and HandTracker shut down");
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeSet3DMode(JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    if (!gVitureDevice) {
        gVitureDevice = std::make_unique<spatial::hardware::VitureDevice>();
        gVitureDevice->initialize();
    }
    auto mode = enabled ? spatial::hardware::Display3DMode::Mode3DSideBySide
                        : spatial::hardware::Display3DMode::Mode2D;
    return gVitureDevice->set3DMode(mode) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeRenderCalibrationPattern(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (gCompositor) {
        gCompositor->renderCalibrationFrame();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeStartImuWorker(JNIEnv* /*env*/, jobject /*thiz*/, jint /*usbFd*/) {
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeStopImuWorker(JNIEnv* /*env*/, jobject /*thiz*/) {
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeGetLatestPose(JNIEnv* env, jobject /*thiz*/, jfloatArray outPoseArray) {
    if (!outPoseArray) return JNI_FALSE;
    jfloat pose[7] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f}; // Identity quaternion qw=1
    env->SetFloatArrayRegion(outPoseArray, 0, 7, pose);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativePredictPose(JNIEnv* env, jobject /*thiz*/, jlong /*predictedTimestampNs*/, jfloatArray outPoseArray) {
    if (!outPoseArray) return JNI_FALSE;
    jfloat pose[7] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    env->SetFloatArrayRegion(outPoseArray, 0, 7, pose);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeProcessCameraFrame(
    JNIEnv* env, jobject /*thiz*/, jint width, jint height, jbyteArray yPlane, jbyteArray uvPlane
) {
    if (!yPlane || width <= 0 || height <= 0) return JNI_FALSE;
    auto* tracker = getOrCreateHandTracker();

    jbyte* yBytes = env->GetByteArrayElements(yPlane, nullptr);
    jbyte* uvBytes = uvPlane ? env->GetByteArrayElements(uvPlane, nullptr) : nullptr;

    bool result = tracker->processCameraFrame(
        width,
        height,
        reinterpret_cast<const uint8_t*>(yBytes),
        uvBytes ? reinterpret_cast<const uint8_t*>(uvBytes) : nullptr
    );

    env->ReleaseByteArrayElements(yPlane, yBytes, JNI_ABORT);
    if (uvPlane && uvBytes) {
        env->ReleaseByteArrayElements(uvPlane, uvBytes, JNI_ABORT);
    }

    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeGetHandPointers(
    JNIEnv* env, jobject /*thiz*/, jfloatArray leftRay, jfloatArray rightRay
) {
    auto* tracker = getOrCreateHandTracker();
    int mask = 0;

    const auto& leftHand = tracker->getHand(true);
    if (leftHand.isTracked && leftRay) {
        auto ray = spatial::tracking::HandTrackingQnn::computeIndexPointerRay(leftHand);
        jfloat rayData[6] = {
            ray.origin[0], ray.origin[1], ray.origin[2],
            ray.direction[0], ray.direction[1], ray.direction[2]
        };
        env->SetFloatArrayRegion(leftRay, 0, 6, rayData);
        mask |= 1;
    }

    const auto& rightHand = tracker->getHand(false);
    if (rightHand.isTracked && rightRay) {
        auto ray = spatial::tracking::HandTrackingQnn::computeIndexPointerRay(rightHand);
        jfloat rayData[6] = {
            ray.origin[0], ray.origin[1], ray.origin[2],
            ray.direction[0], ray.direction[1], ray.direction[2]
        };
        env->SetFloatArrayRegion(rightRay, 0, 6, rayData);
        mask |= 2;
    }

    return mask;
}

JNIEXPORT jint JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeGetPinchState(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto* tracker = getOrCreateHandTracker();
    int mask = 0;
    if (tracker->isPinching(true)) mask |= 1;
    if (tracker->isPinching(false)) mask |= 2;
    return mask;
}

JNIEXPORT jint JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeGetPinchPositions(
    JNIEnv* env, jobject /*thiz*/, jfloatArray leftPos, jfloatArray rightPos
) {
    auto* tracker = getOrCreateHandTracker();
    int mask = 0;

    const auto& leftHand = tracker->getHand(true);
    if (leftHand.isTracked && leftPos) {
        const auto& tip = leftHand.joints[spatial::tracking::INDEX_FINGER_TIP];
        jfloat pos[3] = {tip.x, tip.y, tip.z};
        env->SetFloatArrayRegion(leftPos, 0, 3, pos);
        mask |= 1;
    }

    const auto& rightHand = tracker->getHand(false);
    if (rightHand.isTracked && rightPos) {
        const auto& tip = rightHand.joints[spatial::tracking::INDEX_FINGER_TIP];
        jfloat pos[3] = {tip.x, tip.y, tip.z};
        env->SetFloatArrayRegion(rightPos, 0, 3, pos);
        mask |= 2;
    }

    return mask;
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeIsLookingAtPalm(JNIEnv* /*env*/, jobject /*thiz*/, jboolean isLeft) {
    auto* tracker = getOrCreateHandTracker();
    const auto& hand = tracker->getHand(isLeft);
    if (!hand.isTracked) return JNI_FALSE;
    return spatial::tracking::HandTrackingQnn::isLookingAtPalm(hand) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeGetTwoHandTransform(
    JNIEnv* env, jobject /*thiz*/,
    jfloatArray initLeftPos, jfloatArray initRightPos,
    jfloatArray outTransform
) {
    if (!initLeftPos || !initRightPos || !outTransform) return JNI_FALSE;
    auto* tracker = getOrCreateHandTracker();
    const auto& leftHand = tracker->getHand(true);
    const auto& rightHand = tracker->getHand(false);
    if (!leftHand.isTracked || !rightHand.isTracked) return JNI_FALSE;

    jfloat iL[3], iR[3];
    env->GetFloatArrayRegion(initLeftPos, 0, 3, iL);
    env->GetFloatArrayRegion(initRightPos, 0, 3, iR);

    const auto& cL = leftHand.joints[spatial::tracking::INDEX_FINGER_TIP];
    const auto& cR = rightHand.joints[spatial::tracking::INDEX_FINGER_TIP];

    std::array<float, 3> initH1 = {iL[0], iL[1], iL[2]};
    std::array<float, 3> initH2 = {iR[0], iR[1], iR[2]};
    std::array<float, 3> currH1 = {cL.x, cL.y, cL.z};
    std::array<float, 3> currH2 = {cR.x, cR.y, cR.z};

    spatial::tracking::HandTrackingQnn::TwoHandTransform tf;
    if (!spatial::tracking::HandTrackingQnn::computeTwoHandTransform(initH1, initH2, currH1, currH2, tf)) {
        return JNI_FALSE;
    }

    // outTransform: [scaleFactor, tx, ty, tz, yawDeltaRadians]
    jfloat res[5] = {
        tf.scaleFactor,
        tf.translationDelta[0],
        tf.translationDelta[1],
        tf.translationDelta[2],
        tf.yawDeltaRadians
    };
    env->SetFloatArrayRegion(outTransform, 0, 5, res);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeStartAtwThread(JNIEnv* /*env*/, jobject /*thiz*/) {
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeStopAtwThread(JNIEnv* /*env*/, jobject /*thiz*/) {
}

JNIEXPORT void JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeSubmitEyeFrame(JNIEnv* /*env*/, jobject /*thiz*/, jint /*eyeIndex*/, jint /*textureId*/, jlong /*poseTimestampNs*/) {
}

} // extern "C"
