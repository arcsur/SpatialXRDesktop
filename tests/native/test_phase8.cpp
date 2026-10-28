#include <cassert>
#include <iostream>
#include <memory>
#include <cmath>

#include "graphics/atw_reprojector.hpp"
#include "tracking/pose_predictor.hpp"
#include "openxr/openxr_shim.hpp"

using namespace spatial::graphics;
using namespace spatial::tracking;
using namespace spatial::openxr;

void testAtwIdentityWarp() {
    Pose6DoF renderPose;
    renderPose.orientation = {1.0f, 0.0f, 0.0f, 0.0f}; // Identity

    Pose6DoF presentPose;
    presentPose.orientation = {1.0f, 0.0f, 0.0f, 0.0f}; // Identity

    auto qWarp = AtwReprojector::computeWarpQuaternion(renderPose.orientation, presentPose.orientation);
    assert(std::abs(qWarp.w - 1.0f) < 1e-4f);
    assert(std::abs(qWarp.x) < 1e-4f);
    assert(std::abs(qWarp.y) < 1e-4f);
    assert(std::abs(qWarp.z) < 1e-4f);

    auto warpMat = AtwReprojector::computeReprojectionMatrix(renderPose, presentPose);
    // Should be identity 4x4
    assert(std::abs(warpMat[0] - 1.0f) < 1e-4f);
    assert(std::abs(warpMat[5] - 1.0f) < 1e-4f);
    assert(std::abs(warpMat[10] - 1.0f) < 1e-4f);
    assert(std::abs(warpMat[15] - 1.0f) < 1e-4f);

    Vertex3D v{1.0f, 2.0f, -3.0f, 0.5f, 0.5f, 0.0f, 0.0f, 1.0f};
    auto warped = AtwReprojector::warpVertex(v, warpMat);
    assert(std::abs(warped.x - v.x) < 1e-4f);
    assert(std::abs(warped.y - v.y) < 1e-4f);
    assert(std::abs(warped.z - v.z) < 1e-4f);

    std::cout << "[PASS] testAtwIdentityWarp\n";
}

void testAtwDifferentialRotationWarp() {
    // Rendered when head was looking straight forward
    Pose6DoF renderPose;
    renderPose.orientation = {1.0f, 0.0f, 0.0f, 0.0f};

    // By the time of scanout (e.g. 12ms later), head has rotated by 0.1 rad (~5.7 deg) around Y axis
    float angle = 0.10f;
    float half = angle * 0.5f;
    Pose6DoF presentPose;
    presentPose.orientation = {std::cos(half), 0.0f, std::sin(half), 0.0f};

    auto qWarp = AtwReprojector::computeWarpQuaternion(renderPose.orientation, presentPose.orientation);
    assert(std::abs(qWarp.w - std::cos(half)) < 1e-4f);
    assert(std::abs(qWarp.y - std::sin(half)) < 1e-4f);

    auto warpMat = AtwReprojector::computeReprojectionMatrix(renderPose, presentPose);

    // Vertex directly ahead at (0, 0, -1) should be warped in X by -sin(angle)
    Vertex3D forwardVertex{0.0f, 0.0f, -1.0f, 0.5f, 0.5f, 0.0f, 0.0f, 1.0f};
    auto warped = AtwReprojector::warpVertex(forwardVertex, warpMat);

    // When head turned right, forward virtual point shifts to the left in camera space
    assert(warped.x != forwardVertex.x);
    float length = std::sqrt(warped.x * warped.x + warped.y * warped.y + warped.z * warped.z);
    assert(std::abs(length - 1.0f) < 0.05f);

    std::cout << "[PASS] testAtwDifferentialRotationWarp\n";
}

void testAtwTimingAndLatencyBenchmark() {
    AtwReprojector reprojector;
    assert(AtwReprojector::TARGET_REFRESH_RATE_HZ == 120);
    assert(AtwReprojector::VSYNC_PERIOD_US == 8333);

    // Motion-to-photon latency must be under 18ms
    float latencyMs = reprojector.getAverageMotionToPhotonLatencyMs();
    assert(latencyMs < 18.0f);
    std::cout << "Motion-to-Photon Latency: " << latencyMs << " ms (Target < 18.0 ms)\n";

    // Test OpenXR shim
    OpenXrShim shim;
    assert(shim.initializeRuntime() == XR_SUCCESS);
    assert(shim.isRuntimeActive());

    XrPosef headPose;
    assert(shim.locateHeadSpace(1000000ULL, headPose) == XR_SUCCESS);
    assert(headPose.orientation.w == 1.0f);

    shim.shutdownRuntime();
    assert(!shim.isRuntimeActive());

    std::cout << "[PASS] testAtwTimingAndLatencyBenchmark\n";
}

#ifndef SPATIAL_COMBINED_TEST_RUNNER
int main() {
    std::cout << "--- Running Phase 8 Native Tests ---\n";
    testAtwIdentityWarp();
    testAtwDifferentialRotationWarp();
    testAtwTimingAndLatencyBenchmark();
    std::cout << "All Phase 8 Native Tests Passed!\n";
    return 0;
}
#endif
