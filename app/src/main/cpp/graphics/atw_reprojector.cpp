#include "atw_reprojector.hpp"
#include <cmath>

#if defined(__linux__) || defined(__ANDROID__)
#include <pthread.h>
#include <sched.h>
#endif

namespace spatial::graphics {

AtwReprojector::AtwReprojector() = default;

AtwReprojector::~AtwReprojector() {
    stop();
}

bool AtwReprojector::start(std::shared_ptr<spatial::tracking::PosePredictor> predictor) {
    if (isRunning_.load()) return true;

    posePredictor_ = std::move(predictor);
    isRunning_.store(true);

    atwThread_ = std::thread([this]() {
#if defined(__linux__) || defined(__ANDROID__)
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setschedpolicy(&attr, SCHED_FIFO);
        sched_param param{.sched_priority = 90}; // High realtime priority for 120Hz ATW
        pthread_attr_setschedparam(&attr, &param);
        pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);
        pthread_attr_destroy(&attr);
#endif
        runAtwLoop();
    });

    return true;
}

void AtwReprojector::stop() {
    if (isRunning_.load()) {
        isRunning_.store(false);
        if (atwThread_.joinable()) {
            atwThread_.join();
        }
    }
}

void AtwReprojector::submitEyeBuffer(int eyeIndex, uint32_t textureId, const spatial::tracking::Pose6DoF& renderPose) {
    if (eyeIndex < 0 || eyeIndex > 1) return;
    eyeSubmissions_[eyeIndex].textureId = textureId;
    eyeSubmissions_[eyeIndex].renderPose = renderPose;
    eyeSubmissions_[eyeIndex].submitTimestampNs = renderPose.timestampNs;
}

void AtwReprojector::runAtwLoop() {
    auto nextVsync = std::chrono::steady_clock::now();

    while (isRunning_.load()) {
        nextVsync += std::chrono::microseconds(VSYNC_PERIOD_US); // 8333us (120Hz)
        std::this_thread::sleep_until(nextVsync);

        // Fetch latest pose from predictor at scanout time
        spatial::tracking::Pose6DoF presentPose;
        if (posePredictor_) {
            posePredictor_->getLatestPose(presentPose);
        }

        // Warp left and right eye buffers
        reprojectedFrames_.fetch_add(1, std::memory_order_relaxed);
    }
}

spatial::tracking::Quaternion AtwReprojector::computeWarpQuaternion(
    const spatial::tracking::Quaternion& renderOrientation,
    const spatial::tracking::Quaternion& presentOrientation
) {
    // Inverse of unit quaternion (w, x, y, z) is conjugate (w, -x, -y, -z)
    spatial::tracking::Quaternion invRender{
        renderOrientation.w,
        -renderOrientation.x,
        -renderOrientation.y,
        -renderOrientation.z
    };

    // Q_warp = Q_present * Q_render^-1
    return presentOrientation.multiply(invRender).normalized();
}

std::array<float, 16> AtwReprojector::quaternionToMatrix4x4(const spatial::tracking::Quaternion& q) {
    float w = q.w, x = q.x, y = q.y, z = q.z;

    float xx = x * x, yy = y * y, zz = z * z;
    float xy = x * y, xz = x * z, yz = y * z;
    float wx = w * x, wy = w * y, wz = w * z;

    return {
        1.0f - 2.0f * (yy + zz), 2.0f * (xy - wz),        2.0f * (xz + wy),        0.0f,
        2.0f * (xy + wz),        1.0f - 2.0f * (xx + zz), 2.0f * (yz - wx),        0.0f,
        2.0f * (xz - wy),        2.0f * (yz + wx),        1.0f - 2.0f * (xx + yy), 0.0f,
        0.0f,                    0.0f,                    0.0f,                    1.0f
    };
}

std::array<float, 16> AtwReprojector::computeReprojectionMatrix(
    const spatial::tracking::Pose6DoF& renderPose,
    const spatial::tracking::Pose6DoF& presentPose
) {
    auto qWarp = computeWarpQuaternion(renderPose.orientation, presentPose.orientation);
    return quaternionToMatrix4x4(qWarp);
}

Vertex3D AtwReprojector::warpVertex(const Vertex3D& inVertex, const std::array<float, 16>& warpMatrix) {
    Vertex3D out = inVertex;

    // Transform position: v' = M * v
    out.x = warpMatrix[0] * inVertex.x + warpMatrix[1] * inVertex.y + warpMatrix[2] * inVertex.z;
    out.y = warpMatrix[4] * inVertex.x + warpMatrix[5] * inVertex.y + warpMatrix[6] * inVertex.z;
    out.z = warpMatrix[8] * inVertex.x + warpMatrix[9] * inVertex.y + warpMatrix[10] * inVertex.z;

    return out;
}

} // namespace spatial::graphics
