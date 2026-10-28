#pragma once

#include <cstdint>
#include <vector>
#include <array>
#include <memory>
#include <string>

namespace spatial::graphics {

struct ViewportConfig {
    float x;
    float y;
    float width;
    float height;
    float minDepth;
    float maxDepth;
};

struct StereoFrameConfig {
    static constexpr uint32_t TOTAL_WIDTH = 3840;
    static constexpr uint32_t TOTAL_HEIGHT = 1080;
    static constexpr uint32_t EYE_WIDTH = 1920;
    static constexpr uint32_t EYE_HEIGHT = 1080;
    static constexpr uint32_t REFRESH_RATE_HZ = 120;

    static ViewportConfig getLeftEyeViewport() {
        return ViewportConfig{0.0f, 0.0f, static_cast<float>(EYE_WIDTH), static_cast<float>(EYE_HEIGHT), 0.0f, 1.0f};
    }

    static ViewportConfig getRightEyeViewport() {
        return ViewportConfig{static_cast<float>(EYE_WIDTH), 0.0f, static_cast<float>(EYE_WIDTH), static_cast<float>(EYE_HEIGHT), 0.0f, 1.0f};
    }
};

struct StereoColorRGBA {
    float r;
    float g;
    float b;
    float a;
};

struct CalibrationPattern {
    static constexpr StereoColorRGBA LEFT_EYE_COLOR{1.0f, 0.0f, 0.0f, 1.0f};  // Pure Red
    static constexpr StereoColorRGBA RIGHT_EYE_COLOR{0.0f, 1.0f, 1.0f, 1.0f}; // Pure Cyan

    // Binocular fusion test: Red (1,0,0) + Cyan (0,1,1) = White (1,1,1)
    static StereoColorRGBA computeFusedPerception(const StereoColorRGBA& left, const StereoColorRGBA& right) {
        return StereoColorRGBA{
            std::min(1.0f, left.r + right.r),
            std::min(1.0f, left.g + right.g),
            std::min(1.0f, left.b + right.b),
            1.0f
        };
    }
};

class VulkanCompositor {
public:
    VulkanCompositor();
    ~VulkanCompositor();

    bool initialize(void* nativeWindow = nullptr);
    void shutdown();
    bool isInitialized() const { return initialized_; }

    void renderCalibrationFrame();
    void renderStereoFrame(const float* leftViewProj, const float* rightViewProj);

    const ViewportConfig& getLeftViewport() const { return leftViewport_; }
    const ViewportConfig& getRightViewport() const { return rightViewport_; }

    uint32_t getWidth() const { return StereoFrameConfig::TOTAL_WIDTH; }
    uint32_t getHeight() const { return StereoFrameConfig::TOTAL_HEIGHT; }

    bool isMultiviewSupported() const { return multiviewSupported_; }
    void setMultiviewSupported(bool supported) { multiviewSupported_ = supported; }

    uint64_t getRenderedFrameCount() const { return frameCount_; }

private:
    bool initialized_{false};
    bool multiviewSupported_{true};
    uint64_t frameCount_{0};
    void* nativeWindow_{nullptr};
    ViewportConfig leftViewport_{StereoFrameConfig::getLeftEyeViewport()};
    ViewportConfig rightViewport_{StereoFrameConfig::getRightEyeViewport()};
};

} // namespace spatial::graphics
