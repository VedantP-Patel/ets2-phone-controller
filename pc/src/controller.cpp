#include "controller.h"
#include <iostream>
#include <algorithm>

VirtualGamepad::VirtualGamepad() {
    XUSB_REPORT_INIT(&report_);
}

VirtualGamepad::~VirtualGamepad() {
    shutdown();
}

bool VirtualGamepad::initialize() {
    if (initialized_) return true;

    client_ = vigem_alloc();
    if (!client_) {
        std::cerr << "[ERROR] Could not allocate ViGEm client context." << std::endl;
        return false;
    }

    const VIGEM_ERROR connectErr = vigem_connect(client_);
    if (!VIGEM_SUCCESS(connectErr)) {
        std::cerr << "[ERROR] Failed to connect to ViGEmBus driver. Error code: 0x" 
                  << std::hex << connectErr << std::dec << std::endl;
        std::cerr << "        Make sure ViGEmBus driver is installed and running." << std::endl;
        vigem_free(client_);
        client_ = nullptr;
        return false;
    }

    target_ = vigem_target_x360_alloc();
    if (!target_) {
        std::cerr << "[ERROR] Failed to allocate virtual Xbox 360 controller target." << std::endl;
        vigem_disconnect(client_);
        vigem_free(client_);
        client_ = nullptr;
        return false;
    }

    const VIGEM_ERROR addErr = vigem_target_add(client_, target_);
    if (!VIGEM_SUCCESS(addErr)) {
        std::cerr << "[ERROR] Failed to plug in virtual Xbox 360 controller. Error: 0x" 
                  << std::hex << addErr << std::dec << std::endl;
        vigem_target_free(target_);
        vigem_disconnect(client_);
        vigem_free(client_);
        target_ = nullptr;
        client_ = nullptr;
        return false;
    }

    // Register force feedback rumble notification callback
    const VIGEM_ERROR notifErr = vigem_target_x360_register_notification(
        client_, 
        target_, 
        &VirtualGamepad::onNotificationStatic, 
        this
    );
    if (!VIGEM_SUCCESS(notifErr)) {
        std::cout << "[WARN] Force feedback rumble notification could not be registered (0x" 
                  << std::hex << notifErr << std::dec << ")." << std::endl;
    }

    initialized_ = true;
    std::cout << "[SUCCESS] Virtual Xbox 360 controller plugged in successfully!" << std::endl;
    return true;
}

void VirtualGamepad::shutdown() {
    if (!initialized_) return;

    if (target_) {
        vigem_target_x360_unregister_notification(target_);
        vigem_target_remove(client_, target_);
        vigem_target_free(target_);
        target_ = nullptr;
    }

    if (client_) {
        vigem_disconnect(client_);
        vigem_free(client_);
        client_ = nullptr;
    }

    initialized_ = false;
    std::cout << "[INFO] Virtual controller unplugged cleanly." << std::endl;
}

void VirtualGamepad::setRumbleCallback(RumbleCallback cb) {
    rumbleCallback_ = std::move(cb);
}

VOID CALLBACK VirtualGamepad::onNotificationStatic(
    PVIGEM_CLIENT client,
    PVIGEM_TARGET target,
    UCHAR largeMotor,
    UCHAR smallMotor,
    UCHAR ledNumber,
    LPVOID userData
) {
    (void)client;
    (void)target;
    (void)ledNumber;
    if (userData) {
        auto* self = static_cast<VirtualGamepad*>(userData);
        self->handleNotification(largeMotor, smallMotor);
    }
}

void VirtualGamepad::handleNotification(UCHAR largeMotor, UCHAR smallMotor) {
    if (rumbleCallback_) {
        rumbleCallback_(largeMotor, smallMotor);
    }
}

