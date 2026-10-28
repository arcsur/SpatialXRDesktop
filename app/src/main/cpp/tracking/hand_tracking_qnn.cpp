#include "hand_tracking_qnn.hpp"
#include <cmath>

namespace spatial::tracking {

HandTrackingQnn::HandTrackingQnn() = default;

HandTrackingQnn::~HandTrackingQnn() = default;

bool HandTrackingQnn::initialize(const std::string& /*palmModelPath*/, const std::string& /*landmarkModelPath*/) {
    initialized_ = true;
    leftPinching_ = false;
    rightPinching_ = false;
    return true;
}

void HandTrackingQnn::updateHandJoints(bool isLeft, const std::array<Joint3D, JOINT_COUNT>& joints, float confidence) {
    HandSkeleton& hand = isLeft ? leftHand_ : rightHand_;
    hand.isTracked = (confidence > 0.3f);
    hand.isLeft = isLeft;
    hand.joints = joints;
    hand.trackingConfidence = confidence;
}

float HandTrackingQnn::euclideanDistance(const Joint3D& a, const Joint3D& b) {
    float dx = a.x - b.x;
    float dy = a.y - b.y;
    float dz = a.z - b.z;
    return std::sqrt(dx * dx + dy * dy + dz * dz);
}

std::array<float, 3> HandTrackingQnn::computeCrossProduct(const std::array<float, 3>& a, const std::array<float, 3>& b) {
    return {
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    };
}

float HandTrackingQnn::computeDotProduct(const std::array<float, 3>& a, const std::array<float, 3>& b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

PointerRay HandTrackingQnn::computeIndexPointerRay(const HandSkeleton& hand) {
    PointerRay ray{};
    if (!hand.isTracked) return ray;

    const auto& wrist = hand.joints[WRIST];
    const auto& mcp = hand.joints[INDEX_FINGER_MCP];
    const auto& tip = hand.joints[INDEX_FINGER_TIP];

    // Ray origin: Midpoint between wrist and index MCP
    ray.origin[0] = (wrist.x + mcp.x) * 0.5f;
    ray.origin[1] = (wrist.y + mcp.y) * 0.5f;
    ray.origin[2] = (wrist.z + mcp.z) * 0.5f;

    // Ray direction: Vector pointing from index MCP to index tip
    float dx = tip.x - mcp.x;
    float dy = tip.y - mcp.y;
    float dz = tip.z - mcp.z;
    float len = std::sqrt(dx * dx + dy * dy + dz * dz);

    if (len > 1e-6f) {
        ray.direction[0] = dx / len;
        ray.direction[1] = dy / len;
        ray.direction[2] = dz / len;
    }

    return ray;
}

PinchPhase HandTrackingQnn::updatePinchGesture(bool isLeft) {
    const HandSkeleton& hand = isLeft ? leftHand_ : rightHand_;
    bool& currentlyPinching = isLeft ? leftPinching_ : rightPinching_;

    if (!hand.isTracked) {
        if (currentlyPinching) {
            currentlyPinching = false;
            return PinchPhase::PinchUp;
        }
        return PinchPhase::None;
    }

    const auto& indexTip = hand.joints[INDEX_FINGER_TIP];
    const auto& thumbTip = hand.joints[THUMB_TIP];

    float dist = euclideanDistance(indexTip, thumbTip);

    if (!currentlyPinching) {
        if (dist < PINCH_DOWN_THRESHOLD_METERS) { // < 20mm
            currentlyPinching = true;
            return PinchPhase::PinchDown;
        }
        return PinchPhase::None;
    } else {
        if (dist > PINCH_UP_THRESHOLD_METERS) { // > 28mm
            currentlyPinching = false;
            return PinchPhase::PinchUp;
        }
        return PinchPhase::PinchHold;
    }
}

bool HandTrackingQnn::isLookingAtPalm(const HandSkeleton& hand, const std::array<float, 3>& gazeDirection) {
    if (!hand.isTracked) return false;

    const auto& wrist = hand.joints[WRIST];
    const auto& indexMcp = hand.joints[INDEX_FINGER_MCP];
    const auto& pinkyMcp = hand.joints[PINKY_MCP];

    // Vector 1: wrist -> index_mcp
    std::array<float, 3> v1 = {
        indexMcp.x - wrist.x,
        indexMcp.y - wrist.y,
        indexMcp.z - wrist.z
    };

    // Vector 2: wrist -> pinky_mcp
    std::array<float, 3> v2 = {
        pinkyMcp.x - wrist.x,
        pinkyMcp.y - wrist.y,
        pinkyMcp.z - wrist.z
    };

    // Palm normal from spec: (index_mcp - wrist) x (pinky_mcp - wrist)
    std::array<float, 3> normal = computeCrossProduct(v1, v2);

    float normLen = std::sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
    if (normLen < 1e-6f) return false;

    normal[0] /= normLen;
    normal[1] /= normLen;
    normal[2] /= normLen;

    // Normal dot Gaze
    float alignment = computeDotProduct(normal, gazeDirection);
    return (alignment < PALM_GAZE_ALIGNMENT_THRESHOLD);
}

bool HandTrackingQnn::computeTwoHandScale(
    const HandSkeleton& leftHand,
    const HandSkeleton& rightHand,
    float initialSeparationMeters,
    float& outScaleFactor
) {
    if (!leftHand.isTracked || !rightHand.isTracked || initialSeparationMeters <= 0.001f) {
        return false;
    }

    const auto& leftPinchCenter = leftHand.joints[INDEX_FINGER_TIP];
    const auto& rightPinchCenter = rightHand.joints[INDEX_FINGER_TIP];

    float currentDist = euclideanDistance(leftPinchCenter, rightPinchCenter);
    outScaleFactor = currentDist / initialSeparationMeters;
    return true;
}

bool HandTrackingQnn::computeTwoHandTransform(
    const std::array<float, 3>& initHand1,
    const std::array<float, 3>& initHand2,
    const std::array<float, 3>& currHand1,
    const std::array<float, 3>& currHand2,
    TwoHandTransform& outTransform
) {
    float initDx = initHand2[0] - initHand1[0];
    float initDy = initHand2[1] - initHand1[1];
    float initDz = initHand2[2] - initHand1[2];
    float initDist = std::sqrt(initDx * initDx + initDy * initDy + initDz * initDz);
    if (initDist <= 0.001f) return false;

    float currDx = currHand2[0] - currHand1[0];
    float currDy = currHand2[1] - currHand1[1];
    float currDz = currHand2[2] - currHand1[2];
    float currDist = std::sqrt(currDx * currDx + currDy * currDy + currDz * currDz);

    outTransform.scaleFactor = currDist / initDist;

    // Translation delta from midpoint shift
    std::array<float, 3> initMid = {
        (initHand1[0] + initHand2[0]) * 0.5f,
        (initHand1[1] + initHand2[1]) * 0.5f,
        (initHand1[2] + initHand2[2]) * 0.5f
    };
    std::array<float, 3> currMid = {
        (currHand1[0] + currHand2[0]) * 0.5f,
        (currHand1[1] + currHand2[1]) * 0.5f,
        (currHand1[2] + currHand2[2]) * 0.5f
    };
    outTransform.translationDelta = {
        currMid[0] - initMid[0],
        currMid[1] - initMid[1],
        currMid[2] - initMid[2]
    };

    // Yaw rotation delta in XZ plane: positive yaw turns right side further (-Z) and left side closer (+Z)
    bool hand1IsLeft = initHand1[0] <= initHand2[0];
    const auto& initLeft = hand1IsLeft ? initHand1 : initHand2;
    const auto& initRight = hand1IsLeft ? initHand2 : initHand1;
    const auto& currLeft = hand1IsLeft ? currHand1 : currHand2;
    const auto& currRight = hand1IsLeft ? currHand2 : currHand1;

    float initAngle = std::atan2(initRight[2] - initLeft[2], initRight[0] - initLeft[0]);
    float currAngle = std::atan2(currRight[2] - currLeft[2], currRight[0] - currLeft[0]);
    outTransform.yawDeltaRadians = initAngle - currAngle;

    return true;
}

} // namespace spatial::tracking
