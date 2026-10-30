#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <memory>
#include <vector>
#include <mutex>
#include <thread>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <cstring>

#include "graphics/vulkan_compositor.hpp"
#include "hardware/viture_device.hpp"
#include "tracking/hand_tracking_qnn.hpp"

#include "viture_version.h"
#include "viture_glasses_provider.h"
#include "viture_camera_provider.h"
#include "viture_device.h"
#include "viture_device_carina.h"
#include "viture_protocol_public.h"

#define LOG_TAG "SpatialXR_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::unique_ptr<spatial::graphics::VulkanCompositor> gCompositor;
    std::unique_ptr<spatial::hardware::VitureDevice> gVitureDevice;
    std::unique_ptr<spatial::tracking::HandTrackingQnn> gHandTracker;
    ANativeWindow* gNativeWindow = nullptr;

    // VITURE SDK State
    XRDeviceProviderHandle gVitureHandle = nullptr;
    XRCameraProviderHandle gVitureCameraHandle = nullptr;

    struct CarinaFrameState {
        std::mutex mtx;
        std::vector<uint8_t> left;
        std::vector<uint8_t> right;
        int width = 0;
        int height = 0;
        uint32_t sequence = 0;
        uint32_t lastRead = 0;
    };
    CarinaFrameState gCarinaFrame;

    struct CameraFrameState {
        std::mutex mtx;
        std::vector<uint8_t> data;
        uint32_t sequence = 0;
        uint32_t lastRead = 0;
    };
    CameraFrameState gRgbCameraFrame;

    struct CarinaPoseState {
        std::mutex mtx;
        float pose[7] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f}; // [px, py, pz, qw, qx, qy, qz]
        bool fresh = false;
    };
    CarinaPoseState gCarinaPose;

    std::atomic<bool> gCarinaPollRunning{false};
    std::thread gCarinaPollThread;

    spatial::tracking::HandTrackingQnn* getOrCreateHandTracker() {
        if (!gHandTracker) {
            gHandTracker = std::make_unique<spatial::tracking::HandTrackingQnn>();
            gHandTracker->initialize();
            LOGI("HandTrackingQnn initialized");
        }
        return gHandTracker.get();
    }

    void onCarinaCamera(char* left0, char* right0,
                        char* /*left1*/, char* /*right1*/,
                        double /*ts*/, int width, int height) {
        int size = width * height;
        if (size <= 0 || !left0 || !right0) return;

        {
            std::lock_guard<std::mutex> lk(gCarinaFrame.mtx);
            gCarinaFrame.left.assign(reinterpret_cast<uint8_t*>(left0),
                                    reinterpret_cast<uint8_t*>(left0) + size);
            gCarinaFrame.right.assign(reinterpret_cast<uint8_t*>(right0),
                                     reinterpret_cast<uint8_t*>(right0) + size);
            gCarinaFrame.width = width;
            gCarinaFrame.height = height;
            gCarinaFrame.sequence++;
        }
    }

    void onRgbCamera(const XRCameraFrame* frame, void* /*user_data*/) {
        if (!frame || !frame->data || frame->size == 0) return;
        std::lock_guard<std::mutex> lk(gRgbCameraFrame.mtx);
        gRgbCameraFrame.data.assign(frame->data, frame->data + frame->size);
        gRgbCameraFrame.sequence++;
    }

    void carinaPollWorker() {
        float pose[7] = {};
        while (gCarinaPollRunning) {
            int status = 1;
            if (gVitureHandle &&
                xr_device_provider_get_gl_pose_carina(gVitureHandle, pose, 0.0, &status) == VITURE_GLASSES_SUCCESS) {
                std::lock_guard<std::mutex> lk(gCarinaPose.mtx);
                memcpy(gCarinaPose.pose, pose, sizeof(pose));
                gCarinaPose.fresh = true;
            }
            std::this_thread::sleep_for(std::chrono::microseconds(8333)); // ~120 Hz
        }
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
    if (gNativeWindow) {
        ANativeWindow_release(gNativeWindow);
        gNativeWindow = nullptr;
    }
    LOGI("Compositor surface shut down");
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeSet3DMode(JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    if (gVitureHandle) {
        xr_device_provider_switch_dimension(gVitureHandle, enabled ? 1 : 0);
        xr_device_provider_set_display_mode(
            gVitureHandle,
            enabled ? VITURE_DISPLAY_MODE_3840_1080_60HZ : VITURE_DISPLAY_MODE_1920_1080_60HZ
        );
    }
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
    std::lock_guard<std::mutex> lk(gCarinaPose.mtx);
    env->SetFloatArrayRegion(outPoseArray, 0, 7, gCarinaPose.pose);
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

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeUpdateHandJoints(
    JNIEnv* env, jobject /*thiz*/, jboolean isLeft, jfloatArray jointsArray, jfloat confidence
) {
    if (!jointsArray) return JNI_FALSE;
    jsize len = env->GetArrayLength(jointsArray);
    if (len < spatial::tracking::JOINT_COUNT * 3) return JNI_FALSE;

    auto* tracker = getOrCreateHandTracker();
    jfloat* rawJoints = env->GetFloatArrayElements(jointsArray, nullptr);

    std::array<spatial::tracking::Joint3D, spatial::tracking::JOINT_COUNT> joints{};
    for (size_t i = 0; i < spatial::tracking::JOINT_COUNT; ++i) {
        joints[i].x = rawJoints[i * 3 + 0];
        joints[i].y = rawJoints[i * 3 + 1];
        joints[i].z = rawJoints[i * 3 + 2];
        joints[i].confidence = confidence;
    }

    env->ReleaseFloatArrayElements(jointsArray, rawJoints, JNI_ABORT);
    tracker->updateHandJoints(isLeft, joints, confidence);
    tracker->updatePinchGesture(isLeft);
    tracker->updateFistGesture(isLeft);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeResetHand(JNIEnv* /*env*/, jobject /*thiz*/, jboolean isLeft) {
    auto* tracker = getOrCreateHandTracker();
    tracker->resetHand(isLeft);
}

JNIEXPORT jint JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeGetHandTrackingDots(
    JNIEnv* env, jobject /*thiz*/, jfloatArray leftDots, jfloatArray rightDots
) {
    auto* tracker = getOrCreateHandTracker();
    int mask = 0;

    auto fillDots = [&](bool isLeft, jfloatArray outArray) {
        if (!outArray) return;
        float tx, ty, tz, ix, iy, iz;
        if (tracker->getThumbAndIndexPositions(isLeft, tx, ty, tz, ix, iy, iz)) {
            const auto& hand = tracker->getHand(isLeft);
            bool pinching = tracker->isPinching(isLeft);
            jfloat data[8] = {
                tx, ty, tz,
                ix, iy, iz,
                pinching ? 1.0f : 0.0f,
                hand.trackingConfidence
            };
            env->SetFloatArrayRegion(outArray, 0, 8, data);
            mask |= (isLeft ? 1 : 2);
        }
    };

    fillDots(true, leftDots);
    fillDots(false, rightDots);

    return mask;
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
Java_com_spatial_xr_bridge_NativeBridge_nativeGetFistState(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto* tracker = getOrCreateHandTracker();
    int mask = 0;
    if (tracker->isFist(true)) mask |= 1;
    if (tracker->isFist(false)) mask |= 2;
    return mask;
}

JNIEXPORT jint JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeGetOpenHandState(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto* tracker = getOrCreateHandTracker();
    int mask = 0;
    if (tracker->isOpenHand(true)) mask |= 1;
    if (tracker->isOpenHand(false)) mask |= 2;
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

JNIEXPORT jint JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeGetHandPositions(
    JNIEnv* env, jobject /*thiz*/, jfloatArray leftPos, jfloatArray rightPos
) {
    auto* tracker = getOrCreateHandTracker();
    int mask = 0;

    auto fillPos = [&](bool isLeft, jfloatArray outArray) {
        if (!outArray) return;
        const auto& hand = tracker->getHand(isLeft);
        if (hand.isTracked) {
            const auto& wrist = hand.joints[spatial::tracking::WRIST];
            const auto& mcp = hand.joints[spatial::tracking::MIDDLE_FINGER_MCP];
            jfloat pos[3] = {
                (wrist.x + mcp.x) * 0.5f,
                (wrist.y + mcp.y) * 0.5f,
                (wrist.z + mcp.z) * 0.5f
            };
            env->SetFloatArrayRegion(outArray, 0, 3, pos);
            mask |= (isLeft ? 1 : 2);
        }
    };

    fillPos(true, leftPos);
    fillPos(false, rightPos);
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

JNIEXPORT jfloat JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeComputeStereoDepth(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat normWristX, jfloat normWristY,
    jfloat normMcpX, jfloat normMcpY
) {
    std::lock_guard<std::mutex> lk(gCarinaFrame.mtx);
    if (gCarinaFrame.left.empty() || gCarinaFrame.right.empty() ||
        gCarinaFrame.width <= 0 || gCarinaFrame.height <= 0) {
        return 0.0f;
    }

    int width = gCarinaFrame.width;
    int height = gCarinaFrame.height;
    const uint8_t* leftImg = gCarinaFrame.left.data();
    const uint8_t* rightImg = gCarinaFrame.right.data();

    auto matchFeature = [&](float nx, float ny) -> float {
        int lx = static_cast<int>(nx * width);
        int ly = static_cast<int>(ny * height);

        const int patchRadius = 5; // 11x11 patch
        if (lx - patchRadius < 0 || lx + patchRadius >= width ||
            ly - patchRadius - 2 < 0 || ly + patchRadius + 2 >= height) {
            return -1.0f;
        }

        // Search disparity window for hands in front of glasses (0.22m to 1.30m)
        // d ~ 16 to 110 pixels
        int minDisp = 16;
        int maxDisp = std::min(115, lx - patchRadius);
        if (minDisp >= maxDisp) return -1.0f;

        int bestDisp = -1;
        uint32_t minSad = UINT32_MAX;

        for (int d = minDisp; d <= maxDisp; ++d) {
            for (int dy = -1; dy <= 1; ++dy) {
                uint32_t sad = 0;
                for (int v = -patchRadius; v <= patchRadius; ++v) {
                    int leftRow = (ly + v) * width;
                    int rightRow = (ly + dy + v) * width;
                    for (int u = -patchRadius; u <= patchRadius; ++u) {
                        uint8_t lVal = leftImg[leftRow + (lx + u)];
                        uint8_t rVal = rightImg[rightRow + (lx - d + u)];
                        sad += std::abs(static_cast<int>(lVal) - static_cast<int>(rVal));
                    }
                }
                if (sad < minSad) {
                    minSad = sad;
                    bestDisp = d;
                }
            }
        }

        if (bestDisp <= 0) return -1.0f;

        // Sub-pixel parabolic refinement
        float subDisp = static_cast<float>(bestDisp);
        if (bestDisp > minDisp && bestDisp < maxDisp) {
            auto computeSAD = [&](int d) -> uint32_t {
                uint32_t sad = 0;
                for (int v = -patchRadius; v <= patchRadius; ++v) {
                    int leftRow = (ly + v) * width;
                    int rightRow = (ly + v) * width;
                    for (int u = -patchRadius; u <= patchRadius; ++u) {
                        sad += std::abs(static_cast<int>(leftImg[leftRow + lx + u]) -
                                        static_cast<int>(rightImg[rightRow + lx - d + u]));
                    }
                }
                return sad;
            };
            uint32_t s0 = computeSAD(bestDisp - 1);
            uint32_t s1 = minSad;
            uint32_t s2 = computeSAD(bestDisp + 1);
            float denom = 2.0f * (static_cast<float>(s0) - 2.0f * s1 + s2);
            if (std::abs(denom) > 1e-4f) {
                float delta = (static_cast<float>(s0) - s2) / denom;
                if (std::abs(delta) < 1.0f) {
                    subDisp += delta;
                }
            }
        }

        return subDisp;
    };

    float d1 = matchFeature(normWristX, normWristY);
    float d2 = matchFeature(normMcpX, normMcpY);

    float validDisp = 0.0f;
    int count = 0;
    if (d1 > 12.0f) { validDisp += d1; count++; }
    if (d2 > 12.0f) { validDisp += d2; count++; }

    if (count == 0) return 0.0f;
    float avgDisp = validDisp / count;

    // Stereoscopic baseline triangulation:
    // Z = -(f_px * B) / disparity
    // Baseline B = 0.064m, Focal length f_px = 368px => f*B = 23.552
    const float FB_CONSTANT = 23.552f;
    float depth = -FB_CONSTANT / avgDisp;

    if (depth < -1.30f) depth = -1.30f;
    if (depth > -0.22f) depth = -0.22f;

    return depth;
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

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeVitureCreate(JNIEnv* /*env*/, jobject /*thiz*/, jint productId, jint fd) {
    if (gVitureHandle) {
        if (gCarinaPollRunning) {
            gCarinaPollRunning = false;
            if (gCarinaPollThread.joinable()) {
                gCarinaPollThread.join();
            }
        }
        xr_device_provider_stop(gVitureHandle);
        xr_device_provider_shutdown(gVitureHandle);
        xr_device_provider_destroy(gVitureHandle);
        gVitureHandle = nullptr;
    }

    gVitureHandle = xr_device_provider_create(productId, fd);
    if (!gVitureHandle) {
        LOGE("xr_device_provider_create failed for PID 0x%04x, fd %d", productId, fd);
        return JNI_FALSE;
    }
    LOGI("Viture device provider created for PID 0x%04x, fd %d", productId, fd);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeVitureStartCarina(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!gVitureHandle) {
        LOGE("nativeVitureStartCarina: gVitureHandle is null");
        return JNI_FALSE;
    }

    xr_device_provider_set_dof_type_carina(gVitureHandle, 1);
    xr_device_provider_register_callbacks_carina(gVitureHandle, nullptr, nullptr, nullptr, onCarinaCamera);

    int initRes = xr_device_provider_initialize(gVitureHandle, nullptr, nullptr);
    if (initRes != VITURE_GLASSES_SUCCESS) {
        LOGE("xr_device_provider_initialize failed: %d", initRes);
        return JNI_FALSE;
    }

    int startRes = xr_device_provider_start(gVitureHandle);
    if (startRes != VITURE_GLASSES_SUCCESS) {
        LOGE("xr_device_provider_start failed: %d", startRes);
        return JNI_FALSE;
    }

    int expRes = xr_device_provider_set_auto_exposure_carina(gVitureHandle);
    if (expRes != VITURE_GLASSES_SUCCESS) {
        expRes = xr_device_provider_set_manual_exposure_carina(gVitureHandle, 8.0f, 15);
    }
    LOGI("Carina exposure set result (auto-exposure first): %d", expRes);

    if (!gCarinaPollRunning) {
        gCarinaPollRunning = true;
        gCarinaPollThread = std::thread(carinaPollWorker);
    }

    LOGI("Carina device and stereo cameras started successfully");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeVitureStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (gCarinaPollRunning) {
        gCarinaPollRunning = false;
        if (gCarinaPollThread.joinable()) {
            gCarinaPollThread.join();
        }
    }
    if (gVitureHandle) {
        xr_device_provider_stop(gVitureHandle);
        xr_device_provider_shutdown(gVitureHandle);
        xr_device_provider_destroy(gVitureHandle);
        gVitureHandle = nullptr;
    }
    if (gVitureCameraHandle) {
        xr_camera_provider_stop(gVitureCameraHandle);
        xr_camera_provider_destroy(gVitureCameraHandle);
        gVitureCameraHandle = nullptr;
    }
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeVitureGetCarinaFrame(JNIEnv* env, jobject /*thiz*/, jbyteArray outLeftFrame) {
    if (!outLeftFrame) return nullptr;

    std::lock_guard<std::mutex> lk(gCarinaFrame.mtx);
    if (gCarinaFrame.sequence == gCarinaFrame.lastRead || gCarinaFrame.left.empty()) {
        return nullptr;
    }

    gCarinaFrame.lastRead = gCarinaFrame.sequence;
    jsize maxLen = env->GetArrayLength(outLeftFrame);
    jsize copyLen = std::min(maxLen, static_cast<jsize>(gCarinaFrame.left.size()));

    env->SetByteArrayRegion(outLeftFrame, 0, copyLen,
                            reinterpret_cast<const jbyte*>(gCarinaFrame.left.data()));

    jintArray dims = env->NewIntArray(2);
    jint d[2] = {gCarinaFrame.width, gCarinaFrame.height};
    env->SetIntArrayRegion(dims, 0, 2, d);
    return dims;
}

JNIEXPORT jint JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeVitureGetCameraVid(JNIEnv* /*env*/, jobject /*thiz*/, jint glassesPid) {
    return xr_camera_provider_get_camera_vid(glassesPid);
}

JNIEXPORT jint JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeVitureGetCameraPid(JNIEnv* /*env*/, jobject /*thiz*/, jint glassesPid) {
    return xr_camera_provider_get_camera_pid(glassesPid);
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeVitureCameraCreate(JNIEnv* /*env*/, jobject /*thiz*/, jint vid, jint pid, jint fd) {
    if (gVitureCameraHandle) {
        xr_camera_provider_destroy(gVitureCameraHandle);
        gVitureCameraHandle = nullptr;
    }
    gVitureCameraHandle = xr_camera_provider_create(vid, pid, fd);
    return gVitureCameraHandle != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeVitureCameraStart(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!gVitureCameraHandle) return JNI_FALSE;
    int rc = xr_camera_provider_start(gVitureCameraHandle, onRgbCamera, nullptr);
    return rc == VITURE_GLASSES_SUCCESS ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeVitureCameraStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!gVitureCameraHandle) return JNI_TRUE;
    xr_camera_provider_stop(gVitureCameraHandle);
    xr_camera_provider_destroy(gVitureCameraHandle);
    gVitureCameraHandle = nullptr;
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeVitureCameraGetFrame(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(gRgbCameraFrame.mtx);
    if (gRgbCameraFrame.sequence == gRgbCameraFrame.lastRead || gRgbCameraFrame.data.empty()) {
        return nullptr;
    }
    gRgbCameraFrame.lastRead = gRgbCameraFrame.sequence;
    jsize sz = static_cast<jsize>(gRgbCameraFrame.data.size());
    jbyteArray arr = env->NewByteArray(sz);
    env->SetByteArrayRegion(arr, 0, sz, reinterpret_cast<const jbyte*>(gRgbCameraFrame.data.data()));
    return arr;
}

} // extern "C"
