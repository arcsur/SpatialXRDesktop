#pragma once

#include <cstdint>
#include <vector>
#include <array>

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
};

} // namespace spatial::graphics