void VirtualGamepad::update(float steering, float throttle, float brake, uint16_t buttons, uint8_t flags) {
    if (!initialized_ || !target_ || !client_) return;

    // Map steering (-1.0 to +1.0) -> Left Thumbstick X (-32768 to 32767)
    int steerInt = static_cast<int>(steering * 32767.0f);
    steerInt = std::clamp(steerInt, -32768, 32767);
    report_.sThumbLX = static_cast<SHORT>(steerInt);
    report_.sThumbLY = 0;

    // Right thumbstick permanently centered (ensures ZERO camera pitch or yaw jitter)
    report_.sThumbRX = 0;
    report_.sThumbRY = 0;

    // Map throttle (0.0 to 1.0) -> Right Trigger RT (0 to 255)
    int gasInt = static_cast<int>(throttle * 255.0f);
    report_.bRightTrigger = static_cast<BYTE>(std::clamp(gasInt, 0, 255));

    // Map brake (0.0 to 1.0) -> Left Trigger LT (0 to 255)
    int brakeInt = static_cast<int>(brake * 255.0f);
    report_.bLeftTrigger = static_cast<BYTE>(std::clamp(brakeInt, 0, 255));

    // Current timestamp for pulse and hold timing
    auto now = std::chrono::steady_clock::now();
    uint16_t newPresses = buttons & ~prevButtons_;
    uint8_t newFlags = flags & ~prevFlags_;
    prevButtons_ = buttons;
    prevFlags_ = flags;

    // Trigger pulse and hold timers on rising edges (ensures 100% detection by ETS2's polling rate)
    auto triggerPulse = [&](std::chrono::milliseconds duration) {
        return now + duration;
    };

    if (newPresses & (1 << 0)) { // Bit 0: HORN
        hornPulseUntil_ = triggerPulse(std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 1)) { // Bit 1: ENGINE
        engineHoldUntil_ = triggerPulse(std::chrono::milliseconds(800));
    }
    if (newPresses & (1 << 2)) { // Bit 2: LIGHTS
        lightsPulseUntil_ = triggerPulse(std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 3)) { // Bit 3: HAZARD
        hazardHoldUntil_ = triggerPulse(std::chrono::milliseconds(200));
    }
    if (newPresses & (1 << 4)) { // Bit 4: LEFT TURN
        leftTurnPulseUntil_ = triggerPulse(std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 5)) { // Bit 5: RIGHT TURN
        rightTurnPulseUntil_ = triggerPulse(std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 6)) { // Bit 6: PARK BRK
        parkHoldUntil_ = triggerPulse(std::chrono::milliseconds(300));
    }
    if (newPresses & (1 << 7)) { // Bit 7: EXHAUST / RETARDER
        exhaustPulseUntil_ = triggerPulse(std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 8)) { // Bit 8: MAP / ADVISOR
        mapPulseUntil_ = triggerPulse(std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 9)) { // Bit 9: PAUSE / CRUISE
        cruiseHoldUntil_ = triggerPulse(std::chrono::milliseconds(250));
    }
    if (newPresses & (1 << 10)) { // Bit 10: GEAR UP
        gearUpPulseUntil_ = triggerPulse(std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 11)) { // Bit 11: GEAR DOWN
        gearDownPulseUntil_ = triggerPulse(std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 12)) { // Bit 12: WIPERS
        wiperPulseUntil_ = triggerPulse(std::chrono::milliseconds(150));
    }
    if (newPresses & (1 << 13)) { // Bit 13: CAMERA
        camPulseUntil_ = triggerPulse(std::chrono::milliseconds(150));
    }
    if (newFlags & (1 << 1)) { // Flags Bit 1: NEUTRAL
        neutralHoldUntil_ = triggerPulse(std::chrono::milliseconds(180));
    }

    // Build Xbox buttons report matching ETS2's exact factory default controller layout
    report_.wButtons = 0;

    // Bit 0: HORN -> Joy Right Thumb (R3) while held (No Joy A contention!)
    if ((buttons & (1 << 0)) || (now < hornPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_RIGHT_THUMB;
    }

    // Bit 1: ENGINE -> ETS2 Joy D-Pad Right (Hold)
    if ((buttons & (1 << 1)) || (now < engineHoldUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_DPAD_RIGHT;
    }

    // Bit 2: LIGHTS -> ETS2 Joy (X)
    if ((buttons & (1 << 2)) || (now < lightsPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_X;
    }

    // Bit 3: HAZARD -> ETS2 Joy Back
    if ((buttons & (1 << 3)) || (now < hazardHoldUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_BACK;
    }

    // Bit 4: LEFT TURN INDICATOR -> ETS2 Joy D-Pad Left
    if ((buttons & (1 << 4)) || (now < leftTurnPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_DPAD_LEFT;
    }

    // Bit 5: RIGHT TURN INDICATOR -> ETS2 Joy D-Pad Right
    if ((buttons & (1 << 5)) || (now < rightTurnPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_DPAD_RIGHT;
    }

    // Bit 6: PARKING BRAKE / WIPERS -> ETS2 Joy D-Pad Down
    if ((buttons & (1 << 6)) || (now < parkHoldUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_DPAD_DOWN; // Hold for park brake
    } else if ((buttons & (1 << 12)) || (now < wiperPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_DPAD_DOWN; // Short pulse for wipers
    }

    // Bit 7: EXHAUST / RETARDER -> Joy Left Stick Click (L3)
    if ((buttons & (1 << 7)) || (now < exhaustPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_LEFT_THUMB;
    }

    // Bit 8: MAP / ADVISOR -> Joy (Y)
    if ((buttons & (1 << 8)) || (now < mapPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_Y;
    }

    // Bit 9: CRUISE CONTROL -> Joy (B)
    if ((buttons & (1 << 9)) || (now < cruiseHoldUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_B;
    }

    // Bit 10: GEAR UP -> Joy Right Shoulder (RB) - Pure bumper pulse, ZERO sThumbRY
    if ((buttons & (1 << 10)) || (now < gearUpPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_RIGHT_SHOULDER;
    }

    // Bit 11: GEAR DOWN -> Joy Left Shoulder (LB) - Pure bumper pulse, ZERO sThumbRY
    if ((buttons & (1 << 11)) || (now < gearDownPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_LEFT_SHOULDER;
    }

    // Flags Bit 1: NEUTRAL -> Joy (Y)
    if ((flags & (1 << 1)) || (now < neutralHoldUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_Y;
    }

    // Bit 13: CAMERA -> Joy D-Pad Up
    if ((buttons & (1 << 13)) || (now < camPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_DPAD_UP;
    }

    // Direct kernel IOCTL update
    vigem_target_x360_update(client_, target_, report_);
}
