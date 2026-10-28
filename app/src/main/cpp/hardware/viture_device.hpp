#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>
#include <string>
#include <functional>
#include <memory>

namespace spatial::hardware {

constexpr uint16_t VITURE_VENDOR_ID = 0x35CA;
constexpr uint16_t VITURE_LUMA_PRODUCT_ID = 0x1011;
constexpr uint8_t VITURE_ENDPOINT_OUT = 0x01;
constexpr uint8_t VITURE_ENDPOINT_IN = 0x81;
constexpr size_t VITURE_PACKET_SIZE = 64;

enum class Display3DMode : uint8_t {
    Mode2D = 0x00,
    Mode3DSideBySide = 0x01
};

struct ViturePacket {
    uint8_t data[VITURE_PACKET_SIZE]{0};

    static ViturePacket create3DSwitchPacket(Display3DMode mode) {
        ViturePacket packet{};
        packet.data[0] = 0x00;
        packet.data[1] = 0xA1;
        packet.data[2] = 0x01;
        packet.data[3] = static_cast<uint8_t>(mode);
        return packet;
    }

    static ViturePacket createBrightnessPacket(uint8_t brightnessLevel) {
        ViturePacket packet{};
        packet.data[0] = 0x00;
        packet.data[1] = 0xA2;
        packet.data[2] = 0x01;
        packet.data[3] = (brightnessLevel > 7) ? 7 : brightnessLevel;
        return packet;
    }
};

class IUsbTransport {
public:
    virtual ~IUsbTransport() = default;
    virtual bool openDevice(uint16_t vid, uint16_t pid) = 0;
    virtual void closeDevice() = 0;
    virtual bool isConnected() const = 0;
    virtual int controlTransfer(uint8_t requestType, uint8_t request,
                               uint16_t value, uint16_t index,
                               uint8_t* buffer, uint16_t length,
                               uint32_t timeoutMs) = 0;
    virtual int bulkOrInterruptWrite(uint8_t endpoint, const uint8_t* buffer,
                                     size_t length, uint32_t timeoutMs) = 0;
    virtual int bulkOrInterruptRead(uint8_t endpoint, uint8_t* buffer,
                                    size_t length, uint32_t timeoutMs) = 0;
};

class VitureDevice {
public:
    explicit VitureDevice(std::shared_ptr<IUsbTransport> transport = nullptr);
    ~VitureDevice();

    bool initialize(int usbFd = -1);
    void disconnect();
    bool isConnected() const;

    bool set3DMode(Display3DMode mode);
    bool setBrightness(uint8_t level);
    Display3DMode getCurrentMode() const { return currentMode_; }

    void setTransport(std::shared_ptr<IUsbTransport> transport) {
        transport_ = transport;
    }

private:
    std::shared_ptr<IUsbTransport> transport_;
    Display3DMode currentMode_{Display3DMode::Mode2D};
    bool initialized_{false};
};

} // namespace spatial::hardware
