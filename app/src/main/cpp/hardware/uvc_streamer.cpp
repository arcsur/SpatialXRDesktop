#include "uvc_streamer.hpp"

namespace spatial::hardware {

UvcStreamer::UvcStreamer() = default;

UvcStreamer::~UvcStreamer() {
    closeDevice();
}

bool UvcStreamer::openDevice(const std::string& devNode, uint32_t width, uint32_t height, uint32_t fps) {
    if (isStreaming_.load()) return true;

    devNode_ = devNode;
    width_ = width;
    height_ = height;
    fps_ = fps;
    isStreaming_.store(true);

    return true;
}

void UvcStreamer::closeDevice() {
    if (isStreaming_.load()) {
        isStreaming_.store(false);
        if (streamThread_.joinable()) {
            streamThread_.join();
        }
    }
}

void UvcStreamer::injectTestFrame(const CameraFrame& frame) {
    frameCount_.fetch_add(1, std::memory_order_relaxed);
    if (callback_) {
        callback_(frame);
    }
}

} // namespace spatial::hardware
