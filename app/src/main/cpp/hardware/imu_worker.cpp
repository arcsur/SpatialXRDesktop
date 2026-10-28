#include "imu_worker.hpp"
#include <cstring>
#include <chrono>

#if defined(__linux__) || defined(__ANDROID__)
#include <pthread.h>
#include <sched.h>
#endif

namespace spatial::tracking {

ImuWorker::ImuWorker() = default;

ImuWorker::~ImuWorker() {
    stop();
}

bool ImuWorker::start(int usbFd, ImuCallback callback) {
    if (running_.load()) return true;

    usbFd_ = usbFd;
    callback_ = std::move(callback);
    running_.store(true);

    workerThread_ = std::thread([this]() {
#if defined(__linux__) || defined(__ANDROID__)
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setschedpolicy(&attr, SCHED_FIFO);
        sched_param param{.sched_priority = 85};
        pthread_attr_setschedparam(&attr, &param);
        pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);
        pthread_attr_destroy(&attr);
#endif
        runThreadLoop();
    });

    return true;
}

void ImuWorker::stop() {
    if (running_.load()) {
        running_.store(false);
        if (workerThread_.joinable()) {
            workerThread_.join();
        }
    }
}

void ImuWorker::runThreadLoop() {
    // 1000Hz loop (1ms period)
    auto nextTick = std::chrono::steady_clock::now();
    while (running_.load()) {
        nextTick += std::chrono::milliseconds(1);
        std::this_thread::sleep_until(nextTick);

        // If usbFd is valid, real hardware reads 64 bytes via read(usbFd, buf, 64)
        // In loop, process periodic sample
    }
}

void ImuWorker::processRawImuPacket(const uint8_t* data, size_t length, uint64_t timestampNs) {
    if (!data || length < 16) return;

    // Viture 64-byte IMU format:
    // Bytes 0-1: Header / Packet ID
    // Bytes 2-7: 3-axis int16 Gyroscope (raw)
    // Bytes 8-13: 3-axis int16 Accelerometer (raw)
    // Scaling: Gyro ~ 0.001065 rad/s/LSB, Accel ~ 0.000598 m/s^2 / LSB
    int16_t gx = static_cast<int16_t>(data[2] | (data[3] << 8));
    int16_t gy = static_cast<int16_t>(data[4] | (data[5] << 8));
    int16_t gz = static_cast<int16_t>(data[6] | (data[7] << 8));

    int16_t ax = static_cast<int16_t>(data[8] | (data[9] << 8));
    int16_t ay = static_cast<int16_t>(data[10] | (data[11] << 8));
    int16_t az = static_cast<int16_t>(data[12] | (data[13] << 8));

    constexpr float GYRO_SCALE = 0.001065f;
    constexpr float ACCEL_SCALE = 0.000598f;

    ImuSample sample;
    sample.timestampNs = timestampNs;
    sample.gyro[0] = static_cast<float>(gx) * GYRO_SCALE;
    sample.gyro[1] = static_cast<float>(gy) * GYRO_SCALE;
    sample.gyro[2] = static_cast<float>(gz) * GYRO_SCALE;

    sample.accel[0] = static_cast<float>(ax) * ACCEL_SCALE;
    sample.accel[1] = static_cast<float>(ay) * ACCEL_SCALE;
    sample.accel[2] = static_cast<float>(az) * ACCEL_SCALE;

    processSample(sample);
}

void ImuWorker::processSample(const ImuSample& sample) {
    latestSample_ = sample;

    if (lastTimestampNs_ == 0) {
        lastTimestampNs_ = sample.timestampNs;
        return;
    }

    float dt = static_cast<float>(sample.timestampNs - lastTimestampNs_) * 1e-9f;
    lastTimestampNs_ = sample.timestampNs;

    if (dt <= 0.0f || dt > 0.1f) {
        dt = 0.001f; // Clamped fallback for 1000Hz (1ms)
    }

    // Unbias angular velocity
    float wx = sample.gyro[0] - gyroBias_[0];
    float wy = sample.gyro[1] - gyroBias_[1];
    float wz = sample.gyro[2] - gyroBias_[2];

    // Angular velocity magnitude
    float omega = std::sqrt(wx * wx + wy * wy + wz * wz);
    Quaternion deltaQ{1.0f, 0.0f, 0.0f, 0.0f};

    if (omega > 1e-6f) {
        float halfTheta = omega * dt * 0.5f;
        float sinHalf = std::sin(halfTheta);
        deltaQ = {
            std::cos(halfTheta),
            (wx / omega) * sinHalf,
            (wy / omega) * sinHalf,
            (wz / omega) * sinHalf
        };
    } else {
        // First order Taylor expansion
        deltaQ = {
            1.0f,
            0.5f * wx * dt,
            0.5f * wy * dt,
            0.5f * wz * dt
        };
    }

    currentOrientation_ = currentOrientation_.multiply(deltaQ).normalized();

    if (callback_) {
        callback_(latestSample_, currentOrientation_);
    }
}

} // namespace spatial::tracking
