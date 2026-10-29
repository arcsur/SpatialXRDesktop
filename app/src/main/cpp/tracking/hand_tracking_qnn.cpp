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

bool HandTrackingQnn::processCameraFrame(int width, int height, const uint8_t* yPlane, const uint8_t* uvPlane) {
    if (!yPlane || width <= 0 || height <= 0) return false;

    // Scan lower 75% of frame where hands naturally appear in AR glasses FOV
    int startY = height / 4;
    int minHandPixels = (width * height) / 120; // At least ~0.8% of pixels

    auto detectHandOnSide = [&](bool isLeft) {
        int startX = isLeft ? 0 : width / 2;
        int endX = isLeft ? width / 2 : width;

        int count = 0;
        int64_t sumX = 0, sumY = 0;
        int minY = height, maxY = 0;
        int minX = endX, maxX = startX;
        int tipX = startX, tipY = height;

        // Subsample step for speed
        const int step = 4;
        for (int y = startY; y < height; y += step) {
            for (int x = startX; x < endX; x += step) {
                uint8_t yVal = yPlane[y * width + x];
                bool isSkin = false;

                if (uvPlane) {
                    // YUV420 semi-planar UV check
                    int uvIndex = (y / 2) * width + (x / 2) * 2;
                    uint8_t u = uvPlane[uvIndex];
                    uint8_t v = uvPlane[uvIndex + 1];
                    isSkin = (yVal > 40 && u >= 75 && u <= 135 && v >= 130 && v <= 180);
                } else {
                    // Grayscale intensity thresholding for foreground hands
                    isSkin = (yVal > 60 && yVal < 240);
                }

                if (isSkin) {
                    count++;
                    sumX += x;
                    sumY += y;
                    if (y < minY) {
                        minY = y;
                        tipX = x;
                        tipY = y;
                    }
                    if (y > maxY) maxY = y;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                }
            }
        }

        HandSkeleton& hand = isLeft ? leftHand_ : rightHand_;
        if (count >= (minHandPixels / (step * step))) {
            float cx = static_cast<float>(sumX) / count;
            float cy = static_cast<float>(sumY) / count;

            // Map camera pixels to metric 3D space in front of glasses
            auto to3D = [&](float px, float py) -> Joint3D {
                float normX = (px - width * 0.5f) / (width * 0.5f);
                float normY = (height * 0.5f - py) / (height * 0.5f);
                return {
                    normX * 0.28f, // +/- 28cm field
                    normY * 0.22f, // +/- 22cm field
                    -0.50f,        // 50cm in front of eyes
                    1.0f
                };
            };

            std::array<Joint3D, JOINT_COUNT> joints{};
            // Base of palm / wrist
            joints[WRIST] = to3D(cx, static_cast<float>(maxY));
            // Index finger MCP and TIP
            joints[INDEX_FINGER_MCP] = to3D(cx, cy - 25.0f);
            joints[INDEX_FINGER_TIP] = to3D(static_cast<float>(tipX), static_cast<float>(tipY));

            // Thumb MCP and TIP (thumb extends outward laterally)
            float thumbX = isLeft ? static_cast<float>(maxX) : static_cast<float>(minX);
            joints[THUMB_MCP] = to3D(cx + (isLeft ? 20.0f : -20.0f), cy);
            joints[THUMB_TIP] = to3D(thumbX, cy - 10.0f);

            // Pinky MCP (opposite side of palm from thumb)
            float pinkyX = isLeft ? static_cast<float>(minX) : static_cast<float>(maxX);
            joints[PINKY_MCP] = to3D(pinkyX, cy);
            joints[PINKY_TIP] = to3D(pinkyX, cy - 20.0f);

            // Middle and ring fingers for completeness
            joints[MIDDLE_FINGER_MCP] = to3D(cx, cy - 20.0f);
            joints[MIDDLE_FINGER_TIP] = to3D(cx, static_cast<float>(minY) - 5.0f);
            joints[RING_FINGER_MCP] = to3D(cx + (isLeft ? -10.0f : 10.0f), cy - 20.0f);
            joints[RING_FINGER_TIP] = to3D(cx + (isLeft ? -10.0f : 10.0f), static_cast<float>(minY));

            updateHandJoints(isLeft, joints, 0.95f);
            updatePinchGesture(isLeft);
        } else {
            hand.isTracked = false;
        }
    };

    detectHandOnSide(true);  // Left Hand
    detectHandOnSide(false); // Right Hand

    return true;
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
