#pragma once

#include <cstdint>
#include <vector>
#include <array>
#include "hardware/imu_worker.hpp"
#include "pose_predictor.hpp"

namespace spatial::tracking {

struct Keypoint2D {
    int x;
    int y;
    int score;
};

class FastCornerDetector {
public:
    static std::vector<Keypoint2D> detectCorners(
        const uint8_t* image,
        int width,
        int height,
        int threshold = 20
    );
};

struct EsEkfState {
    std::array<float, 3> position{0.0f, 0.0f, 0.0f};
    std::array<float, 3> velocity{0.0f, 0.0f, 0.0f};
    Quaternion orientation{1.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 3> accelBias{0.0f, 0.0f, 0.0f};
    std::array<float, 3> gyroBias{0.0f, 0.0f, 0.0f};

    // Diagonal covariance elements
    float covPos{1e-4f};
    float covVel{1e-3f};
    float covRot{1e-4f};
};

class VioPoseFusion {
public:
    VioPoseFusion();

    void initialize(const std::array<float, 3>& initialPos = {0.0f, 0.0f, 0.0f});

    void predictWithImu(const ImuSample& sample, float dt);
    void updateVisualMeasurement(const std::array<float, 3>& visualDeltaPos, float confidence = 0.9f);

    Pose6DoF getCurrentPose(uint64_t timestampNs) const;
    const EsEkfState& getState() const { return state_; }

private:
    EsEkfState state_;
    std::array<float, 3> gravityVector_{0.0f, -9.80665f, 0.0f}; // Earth gravity in world frame
    std::array<float, 3> lastAngularVel_{0.0f, 0.0f, 0.0f};
};

} // namespace spatial::tracking
