#include "quad_renderer.hpp"
#include <cmath>

namespace spatial::graphics {

std::vector<Vertex3D> QuadRenderer::generatePlanarQuadMesh(float width, float height) {
    float hw = width * 0.5f;
    float hh = height * 0.5f;

    // 2 triangles forming a quad
    // (x, y, z, u, v, nx, ny, nz)
    return {
        // Triangle 1
        {-hw,  hh, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
        {-hw, -hh, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
        { hw, -hh, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f},

        // Triangle 2
        {-hw,  hh, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
        { hw, -hh, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f},
        { hw,  hh, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f}
    };
}

std::vector<Vertex3D> QuadRenderer::generateCurvedMesh(float width, float height, float radiusMeters, int segments) {
    if (radiusMeters <= 0.001f || segments < 2) {
        return generatePlanarQuadMesh(width, height);
    }

    std::vector<Vertex3D> vertices;
    vertices.reserve(segments * 6);

    float totalAngle = width / radiusMeters;
    float startAngle = -totalAngle * 0.5f;
    float angleStep = totalAngle / segments;
    float hh = height * 0.5f;

    for (int i = 0; i < segments; ++i) {
        float a0 = startAngle + i * angleStep;
        float a1 = startAngle + (i + 1) * angleStep;

        float u0 = static_cast<float>(i) / segments;
        float u1 = static_cast<float>(i + 1) / segments;

        // Cylindrical coordinates centered at origin (0, 0, -radius)
        float x0 = radiusMeters * std::sin(a0);
        float z0 = radiusMeters * (1.0f - std::cos(a0));
        float x1 = radiusMeters * std::sin(a1);
        float z1 = radiusMeters * (1.0f - std::cos(a1));

        // Vertex definitions
        Vertex3D tl{x0,  hh, -z0, u0, 0.0f, -std::sin(a0), 0.0f, std::cos(a0)};
        Vertex3D bl{x0, -hh, -z0, u0, 1.0f, -std::sin(a0), 0.0f, std::cos(a0)};
        Vertex3D tr{x1,  hh, -z1, u1, 0.0f, -std::sin(a1), 0.0f, std::cos(a1)};
        Vertex3D br{x1, -hh, -z1, u1, 1.0f, -std::sin(a1), 0.0f, std::cos(a1)};

        // Tri 1
        vertices.push_back(tl);
        vertices.push_back(bl);
        vertices.push_back(br);

        // Tri 2
        vertices.push_back(tl);
        vertices.push_back(br);
        vertices.push_back(tr);
    }

    return vertices;
}

std::array<Vertex3D, 4> QuadRenderer::getCornerHandles(float width, float height) {
    float hw = width * 0.5f;
    float hh = height * 0.5f;
    return {
        Vertex3D{-hw,  hh, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f}, // Top-Left
        Vertex3D{ hw,  hh, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f}, // Top-Right
        Vertex3D{-hw, -hh, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f}, // Bottom-Left
        Vertex3D{ hw, -hh, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f}  // Bottom-Right
    };
}

bool QuadRenderer::rayIntersectsQuad(
    const std::array<float, 3>& rayOrigin,
    const std::array<float, 3>& rayDir,
    float width,
    float height,
    float& hitDistance,
    float& hitU,
    float& hitV
) {
    // Quad lies on z = 0 plane, normal is (0, 0, 1)
    if (std::abs(rayDir[2]) < 1e-6f) {
        return false; // Ray parallel to plane
    }

    float t = -rayOrigin[2] / rayDir[2];
    if (t < 0.0f) {
        return false; // Quad is behind ray
    }

    float hitX = rayOrigin[0] + t * rayDir[0];
    float hitY = rayOrigin[1] + t * rayDir[1];

    float hw = width * 0.5f;
    float hh = height * 0.5f;

    if (hitX < -hw || hitX > hw || hitY < -hh || hitY > hh) {
        return false; // Outside quad boundaries
    }

    hitDistance = t;
    hitU = (hitX + hw) / width;
    hitV = 1.0f - ((hitY + hh) / height); // V=0 at top, V=1 at bottom
    return true;
}

} // namespace spatial::graphics
