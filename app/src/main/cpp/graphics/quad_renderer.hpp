#pragma once

#include <cstdint>
#include <vector>
#include <array>
#include <cmath>

namespace spatial::graphics {

struct Vertex3D {
    float x, y, z;
    float u, v;
    float nx, ny, nz;
};

struct QuadDimensions {
    float widthMeters{1.0f};
    float heightMeters{0.6f};
    float curvatureRadius{0.0f}; // 0 = flat planar, >0 = cylindrical radius in meters
};

class QuadRenderer {
public:
    QuadRenderer() = default;

    static std::vector<Vertex3D> generatePlanarQuadMesh(float width, float height);
    static std::vector<Vertex3D> generateCurvedMesh(float width, float height, float radiusMeters, int segments = 32);

    static std::array<Vertex3D, 4> getCornerHandles(float width, float height);

    static bool rayIntersectsQuad(
        const std::array<float, 3>& rayOrigin,
        const std::array<float, 3>& rayDir,
        float width,
        float height,
        float& hitDistance,
        float& hitU,
        float& hitV
    );

    // Top grab bar hit testing for 3D window translation
    static bool isTopBarHit(float localY, float height, float barThickness = 0.08f) {
        float halfH = height * 0.5f;
        return (localY >= (halfH - barThickness) && localY <= (halfH + 0.02f));
    }

    // Diagonal corner-to-corner length calculation for two-hand pinch stretch
    static float computeDiagonalLength(float width, float height) {
        return std::sqrt(width * width + height * height);
    }

    // Compute horizontal yaw angle in radians between two points in XZ plane
    static float computeYawAngle(float x1, float z1, float x2, float z2) {
        return std::atan2(z2 - z1, x2 - x1);
    }

    // Compute 3D midpoint between two points
    static std::array<float, 3> computeMidpoint(
        const std::array<float, 3>& p1,
        const std::array<float, 3>& p2
    ) {
        return {
            (p1[0] + p2[0]) * 0.5f,
            (p1[1] + p2[1]) * 0.5f,
            (p1[2] + p2[2]) * 0.5f
        };
    }
};

} // namespace spatial::graphics
