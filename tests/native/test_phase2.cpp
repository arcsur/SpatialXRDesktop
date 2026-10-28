#include <cassert>
#include <iostream>
#include <vector>
#include <cmath>

#include "hardware/imu_worker.hpp"
#include "hardware/uvc_streamer.hpp"
#include "tracking/pose_predictor.hpp"
#include "tracking/vio_pose_fusion.hpp"

using namespace spatial::hardware;
using namespace spatial::tracking;

void testImuWorkerPacketParsing() {
    ImuWorker worker;

    // Construct synthetic 64-byte Viture IMU packet
    // Bytes 2-7: Gyro (gx, gy, gz)
    // Bytes 8-13: Accel (ax, ay, az)
    uint8_t packet[64]{0};
    int16_t rawGx = 1000;
    packet[2] = static_cast<uint8_t>(rawGx & 0xFF);
    packet[3] = static_cast<uint8_t>((rawGx >> 8) & 0xFF);

    int16_t rawAz = 16384; // ~ 9.8 m/s^2 (1g)
    packet[12] = static_cast<uint8_t>(rawAz & 0xFF);
    packet[13] = static_cast<uint8_t>((rawAz >> 8) & 0xFF);

    worker.processRawImuPacket(packet, sizeof(packet), 1000000ULL);
    auto sample = worker.getLatestSample();
    assert(sample.timestampNs == 1000000ULL);
    assert(sample.gyro[0] > 0.0f);
    assert(sample.accel[2] > 9.0f); // ~9.8 m/s^2

    // Integrate constant yaw rotation around Z axis at 1 rad/s for 1 second (1000 steps of 1ms)
    ImuWorker yawWorker;
    uint64_t t = 1000000ULL;
    for (int i = 0; i < 1000; ++i) {
        ImuSample s;
        s.timestampNs = t;
        s.gyro = {0.0f, 0.0f, 1.0f}; // 1 rad/s around Z
        s.accel = {0.0f, 9.8f, 0.0f};
        yawWorker.processSample(s);
        t += 1000000ULL; // +1ms
    }

    auto finalQ = yawWorker.getCurrentOrientation();
    // Integrated angle ~ 1.0 radian (~57.3 degrees)
    // For Z-rotation, qz = sin(theta/2) = sin(0.5) ~ 0.4794, qw = cos(0.5) ~ 0.8775
    assert(std::abs(finalQ.z - std::sin(0.5f)) < 0.05f);
    assert(std::abs(finalQ.w - std::cos(0.5f)) < 0.05f);

    std::cout << "[PASS] testImuWorkerPacketParsing\n";
}

void testPosePredictorLateLatch() {
    PosePredictor predictor;
    assert(predictor.getRecordedCount() == 0);

    Pose6DoF p0;
    p0.timestampNs = 1000000000ULL; // 1.0s
    p0.position = {1.0f, 2.0f, 3.0f};
    p0.linearVelocity = {0.5f, 0.0f, 0.0f}; // 0.5 m/s in X
    p0.orientation = {1.0f, 0.0f, 0.0f, 0.0f};
    p0.angularVelocity = {0.0f, 1.0f, 0.0f}; // 1.0 rad/s in Y

    predictor.recordPose(p0);
    assert(predictor.getRecordedCount() == 1);

    Pose6DoF latest;
    assert(predictor.getLatestPose(latest));
    assert(latest.timestampNs == p0.timestampNs);

    // Predict forward by 16.6ms (1 frame @ 60Hz = 16,666,666 ns)
    uint64_t targetTime = p0.timestampNs + 16666666ULL;
    Pose6DoF predicted;
    assert(predictor.predictPose(targetTime, predicted));

    float dt = 0.016666666f;
    float expectedX = 1.0f + 0.5f * dt;
    assert(std::abs(predicted.position[0] - expectedX) < 1e-4f);
    assert(predicted.position[1] == 2.0f);
    assert(predicted.position[2] == 3.0f);

    // Orientation predicted forward in Y
    float halfTheta = 1.0f * dt * 0.5f;
    assert(std::abs(predicted.orientation.y - std::sin(halfTheta)) < 1e-4f);

    // Test ring buffer wrapping past 128 items
    for (uint64_t i = 1; i <= 200; ++i) {
        Pose6DoF p;
        p.timestampNs = p0.timestampNs + i * 1000000ULL;
        p.position = {static_cast<float>(i), 0.0f, 0.0f};
        predictor.recordPose(p);
    }
    assert(predictor.getRecordedCount() == 201);
    assert(predictor.getLatestPose(latest));
    assert(latest.position[0] == 200.0f);

    std::cout << "[PASS] testPosePredictorLateLatch\n";
}

void testFastCornerDetectorAndVio() {
    // Generate 64x64 synthetic image with 4 distinct bright squares (corners)
    std::vector<uint8_t> image(64 * 64, 20); // dark background
    // Draw 10x10 bright square at (20, 20)
    for (int y = 20; y < 30; ++y) {
        for (int x = 20; x < 30; ++x) {
            image[y * 64 + x] = 220;
        }
    }

    auto corners = FastCornerDetector::detectCorners(image.data(), 64, 64, 30);
    assert(!corners.empty());

    // Test VIO EKF
    VioPoseFusion vio;
    vio.initialize({0.0f, 0.0f, 0.0f});

    // 10 IMU prediction steps (stationary)
    for (int i = 0; i < 10; ++i) {
        ImuSample s;
        s.timestampNs = (i + 1) * 1000000ULL;
        s.gyro = {0.0f, 0.0f, 0.0f};
        s.accel = {0.0f, 9.80665f, 0.0f}; // balance gravity
        vio.predictWithImu(s, 0.01f);
    }

    // Measurement update from visual translation
    std::array<float, 3> visualDelta{0.15f, 0.0f, 0.0f};
    vio.updateVisualMeasurement(visualDelta, 0.95f);

    auto pose = vio.getCurrentPose(10000000ULL);
    assert(pose.position[0] > 0.10f); // Pulled towards visual observation

    std::cout << "[PASS] testFastCornerDetectorAndVio\n";
}

void testUvcStreamer() {
    UvcStreamer streamer;
    assert(streamer.openDevice("/dev/video0", 640, 480, 60));
    assert(streamer.isStreaming());

    bool received = false;
    streamer.setFrameCallback([&received](const CameraFrame& frame) {
        if (frame.width == 640 && frame.height == 480) {
            received = true;
        }
    });

    CameraFrame frame;
    frame.width = 640;
    frame.height = 480;
    frame.yPlane.resize(640 * 480, 128);
    streamer.injectTestFrame(frame);

    assert(received);
    assert(streamer.getProcessedFrameCount() == 1);
    streamer.closeDevice();
    assert(!streamer.isStreaming());

    std::cout << "[PASS] testUvcStreamer\n";
}

#ifndef SPATIAL_COMBINED_TEST_RUNNER
int main() {
    std::cout << "--- Running Phase 2 Native Tests ---\n";
    testImuWorkerPacketParsing();
    testPosePredictorLateLatch();
    testFastCornerDetectorAndVio();
    testUvcStreamer();
    std::cout << "All Phase 2 Native Tests Passed!\n";
    return 0;
}
#endif
