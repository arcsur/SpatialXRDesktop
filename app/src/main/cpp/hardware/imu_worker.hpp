#pragma once

#include <cstdint>
#include <atomic>
#include <thread>
#include <functional>
#include <array>
#include <cmath>

namespace spatial::tracking {

struct ImuSample {
    uint64_t timestampNs{0};
    std::array<float, 3> accel{0.0f, 0.0f, 0.0f}; // m/s^2
    std::array<float, 3> gyro{0.0f, 0.0f, 0.0f};  // rad/s
    float temperatureC{25.0f};
};

struct Quaternion {
    float w{1.0f};
    float x{0.0f};
    float y{0.0f};
    float z{0.0f};

    Quaternion normalized() const {
        float norm = std::sqrt(w * w + x * x + y * y + z * z);
        if (norm < 1e-8f) return {1.0f, 0.0f, 0.0f, 0.0f};
        float inv = 1.0f / norm;
        return {w * inv, x * inv, y * inv, z * inv};
    }

    Quaternion multiply(const Quaternion& q) const {
        return {
            w * q.w - x * q.x - y * q.y - z * q.z,
            w * q.x + x * q.w + y * q.y - z * q.z,
            w * q.y - x * q.z + y * q.w + z * q.x,
            w * q.z + x * q.y - y * q.x + z * q.w
        };
    }

    static Quaternion fromAxisAngle(float ax, float ay, float az, float angleRad) {
        float halfAngle = angleRad * 0.5f;
        float sinHalf = std::sin(halfAngle);
        Quaternion q{
            std::cos(halfAngle),
            ax * sinHalf,
            ay * sinHalf,
            az * sinHalf
        };
        return q.normalized();
    }
};

class ImuWorker {
public:
    using ImuCallback = std::function<void(const ImuSample&, const Quaternion&)>;

    ImuWorker();
    ~ImuWorker();

    bool start(int usbFd = -1, ImuCallback callback = nullptr);
    void stop();
    bool isRunning() const { return running_.load(); }

    // Direct sample injection (useful for tests and synthetic feeds)
    void processRawImuPacket(const uint8_t* data, size_t length, uint64_t timestampNs);
    void processSample(const ImuSample& sample);

    Quaternion getCurrentOrientation() const { return currentOrientation_; }
    ImuSample getLatestSample() const { return latestSample_; }

    void setGyroBias(float bx, float by, float bz) {
        gyroBias_ = {bx, by, bz};
    }

private:
    std::atomic<bool> running_{false};
    std::thread workerThread_;
    ImuCallback callback_;
    int usbFd_{-1};

    ImuSample latestSample_{};
    Quaternion currentOrientation_{1.0f, 0.0f, 0.0f, 0.0f};
    uint64_t lastTimestampNs_{0};

    std::array<float, 3> gyroBias_{0.0f, 0.0f, 0.0f};
    std::array<float, 3> accelBias_{0.0f, 0.0f, 0.0f};

    void runThreadLoop();
};

} // namespace spatial::tracking
