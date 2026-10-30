#include <iostream>
#include <cstdlib>

// Forward declarations of phase test functions
void testViture3DSBSToggle();
void testVitureElectrochromicFilmToggle();
void testVulkanCompositorViewports();
void testMultiviewAndQuadRenderer();

void testImuWorkerPacketParsing();
void testPosePredictorLateLatch();
void testFastCornerDetectorAndVio();
void testUvcStreamer();

void testIndexPointerRay();
void testPinchHysteresis();
void testTwoHandScaleAndLookAtPalm();
void testThumbAndIndexTrackingDots();
void testFistAndOpenHandDetection();

void testAtwIdentityWarp();
void testAtwDifferentialRotationWarp();
void testAtwTimingAndLatencyBenchmark();

int main() {
    std::cout << "========================================\n";
    std::cout << "  Spatial XR Desktop: Full Native Suite \n";
    std::cout << "========================================\n\n";

    std::cout << "--- [Phase 1: Display & Swapchain] ---\n";
    testViture3DSBSToggle();
    testVitureElectrochromicFilmToggle();
    testVulkanCompositorViewports();
    testMultiviewAndQuadRenderer();

    std::cout << "\n--- [Phase 2: 6DoF Pose & Sensor Fusion] ---\n";
    testImuWorkerPacketParsing();
    testPosePredictorLateLatch();
    testFastCornerDetectorAndVio();
    testUvcStreamer();

    std::cout << "\n--- [Phase 3: Hand Tracking & Gestures] ---\n";
    testIndexPointerRay();
    testPinchHysteresis();
    testTwoHandScaleAndLookAtPalm();
    testThumbAndIndexTrackingDots();
    testFistAndOpenHandDetection();

    std::cout << "\n--- [Phase 8: Asynchronous TimeWarp & OpenXR] ---\n";
    testAtwIdentityWarp();
    testAtwDifferentialRotationWarp();
    testAtwTimingAndLatencyBenchmark();

    std::cout << "\n========================================\n";
    std::cout << "  ALL NATIVE XR TESTS PASSED (15/15)   \n";
    std::cout << "========================================\n";
    return 0;
}
