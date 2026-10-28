#pragma once

#include <cstdint>
#include <array>
#include <vector>
#include <string>
#include <cmath>

namespace spatial::tracking {

enum HandJointIndex {
    WRIST = 0,
    THUMB_CMC = 1,
    THUMB_MCP = 2,
    THUMB_IP = 3,
    THUMB_TIP = 4,
    INDEX_FINGER_MCP = 5,
    INDEX_FINGER_PIP = 6,
    INDEX_FINGER_DIP = 7,
    INDEX_FINGER_TIP = 8,
    MIDDLE_FINGER_MCP = 9,
    MIDDLE_FINGER_PIP = 10,
    MIDDLE_FINGER_DIP = 11,
    MIDDLE_FINGER_TIP = 12,
    RING_FINGER_MCP = 13,
    RING_FINGER_PIP = 14,
    RING_FINGER_DIP = 15,
    RING_FINGER_TIP = 16,
    PINKY_MCP = 17,
    PINKY_PIP = 18,
    PINKY_DIP = 19,
    PINKY_TIP = 20,
    JOINT_COUNT = 21
};

struct Joint3D {
    float x{0.0f};
    float y{0.0f};
    float z{0.0f};
    float confidence{1.0f};
};

struct PointerRay {
    std::array<float, 3> origin{0.0f, 0.0f, 0.0f};
    std::array<float, 3> direction{0.0f, 0.0f, -1.0f};
};

struct HandSkeleton {
    bool isTracked{false};
    bool isLeft{false};
    std::array<Joint3D, JOINT_COUNT> joints{};
    float trackingConfidence{0.0f};
};

enum class PinchPhase {
    None,
    PinchDown,
    PinchHold,
    PinchUp
};

class HandTrackingQnn {
public:
    static constexpr float PINCH_DOWN_THRESHOLD_METERS = 0.020f; // 20mm
    static constexpr float PINCH_UP_THRESHOLD_METERS = 0.028f;   // 28mm
    static constexpr float PALM_GAZE_ALIGNMENT_THRESHOLD = -0.75f;

    HandTrackingQnn();
    ~HandTrackingQnn();

    bool initialize(const std::string& palmModelPath = "", const std::string& landmarkModelPath = "");

    void updateHandJoints(bool isLeft, const std::array<Joint3D, JOINT_COUNT>& joints, float confidence = 0.95f);

    const HandSkeleton& getHand(bool isLeft) const {
        return isLeft ? leftHand_ : rightHand_;
    }

    // Task 3.2: Spatial Pointer Ray
    static PointerRay computeIndexPointerRay(const HandSkeleton& hand);

    // Task 3.2: Pinch Gesture Classifier with Hysteresis
    PinchPhase updatePinchGesture(bool isLeft);
    bool isPinching(bool isLeft) const {
        return isLeft ? leftPinching_ : rightPinching_;
    }

    // Task 3.2: Look-at-Palm Detector
    static bool isLookingAtPalm(const HandSkeleton& hand, const std::array<float, 3>& gazeDirection = {0.0f, 0.0f, -1.0f});

    // Task 3.2: Two-Hand Scale Detector
    static bool computeTwoHandScale(
        const HandSkeleton& leftHand,
        const HandSkeleton& rightHand,
        float initialSeparationMeters,
        float& outScaleFactor
    );

    // Iron Man Two-Hand Simultaneous 3D Transform: Scale, Translation, and Yaw Angling
    struct TwoHandTransform {
        float scaleFactor{1.0f};
        std::array<float, 3> translationDelta{0.0f, 0.0f, 0.0f};
        float yawDeltaRadians{0.0f};
    };

    static bool computeTwoHandTransform(
        const std::array<float, 3>& initHand1,
        const std::array<float, 3>& initHand2,
        const std::array<float, 3>& currHand1,
        const std::array<float, 3>& currHand2,
        TwoHandTransform& outTransform
    );

    static float euclideanDistance(const Joint3D& a, const Joint3D& b);
    static std::array<float, 3> computeCrossProduct(const std::array<float, 3>& a, const std::array<float, 3>& b);
    static float computeDotProduct(const std::array<float, 3>& a, const std::array<float, 3>& b);

private:
    HandSkeleton leftHand_{};
    HandSkeleton rightHand_{};
    bool leftPinching_{false};
    bool rightPinching_{false};
    bool initialized_{false};
};

} // namespace spatial::tracking
