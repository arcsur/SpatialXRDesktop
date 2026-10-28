#include <cassert>
#include <iostream>
#include <array>
#include <cmath>

#include "tracking/hand_tracking_qnn.hpp"

using namespace spatial::tracking;

void testIndexPointerRay() {
    HandSkeleton hand;
    hand.isTracked = true;
    hand.isLeft = false;

    // Wrist at (0, 0, 0)
    hand.joints[WRIST] = {0.0f, 0.0f, 0.0f, 1.0f};
    // Index MCP at (0.04, 0.10, -0.02)
    hand.joints[INDEX_FINGER_MCP] = {0.04f, 0.10f, -0.02f, 1.0f};
    // Index Tip at (0.04, 0.18, -0.02) (pointing straight up in Y)
    hand.joints[INDEX_FINGER_TIP] = {0.04f, 0.18f, -0.02f, 1.0f};

    auto ray = HandTrackingQnn::computeIndexPointerRay(hand);

    // Origin = midpoint of (0,0,0) and (0.04, 0.10, -0.02) = (0.02, 0.05, -0.01)
    assert(std::abs(ray.origin[0] - 0.02f) < 1e-4f);
    assert(std::abs(ray.origin[1] - 0.05f) < 1e-4f);
    assert(std::abs(ray.origin[2] - (-0.01f)) < 1e-4f);

    // Direction = (0, 0.08, 0) normalized = (0, 1, 0)
    assert(std::abs(ray.direction[0]) < 1e-4f);
    assert(std::abs(ray.direction[1] - 1.0f) < 1e-4f);
    assert(std::abs(ray.direction[2]) < 1e-4f);

    std::cout << "[PASS] testIndexPointerRay\n";
}

void testPinchHysteresis() {
    HandTrackingQnn tracker;
    tracker.initialize();

    std::array<Joint3D, JOINT_COUNT> joints{};
    // Index tip at (0, 0, 0)
    joints[INDEX_FINGER_TIP] = {0.0f, 0.0f, 0.0f, 1.0f};
    // Thumb tip at (0.05, 0, 0) -> 50mm apart
    joints[THUMB_TIP] = {0.050f, 0.0f, 0.0f, 1.0f};
    tracker.updateHandJoints(false, joints);

    PinchPhase phase = tracker.updatePinchGesture(false);
    assert(phase == PinchPhase::None);
    assert(!tracker.isPinching(false));

    // Move thumb closer: 15mm apart (< 20mm threshold)
    joints[THUMB_TIP] = {0.015f, 0.0f, 0.0f, 1.0f};
    tracker.updateHandJoints(false, joints);
    phase = tracker.updatePinchGesture(false);
    assert(phase == PinchPhase::PinchDown);
    assert(tracker.isPinching(false));

    // Move thumb to 25mm apart (> 20mm but < 28mm hysteresis release)
    joints[THUMB_TIP] = {0.025f, 0.0f, 0.0f, 1.0f};
    tracker.updateHandJoints(false, joints);
    phase = tracker.updatePinchGesture(false);
    assert(phase == PinchPhase::PinchHold);
    assert(tracker.isPinching(false)); // Still held!

    // Move thumb to 32mm apart (> 28mm threshold)
    joints[THUMB_TIP] = {0.032f, 0.0f, 0.0f, 1.0f};
    tracker.updateHandJoints(false, joints);
    phase = tracker.updatePinchGesture(false);
    assert(phase == PinchPhase::PinchUp);
    assert(!tracker.isPinching(false));

    std::cout << "[PASS] testPinchHysteresis\n";
}

void testTwoHandScaleAndLookAtPalm() {
    HandSkeleton left{};
    left.isTracked = true;
    left.isLeft = true;
    left.joints[INDEX_FINGER_TIP] = {-0.10f, 0.0f, 0.0f, 1.0f};

    HandSkeleton right{};
    right.isTracked = true;
    right.isLeft = false;
    right.joints[INDEX_FINGER_TIP] = {0.10f, 0.0f, 0.0f, 1.0f};

    float scale = 1.0f;
    // Initial distance = 0.20m
    bool ok = HandTrackingQnn::computeTwoHandScale(left, right, 0.20f, scale);
    assert(ok);
    assert(std::abs(scale - 1.0f) < 1e-4f);

    // Expand distance to 0.40m
    right.joints[INDEX_FINGER_TIP] = {0.30f, 0.0f, 0.0f, 1.0f};
    ok = HandTrackingQnn::computeTwoHandScale(left, right, 0.20f, scale);
    assert(ok);
    assert(std::abs(scale - 2.0f) < 1e-4f);

    // Look at palm test:
    // Left hand palm facing user (+Z direction)
    // Wrist at (0, 0, 0), Pinky MCP at (-0.05, 0.05, 0), Index MCP at (0.05, 0.05, 0)
    HandSkeleton palmHand{};
    palmHand.isTracked = true;
    palmHand.isLeft = true;
    palmHand.joints[WRIST] = {0.0f, 0.0f, 0.0f, 1.0f};
    palmHand.joints[INDEX_FINGER_MCP] = {0.05f, 0.05f, 0.0f, 1.0f};
    palmHand.joints[PINKY_MCP] = {-0.05f, 0.05f, 0.0f, 1.0f};

    // Gaze direction = (0, 0, -1)
    bool lookingAtPalm = HandTrackingQnn::isLookingAtPalm(palmHand, {0.0f, 0.0f, -1.0f});
    assert(lookingAtPalm);

    // Opposite gaze direction (user looking away):
    bool notLooking = HandTrackingQnn::isLookingAtPalm(palmHand, {0.0f, 0.0f, 1.0f});
    assert(!notLooking);

    std::cout << "[PASS] testTwoHandScaleAndLookAtPalm\n";

    // Iron Man Two-Hand Simultaneous Transform (Scale, 3D Translation, and Yaw Angling)
    std::array<float, 3> initH1 = {-0.5f, 0.2f, -1.2f};
    std::array<float, 3> initH2 = { 0.5f, -0.2f, -1.2f};
    // Move hands: stretch apart 1.5x, translate midpoint by (+0.2, +0.1, -0.4), angle right hand deeper
    std::array<float, 3> currH1 = {-0.55f, 0.3f, -1.2f};
    std::array<float, 3> currH2 = { 0.95f, -0.1f, -2.0f};

    HandTrackingQnn::TwoHandTransform tf;
    bool tfOk = HandTrackingQnn::computeTwoHandTransform(initH1, initH2, currH1, currH2, tf);
    assert(tfOk);
    assert(tf.scaleFactor > 1.4f); // Expanded diagonal distance
    assert(std::abs(tf.translationDelta[0] - 0.2f) < 1e-4f); // Midpoint X shifted from 0.0 to 0.2
    assert(std::abs(tf.translationDelta[1] - 0.1f) < 1e-4f); // Midpoint Y shifted from 0.0 to 0.1
    assert(tf.translationDelta[2] < -0.3f); // Moved deeper in Z
    assert(tf.yawDeltaRadians > 0.3f); // Right hand pushed deeper -> positive yaw angle

}

#ifndef SPATIAL_COMBINED_TEST_RUNNER
int main() {
    std::cout << "--- Running Phase 3 Native Tests ---\n";
    testIndexPointerRay();
    testPinchHysteresis();
    testTwoHandScaleAndLookAtPalm();
    std::cout << "All Phase 3 Native Tests Passed!\n";
    return 0;
}
#endif
