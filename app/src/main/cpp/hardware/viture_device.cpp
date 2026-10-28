#include "viture_device.hpp"
#include <cstring>

namespace spatial::hardware {

VitureDevice::VitureDevice(std::shared_ptr<IUsbTransport> transport)
    : transport_(std::move(transport)) {}

VitureDevice::~VitureDevice() {
    disconnect();
}

bool VitureDevice::initialize(int usbFd) {
    if (transport_) {
        initialized_ = transport_->isConnected();
        if (!initialized_) {
            initialized_ = transport_->openDevice(VITURE_VENDOR_ID, VITURE_LUMA_PRODUCT_ID);
        }
        return initialized_;
    }
    // Host mock or fallback when no transport provided
    initialized_ = (usbFd >= 0);
    return initialized_;
}

void VitureDevice::disconnect() {
    if (initialized_ && transport_) {
        // Safe tear down: revert to 2D
        set3DMode(Display3DMode::Mode2D);
        transport_->closeDevice();
    }
    initialized_ = false;
}

bool VitureDevice::isConnected() const {
    if (transport_) {
        return transport_->isConnected();
    }
    return initialized_;
}

bool VitureDevice::set3DMode(Display3DMode mode) {
    auto packet = ViturePacket::create3DSwitchPacket(mode);
    if (!transport_) {
        currentMode_ = mode;
        return true;
    }

    // Standard VITURE vendor control transfer:
    // RequestType: 0x21 (Host to Device | Class | Interface)
    // Request: 0x09 (SET_REPORT)
    // Value: 0x0300 (Feature Report)
    // Index: 0x00
    int transferred = transport_->controlTransfer(
        0x21, 0x09, 0x0300, 0x00,
        packet.data, sizeof(packet.data), 500
    );

    if (transferred >= 0) {
        currentMode_ = mode;
        return true;
    }
    return false;
}

bool VitureDevice::setBrightness(uint8_t level) {
    auto packet = ViturePacket::createBrightnessPacket(level);
    if (!transport_) {
        return true;
    }

    int transferred = transport_->controlTransfer(
        0x21, 0x09, 0x0300, 0x00,
        packet.data, sizeof(packet.data), 500
    );
    return (transferred >= 0);
}

} // namespace spatial::hardware
