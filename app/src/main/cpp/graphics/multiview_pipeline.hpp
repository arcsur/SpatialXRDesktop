#pragma once

#include <cstdint>
#include <array>
#include <cmath>

namespace spatial::graphics {

struct StereoViewMatrices {
    std::array<float, 16> leftView;
    std::array<float, 16> rightView;
    std::array<float, 16> projection;
};

class MultiviewPipeline {
public:
    MultiviewPipeline() = default;

    static constexpr uint32_t VIEW_MASK_STEREO = 0b11; // 2 views: Left (bit 0), Right (bit 1)

    // Default IPD = 64mm (0.064m)
    static StereoViewMatrices computeStereoMatrices(
        float ipdMeters = 0.064f,
        float fovDegrees = 45.0f,
        float nearPlane = 0.05f,
        float farPlane = 100.0f
    );

    static std::array<float, 16> createIdentityMatrix();
    static std::array<float, 16> createPerspectiveMatrix(float fovRad, float aspect, float nearP, float farP);
    static std::array<float, 16> multiplyMatrices(const std::array<float, 16>& a, const std::array<float, 16>& b);
};

} // namespace spatial::graphics
