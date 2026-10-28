#pragma once

#include <cstdint>
#include <array>
#include <atomic>
#include <thread>
#include <chrono>
#include "hardware/imu_worker.hpp"
#include "tracking/pose_predictor.hpp"
#include "quad_renderer.hpp"

namespace spatial::graphics {

struct EyeBufferSubmission {
    uint32_t textureId{0};
    spatial::tracking::Pose6DoF renderPose{};
    uint64_t submitTimestampNs{0};
};

class AtwReprojector {
public:
    static constexpr uint32_t TARGET_REFRESH_RATE_HZ = 120;
    static constexpr uint32_t VSYNC_PERIOD_US = 1000000 / TARGET_REFRESH_RATE_HZ; // ~8333 us (8.33ms)

    AtwReprojector();
    ~AtwReprojector();

    bool start(std::shared_ptr<spatial::tracking::PosePredictor> predictor);
    void stop();
    bool isRunning() const { return isRunning_.load(); }

    void submitEyeBuffer(int eyeIndex, uint32_t textureId, const spatial::tracking::Pose6DoF& renderPose);

    // Task 8.2: Warp quaternion & reprojection matrix calculation
    // Q_warp = Q_present * inv(Q_render)
    static spatial::tracking::Quaternion computeWarpQuaternion(
        const spatial::tracking::Quaternion& renderOrientation,
        const spatial::tracking::Quaternion& presentOrientation
    );

    static std::array<float, 16> quaternionToMatrix4x4(const spatial::tracking::Quaternion& q);

    static std::array<float, 16> computeReprojectionMatrix(
        const spatial::tracking::Pose6DoF& renderPose,
        const spatial::tracking::Pose6DoF& presentPose
    );

    static Vertex3D warpVertex(const Vertex3D& inVertex, const std::array<float, 16>& warpMatrix);

    uint64_t getReprojectedFrameCount() const { return reprojectedFrames_.load(); }
    float getAverageMotionToPhotonLatencyMs() const { return avgLatencyMs_.load(); }

private:
    std::atomic<bool> isRunning_{false};
    std::thread atwThread_;
    std::shared_ptr<spatial::tracking::PosePredictor> posePredictor_;

    std::array<EyeBufferSubmission, 2> eyeSubmissions_{};
    std::atomic<uint64_t> reprojectedFrames_{0};
    std::atomic<float> avgLatencyMs_{12.5f}; // Typical target ~ 12-14ms, well under 18ms

    void runAtwLoop();
};

} // namespace spatial::graphics
