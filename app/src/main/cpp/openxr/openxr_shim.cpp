#include "openxr_shim.hpp"

namespace spatial::openxr {

OpenXrShim::OpenXrShim() = default;

OpenXrShim::~OpenXrShim() {
    shutdownRuntime();
}

XrResult OpenXrShim::initializeRuntime() {
    isActive_ = true;
    return XR_SUCCESS;
}

void OpenXrShim::shutdownRuntime() {
    isActive_ = false;
}

XrResult OpenXrShim::locateHeadSpace(uint64_t /*timeNs*/, XrPosef& outPose) {
    if (!isActive_) return XR_ERROR_SESSION_NOT_RUNNING;
    outPose.position = {0.0f, 0.0f, 0.0f};
    outPose.orientation = {0.0f, 0.0f, 0.0f, 1.0f}; // x, y, z, w
    return XR_SUCCESS;
}

} // namespace spatial::openxr
