#include "pose_predictor.hpp"
#include <cmath>

namespace spatial::tracking {

PosePredictor::PosePredictor() = default;

void PosePredictor::recordPose(const Pose6DoF& pose) {
    uint64_t index = writeCount_.load(std::memory_order_relaxed);
    ringBuffer_[index & BUFFER_MASK] = pose;
    writeCount_.fetch_add(1, std::memory_order_release);
}

bool PosePredictor::getLatestPose(Pose6DoF& outPose) const {
    uint64_t count = writeCount_.load(std::memory_order_acquire);
    if (count == 0) return false;
    outPose = ringBuffer_[(count - 1) & BUFFER_MASK];
    return true;
}

Quaternion PosePredictor::extrapolateOrientation(
    const Quaternion& currentOrientation,
    const std::array<float, 3>& angularVelocity,
    float dtSeconds
) {
    float wx = angularVelocity[0];
    float wy = angularVelocity[1];
    float wz = angularVelocity[2];
    float omega = std::sqrt(wx * wx + wy * wy + wz * wz);

    Quaternion deltaQ{1.0f, 0.0f, 0.0f, 0.0f};
    if (omega > 1e-6f) {
        float halfAngle = omega * dtSeconds * 0.5f;
        float sinHalf = std::sin(halfAngle);
        deltaQ = {
            std::cos(halfAngle),
            (wx / omega) * sinHalf,
            (wy / omega) * sinHalf,
            (wz / omega) * sinHalf
        };
    } else {
        deltaQ = {
            1.0f,
            0.5f * wx * dtSeconds,
            0.5f * wy * dtSeconds,
            0.5f * wz * dtSeconds
        };
    }

    return currentOrientation.multiply(deltaQ).normalized();
}

bool PosePredictor::predictPose(uint64_t targetTimestampNs, Pose6DoF& outPose) const {
    Pose6DoF latest;
    if (!getLatestPose(latest)) return false;

    if (targetTimestampNs <= latest.timestampNs) {
        outPose = latest;
        return true;
    }

    float dt = static_cast<float>(targetTimestampNs - latest.timestampNs) * 1e-9f;

    // Linear translation forward extrapolation: P(t) = P0 + V * dt
    outPose.timestampNs = targetTimestampNs;
    outPose.position[0] = latest.position[0] + latest.linearVelocity[0] * dt;
    outPose.position[1] = latest.position[1] + latest.linearVelocity[1] * dt;
    outPose.position[2] = latest.position[2] + latest.linearVelocity[2] * dt;

    // Angular orientation forward extrapolation: Q(t) = Q0 * exp(1/2 * w * dt)
    outPose.orientation = extrapolateOrientation(latest.orientation, latest.angularVelocity, dt);

    outPose.linearVelocity = latest.linearVelocity;
    outPose.angularVelocity = latest.angularVelocity;
    return true;
}

} // namespace spatial::tracking
