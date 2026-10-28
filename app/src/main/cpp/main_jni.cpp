#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <memory>
#include "graphics/vulkan_compositor.hpp"
#include "hardware/viture_device.hpp"

#define LOG_TAG "SpatialXR_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::unique_ptr<spatial::graphics::VulkanCompositor> gCompositor;
    std::unique_ptr<spatial::hardware::VitureDevice> gVitureDevice;
    ANativeWindow* gNativeWindow = nullptr;
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
    if (gNativeWindow) {
        ANativeWindow_release(gNativeWindow);
        gNativeWindow = nullptr;
    }
    LOGI("Compositor shut down");
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
Java_com_spatial_xr_bridge_NativeBridge_nativeProcessCameraFrame(JNIEnv* /*env*/, jobject /*thiz*/, jint /*w*/, jint /*h*/, jbyteArray /*y*/, jbyteArray /*uv*/) {
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeGetHandPointers(JNIEnv* /*env*/, jobject /*thiz*/, jfloatArray /*leftRay*/, jfloatArray /*rightRay*/) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_spatial_xr_bridge_NativeBridge_nativeGetPinchState(JNIEnv* /*env*/, jobject /*thiz*/) {
    return 0;
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
