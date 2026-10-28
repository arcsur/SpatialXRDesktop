#include "vulkan_compositor.hpp"
#include <algorithm>

namespace spatial::graphics {

VulkanCompositor::VulkanCompositor() = default;

VulkanCompositor::~VulkanCompositor() {
    shutdown();
}

bool VulkanCompositor::initialize(void* nativeWindow) {
    nativeWindow_ = nativeWindow;
    leftViewport_ = StereoFrameConfig::getLeftEyeViewport();
    rightViewport_ = StereoFrameConfig::getRightEyeViewport();
    initialized_ = true;
    frameCount_ = 0;
    return true;
}

void VulkanCompositor::shutdown() {
    initialized_ = false;
    nativeWindow_ = nullptr;
}

void VulkanCompositor::renderCalibrationFrame() {
    if (!initialized_) return;
    // In full Vulkan device pipeline:
    // Left eye command buffer clears left viewport with LEFT_EYE_COLOR (1, 0, 0, 1)
    // Right eye command buffer clears right viewport with RIGHT_EYE_COLOR (0, 1, 1, 1)
    // Presentation displays side-by-side on 3840x1080 panel.
    frameCount_++;
}

void VulkanCompositor::renderStereoFrame(const float* /*leftViewProj*/, const float* /*rightViewProj*/) {
    if (!initialized_) return;
    frameCount_++;
}

} // namespace spatial::graphics
