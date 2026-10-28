#pragma once

#include <cstdint>
#include <array>
#include <atomic>
#include "hardware/imu_worker.hpp"

namespace spatial::tracking {

struct Pose6DoF {
    uint64_t timestampNs{0};
    std::array<float, 3> position{0.0f, 0.0f, 0.0f};       // x, y, z in meters
    Quaternion orientation{1.0f, 0.0f, 0.0f, 0.0f};         // w, x, y, z
    std::array<float, 3> linearVelocity{0.0f, 0.0f, 0.0f};  // vx, vy, vz (m/s)
    std::array<float, 3> angularVelocity{0.0f, 0.0f, 0.0f}; // wx, wy, wz (rad/s)
};

class PosePredictor {
public:
    static constexpr size_t BUFFER_SIZE = 128; // Power of two for fast bitmask indexing
    static constexpr size_t BUFFER_MASK = BUFFER_SIZE - 1;

    PosePredictor();

    void recordPose(const Pose6DoF& pose);
    bool getLatestPose(Pose6DoF& outPose) const;
    bool predictPose(uint64_t targetTimestampNs, Pose6DoF& outPose) const;

    size_t getRecordedCount() const { return writeCount_.load(std::memory_order_relaxed); }

    static Quaternion extrapolateOrientation(
        const Quaternion& currentOrientation,
        const std::array<float, 3>& angularVelocity,
        float dtSeconds
    );

private:
    std::array<Pose6DoF, BUFFER_SIZE> ringBuffer_{};
    std::atomic<uint64_t> writeCount_{0};
};

} // namespace spatial::tracking
