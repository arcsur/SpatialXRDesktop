#include <cassert>
#include <iostream>
#include <memory>
#include <vector>
#include <cmath>

#include "hardware/viture_device.hpp"
#include "graphics/vulkan_compositor.hpp"
#include "graphics/multiview_pipeline.hpp"
#include "graphics/quad_renderer.hpp"

using namespace spatial::hardware;
using namespace spatial::graphics;

class MockUsbTransport : public IUsbTransport {
public:
    bool openDevice(uint16_t vid, uint16_t pid) override {
        openedVid = vid;
        openedPid = pid;
        connected = true;
        return true;
    }

    void closeDevice() override {
        connected = false;
    }

    bool isConnected() const override {
        return connected;
    }

    int controlTransfer(uint8_t requestType, uint8_t request,
                        uint16_t value, uint16_t index,
                        uint8_t* buffer, uint16_t length,
                        uint32_t /*timeoutMs*/) override {
        lastRequestType = requestType;
        lastRequest = request;
        lastValue = value;
        lastIndex = index;
        lastSentData.assign(buffer, buffer + length);
        return length;
    }

    int bulkOrInterruptWrite(uint8_t /*endpoint*/, const uint8_t* /*buffer*/,
                             size_t length, uint32_t /*timeoutMs*/) override {
        return static_cast<int>(length);
    }

    int bulkOrInterruptRead(uint8_t /*endpoint*/, uint8_t* /*buffer*/,
                            size_t length, uint32_t /*timeoutMs*/) override {
        return static_cast<int>(length);
    }

    bool connected{false};
    uint16_t openedVid{0};
    uint16_t openedPid{0};
    uint8_t lastRequestType{0};
    uint8_t lastRequest{0};
    uint16_t lastValue{0};
    uint16_t lastIndex{0};
    std::vector<uint8_t> lastSentData;
};

void testViture3DSBSToggle() {
    auto mockTransport = std::make_shared<MockUsbTransport>();
    VitureDevice device(mockTransport);

    assert(device.initialize());
    assert(mockTransport->openedVid == VITURE_VENDOR_ID);
    assert(mockTransport->openedPid == VITURE_LUMA_PRODUCT_ID);
    assert(device.isConnected());

    // Switch to 3D SBS
    bool ok = device.set3DMode(Display3DMode::Mode3DSideBySide);
    assert(ok);
    assert(device.getCurrentMode() == Display3DMode::Mode3DSideBySide);
    assert(mockTransport->lastSentData.size() == VITURE_PACKET_SIZE);
    assert(mockTransport->lastSentData[0] == 0x00);
    assert(mockTransport->lastSentData[1] == 0xA1);
    assert(mockTransport->lastSentData[2] == 0x01);
    assert(mockTransport->lastSentData[3] == 0x01); // SBS ON

    // Switch back to 2D
    ok = device.set3DMode(Display3DMode::Mode2D);
    assert(ok);
    assert(device.getCurrentMode() == Display3DMode::Mode2D);
    assert(mockTransport->lastSentData[3] == 0x00); // 2D OFF

    device.disconnect();
    assert(!device.isConnected());
    std::cout << "[PASS] testViture3DSBSToggle\n";
}

void testVulkanCompositorViewports() {
    VulkanCompositor compositor;
    assert(compositor.initialize(nullptr));
    assert(compositor.getWidth() == 3840);
    assert(compositor.getHeight() == 1080);

    const auto& leftVp = compositor.getLeftViewport();
    assert(leftVp.x == 0.0f);
    assert(leftVp.y == 0.0f);
    assert(leftVp.width == 1920.0f);
    assert(leftVp.height == 1080.0f);

    const auto& rightVp = compositor.getRightViewport();
    assert(rightVp.x == 1920.0f);
    assert(rightVp.y == 0.0f);
    assert(rightVp.width == 1920.0f);
    assert(rightVp.height == 1080.0f);

    compositor.renderCalibrationFrame();
    assert(compositor.getRenderedFrameCount() == 1);

    // Test binocular fusion color math
    auto fused = CalibrationPattern::computeFusedPerception(
        CalibrationPattern::LEFT_EYE_COLOR,
        CalibrationPattern::RIGHT_EYE_COLOR
    );
    assert(fused.r == 1.0f);
    assert(fused.g == 1.0f);
    assert(fused.b == 1.0f);
    assert(fused.a == 1.0f);

    compositor.shutdown();
    assert(!compositor.isInitialized());
    std::cout << "[PASS] testVulkanCompositorViewports\n";
}

void testMultiviewAndQuadRenderer() {
    // Stereo matrices check
    auto stereo = MultiviewPipeline::computeStereoMatrices(0.064f, 45.0f, 0.05f, 100.0f);
    assert(stereo.leftView[12] == 0.032f);   // +half IPD
    assert(stereo.rightView[12] == -0.032f); // -half IPD

    // Quad mesh generation
    auto mesh = QuadRenderer::generatePlanarQuadMesh(1.2f, 0.8f);
    assert(mesh.size() == 6); // 2 triangles

    // Raycast intersection test
    std::array<float, 3> origin{0.0f, 0.0f, 2.0f};
    std::array<float, 3> dir{0.0f, 0.0f, -1.0f}; // Ray pointed directly at center
    float hitDist = 0.0f, u = 0.0f, v = 0.0f;

    bool hit = QuadRenderer::rayIntersectsQuad(origin, dir, 1.0f, 1.0f, hitDist, u, v);
    assert(hit);
    assert(std::abs(hitDist - 2.0f) < 1e-4f);
    assert(std::abs(u - 0.5f) < 1e-4f);
    assert(std::abs(v - 0.5f) < 1e-4f);

    // Raycast miss outside bounds
    std::array<float, 3> missDir{2.0f, 0.0f, -1.0f};
    hit = QuadRenderer::rayIntersectsQuad(origin, missDir, 1.0f, 1.0f, hitDist, u, v);
    assert(!hit);

    std::cout << "[PASS] testMultiviewAndQuadRenderer\n";
}

#ifndef SPATIAL_COMBINED_TEST_RUNNER
int main() {
    std::cout << "--- Running Phase 1 Native Tests ---\n";
    testViture3DSBSToggle();
    testVulkanCompositorViewports();
    testMultiviewAndQuadRenderer();
    std::cout << "All Phase 1 Native Tests Passed!\n";
    return 0;
}
#endif
