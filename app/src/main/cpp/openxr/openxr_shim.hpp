#pragma once

#include <cstdint>
#include <string>

namespace spatial::openxr {

enum XrResult {
    XR_SUCCESS = 0,
    XR_ERROR_INSTANCE_LOST = -1,
    XR_ERROR_SESSION_NOT_RUNNING = -2,
    XR_ERROR_HANDLE_INVALID = -3
};

struct XrVector3f {
    float x, y, z;
};

struct XrQuaternionf {
    float x, y, z, w;
};

struct XrPosef {
    XrQuaternionf orientation;
    XrVector3f position;
};

class OpenXrShim {
public:
    OpenXrShim();
    ~OpenXrShim();

    XrResult initializeRuntime();
    void shutdownRuntime();
    bool isRuntimeActive() const { return isActive_; }

    XrResult locateHeadSpace(uint64_t timeNs, XrPosef& outPose);

private:
    bool isActive_{false};
};

} // namespace spatial::openxr
