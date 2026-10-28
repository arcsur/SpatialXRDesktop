#pragma once

#include <cstdint>
#include <vector>
#include <functional>
#include <atomic>
#include <thread>
#include <string>

namespace spatial::hardware {

struct CameraFrame {
    uint32_t width{640};
    uint32_t height{480};
    uint64_t timestampNs{0};
    std::vector<uint8_t> yPlane;
    std::vector<uint8_t> uvPlane;
};

class UvcStreamer {
public:
    using FrameCallback = std::function<void(const CameraFrame&)>;

    UvcStreamer();
    ~UvcStreamer();

    bool openDevice(const std::string& devNode = "/dev/video0", uint32_t width = 640, uint32_t height = 480, uint32_t fps = 60);
    void closeDevice();
    bool isStreaming() const { return isStreaming_.load(); }

    void setFrameCallback(FrameCallback callback) {
        callback_ = std::move(callback);
    }

    void injectTestFrame(const CameraFrame& frame);

    uint64_t getProcessedFrameCount() const { return frameCount_.load(); }

private:
    std::string devNode_;
    uint32_t width_{640};
    uint32_t height_{480};
    uint32_t fps_{60};
    std::atomic<bool> isStreaming_{false};
    std::atomic<uint64_t> frameCount_{0};
    FrameCallback callback_;
    std::thread streamThread_;
};

} // namespace spatial::hardware
