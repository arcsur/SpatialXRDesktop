#include "multiview_pipeline.hpp"
#include <cstring>

namespace spatial::graphics {

std::array<float, 16> MultiviewPipeline::createIdentityMatrix() {
    return {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };
}

std::array<float, 16> MultiviewPipeline::createPerspectiveMatrix(float fovRad, float aspect, float nearP, float farP) {
    float tanHalfFov = std::tan(fovRad / 2.0f);
    std::array<float, 16> mat{};
    mat[0] = 1.0f / (aspect * tanHalfFov);
    mat[5] = 1.0f / tanHalfFov;
    mat[10] = -(farP + nearP) / (farP - nearP);
    mat[11] = -1.0f;
    mat[14] = -(2.0f * farP * nearP) / (farP - nearP);
    return mat;
}

std::array<float, 16> MultiviewPipeline::multiplyMatrices(const std::array<float, 16>& a, const std::array<float, 16>& b) {
    std::array<float, 16> result{};
    for (int row = 0; row < 4; ++row) {
        for (int col = 0; col < 4; ++col) {
            float sum = 0.0f;
            for (int k = 0; k < 4; ++k) {
                sum += a[row * 4 + k] * b[k * 4 + col];
            }
            result[row * 4 + col] = sum;
        }
    }
    return result;
}

StereoViewMatrices MultiviewPipeline::computeStereoMatrices(
    float ipdMeters,
    float fovDegrees,
    float nearPlane,
    float farPlane
) {
    StereoViewMatrices matrices{};
    float halfIpd = ipdMeters * 0.5f;

    // View matrix translates camera: left eye moves -halfIpd, right eye moves +halfIpd
    matrices.leftView = createIdentityMatrix();
    matrices.leftView[12] = halfIpd; // Camera moves right -> world moves left

    matrices.rightView = createIdentityMatrix();
    matrices.rightView[12] = -halfIpd; // Camera moves left -> world moves right

    float aspect = 1920.0f / 1080.0f; // 16:9 per eye
    float fovRad = fovDegrees * (3.14159265358979323846f / 180.0f);
    matrices.projection = createPerspectiveMatrix(fovRad, aspect, nearPlane, farPlane);

    return matrices;
}

} // namespace spatial::graphics
