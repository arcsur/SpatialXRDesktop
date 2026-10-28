#include "vio_pose_fusion.hpp"
#include <cmath>
#include <cstdlib>

namespace spatial::tracking {

std::vector<Keypoint2D> FastCornerDetector::detectCorners(
    const uint8_t* image,
    int width,
    int height,
    int threshold
) {
    std::vector<Keypoint2D> keypoints;
    if (!image || width < 16 || height < 16) return keypoints;

    // Fast 9/16 corner test: Bresenham circle radius 3
    // Offsets around pixel (x, y):
    // 0: (0, -3), 4: (3, 0), 8: (0, 3), 12: (-3, 0)
    for (int y = 3; y < height - 3; y += 2) {
        for (int x = 3; x < width - 3; x += 2) {
            uint8_t p = image[y * width + x];

            uint8_t top = image[(y - 3) * width + x];
            uint8_t bottom = image[(y + 3) * width + x];
            uint8_t right = image[y * width + (x + 3)];
            uint8_t left = image[y * width + (x - 3)];

            int brightCount = (top > p + threshold) + (bottom > p + threshold) +
                              (right > p + threshold) + (left > p + threshold);
            int darkCount = (top < p - threshold) + (bottom < p - threshold) +
                            (right < p - threshold) + (left < p - threshold);

            if (brightCount >= 2 || darkCount >= 2) {
                int score = std::abs(static_cast<int>(top) - p) +
                            std::abs(static_cast<int>(bottom) - p) +
                            std::abs(static_cast<int>(right) - p) +
                            std::abs(static_cast<int>(left) - p);
                keypoints.push_back({x, y, score});
            }
        }
    }
    return keypoints;
}

VioPoseFusion::VioPoseFusion() {
    initialize();
}

void VioPoseFusion::initialize(const std::array<float, 3>& initialPos) {
    state_.position = initialPos;
    state_.velocity = {0.0f, 0.0f, 0.0f};
    state_.orientation = {1.0f, 0.0f, 0.0f, 0.0f};
    state_.accelBias = {0.0f, 0.0f, 0.0f};
    state_.gyroBias = {0.0f, 0.0f, 0.0f};
    state_.covPos = 1.0f; // High initial spatial uncertainty until visual anchor locks
    state_.covVel = 1e-2f;
    state_.covRot = 1e-3f;
}

void VioPoseFusion::predictWithImu(const ImuSample& sample, float dt) {
    if (dt <= 0.0f) return;

    // Unbias angular velocity
    float wx = sample.gyro[0] - state_.gyroBias[0];
    float wy = sample.gyro[1] - state_.gyroBias[1];
    float wz = sample.gyro[2] - state_.gyroBias[2];
    lastAngularVel_ = {wx, wy, wz};

    // Propagate orientation quaternion
    state_.orientation = PosePredictor::extrapolateOrientation(state_.orientation, lastAngularVel_, dt);

    // Unbias acceleration in body frame
    float ax = sample.accel[0] - state_.accelBias[0];
    float ay = sample.accel[1] - state_.accelBias[1];
    float az = sample.accel[2] - state_.accelBias[2];

    // Rotate body acceleration to world frame: a_world = Q * a_body * Q_conj
    // Quaternion rotation: v' = v + 2 * r x (r x v + w * v)
    float qx = state_.orientation.x, qy = state_.orientation.y, qz = state_.orientation.z, qw = state_.orientation.w;
    float tx = 2.0f * (qy * az - qz * ay);
    float ty = 2.0f * (qz * ax - qx * az);
    float tz = 2.0f * (qx * ay - qy * ax);

    float ax_world = ax + qw * tx + (qy * tz - qz * ty);
    float ay_world = ay + qw * ty + (qz * tx - qx * tz);
    float az_world = az + qw * tz + (qx * ty - qy * tx);

    // Subtract gravity in world frame
    float netAx = ax_world + gravityVector_[0];
    float netAy = ay_world + gravityVector_[1];
    float netAz = az_world + gravityVector_[2];

    // Propagate position & velocity
    state_.position[0] += state_.velocity[0] * dt + 0.5f * netAx * dt * dt;
    state_.position[1] += state_.velocity[1] * dt + 0.5f * netAy * dt * dt;
    state_.position[2] += state_.velocity[2] * dt + 0.5f * netAz * dt * dt;

    state_.velocity[0] += netAx * dt;
    state_.velocity[1] += netAy * dt;
    state_.velocity[2] += netAz * dt;

    // Grow covariance with process noise
    state_.covPos += 1e-4f * dt;
    state_.covVel += 1e-3f * dt;
    state_.covRot += 1e-4f * dt;
}

void VioPoseFusion::updateVisualMeasurement(const std::array<float, 3>& visualDeltaPos, float confidence) {
    if (confidence <= 0.0f) return;

    // Kalman gain K = P / (P + R)
    float r = (1.0f - confidence) * 0.1f + 0.01f;
    float k = state_.covPos / (state_.covPos + r);

    // Error state update
    state_.position[0] += k * (visualDeltaPos[0] - state_.position[0]);
    state_.position[1] += k * (visualDeltaPos[1] - state_.position[1]);
    state_.position[2] += k * (visualDeltaPos[2] - state_.position[2]);

    // Covariance contraction
    state_.covPos *= (1.0f - k);
    state_.covVel *= 0.95f; // Damp velocity drift
}

Pose6DoF VioPoseFusion::getCurrentPose(uint64_t timestampNs) const {
    Pose6DoF pose;
    pose.timestampNs = timestampNs;
    pose.position = state_.position;
    pose.orientation = state_.orientation;
    pose.linearVelocity = state_.velocity;
    pose.angularVelocity = lastAngularVel_;
    return pose;
}

} // namespace spatial::tracking
