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

    // Release any held virtual keys
    for (int i = 0; i < 15; ++i) {
        if (activeKeyMask_ & (1 << i)) {
            BYTE vk = (i == 4) ? VK_OEM_4 : (i == 5) ? VK_OEM_6 : (i == 6) ? VK_SPACE : (i == 10) ? VK_LSHIFT : (i == 11) ? VK_LCONTROL : 0;
            keybd_event(vk, 0, KEYEVENTF_KEYUP, 0);
        }
    }
    activeKeyMask_ = 0;

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

struct KeyMapping {
    BYTE vk;
    BYTE scan;
};

static const KeyMapping g_keyMappings[15] = {
    { 'H', 0x23 },          // 0: HORN
    { 'E', 0x12 },          // 1: ENGINE
    { 'L', 0x26 },          // 2: LIGHTS
    { 'F', 0x21 },          // 3: HAZARD
    { VK_OEM_4, 0x1A },     // 4: LEFT TURN ([)
    { VK_OEM_6, 0x1B },     // 5: RIGHT TURN (])
    { VK_SPACE, 0x39 },     // 6: PARKING BRAKE
    { 'B', 0x30 },          // 7: EXHAUST / MOTOR BRAKE
    { 'M', 0x32 },          // 8: MAP / ADVISOR
    { 'C', 0x2E },          // 9: CRUISE CONTROL
    { VK_LSHIFT, 0x2A },    // 10: GEAR UP (Shift)
    { VK_LCONTROL, 0x1D },  // 11: GEAR DOWN (Ctrl)
    { 'P', 0x19 },          // 12: WIPERS
    { '1', 0x02 },          // 13: CAMERA
    { 'N', 0x31 }           // 14: NEUTRAL
};

static void triggerKey(int index, bool press) {
    if (index < 0 || index >= 15) return;
    BYTE vk = g_keyMappings[index].vk;
    BYTE scan = g_keyMappings[index].scan;
    DWORD flags = (press ? 0 : KEYEVENTF_KEYUP);
    keybd_event(vk, scan, flags, 0);
}

void VirtualGamepad::update(float steering, float throttle, float brake, uint16_t buttons, uint8_t flags) {
    if (!initialized_ || !target_ || !client_) return;

    // Map steering (-1.0 to +1.0) -> Left Thumbstick X (-32768 to 32767)
    int steerInt = static_cast<int>(steering * 32767.0f);
    steerInt = std::clamp(steerInt, -32768, 32767);
    report_.sThumbLX = static_cast<SHORT>(steerInt);
    report_.sThumbLY = 0;

    // Right thumbstick default centered
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

    // Direct Dual-Dispatch Key Release Watcher
    auto checkKeyRelease = [&](int bitIndex, const std::chrono::steady_clock::time_point& until) {
        if ((activeKeyMask_ & (1 << bitIndex)) && (now >= until)) {
            triggerKey(bitIndex, false);
            activeKeyMask_ &= ~(1 << bitIndex);
        }
    };

    checkKeyRelease(0, hornPulseUntil_);
    checkKeyRelease(1, engineHoldUntil_);
    checkKeyRelease(2, lightsPulseUntil_);
    checkKeyRelease(3, hazardHoldUntil_);
    checkKeyRelease(4, leftTurnPulseUntil_);
    checkKeyRelease(5, rightTurnPulseUntil_);
    checkKeyRelease(6, parkHoldUntil_);
    checkKeyRelease(7, exhaustPulseUntil_);
    checkKeyRelease(8, mapPulseUntil_);
    checkKeyRelease(9, cruiseHoldUntil_);
    checkKeyRelease(10, gearUpPulseUntil_);
    checkKeyRelease(11, gearDownPulseUntil_);
    checkKeyRelease(12, wiperPulseUntil_);
    checkKeyRelease(13, camPulseUntil_);
    checkKeyRelease(14, neutralHoldUntil_);

    auto pressKey = [&](int bitIndex, std::chrono::milliseconds duration) {
        triggerKey(bitIndex, true);
        activeKeyMask_ |= (1 << bitIndex);
        return now + duration;
    };

    // Trigger auto-hold timers & direct keyboard events on rising edge
    if (newPresses & (1 << 0)) { // Bit 0: HORN / OK -> 180ms pulse
        hornPulseUntil_ = pressKey(0, std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 1)) { // Bit 1: ENGINE -> 600ms hold
        engineHoldUntil_ = pressKey(1, std::chrono::milliseconds(600));
    }
    if (newPresses & (1 << 2)) { // Bit 2: LIGHTS -> 180ms pulse
        lightsPulseUntil_ = pressKey(2, std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 3)) { // Bit 3: HAZARD -> 180ms pulse
        hazardHoldUntil_ = pressKey(3, std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 4)) { // Bit 4: LEFT TURN -> 180ms pulse
        leftTurnPulseUntil_ = pressKey(4, std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 5)) { // Bit 5: RIGHT TURN -> 180ms pulse
        rightTurnPulseUntil_ = pressKey(5, std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 6)) { // Bit 6: PARK BRK -> 300ms hold
        parkHoldUntil_ = pressKey(6, std::chrono::milliseconds(300));
    }
    if (newPresses & (1 << 7)) { // Bit 7: EXHAUST -> 180ms pulse
        exhaustPulseUntil_ = pressKey(7, std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 8)) { // Bit 8: MAP / ADVISOR -> 180ms pulse
        mapPulseUntil_ = pressKey(8, std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 9)) { // Bit 9: PAUSE / CRUISE -> 250ms hold
        cruiseHoldUntil_ = pressKey(9, std::chrono::milliseconds(250));
    }
    if (newPresses & (1 << 10)) { // Bit 10: GEAR UP -> 180ms pulse
        gearUpPulseUntil_ = pressKey(10, std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 11)) { // Bit 11: GEAR DOWN -> 180ms pulse
        gearDownPulseUntil_ = pressKey(11, std::chrono::milliseconds(180));
    }
    if (newPresses & (1 << 12)) { // Bit 12: WIPERS -> 150ms pulse
        wiperPulseUntil_ = pressKey(12, std::chrono::milliseconds(150));
    }
    if (newPresses & (1 << 13)) { // Bit 13: CAMERA -> 150ms pulse
        camPulseUntil_ = pressKey(13, std::chrono::milliseconds(150));
    }
    if (newFlags & (1 << 1)) { // Flags Bit 1: NEUTRAL -> 180ms pulse
        neutralHoldUntil_ = pressKey(14, std::chrono::milliseconds(180));
    }

    // Build Xbox buttons report matching ETS2's exact factory default controller layout
    report_.wButtons = 0;

    // Bit 0: HORN / OK -> Joy Right Thumb (R3) for horn + Joy (A) for menus
    if ((buttons & (1 << 0)) || (now < hornPulseUntil_)) {
        report_.wButtons |= (XUSB_GAMEPAD_RIGHT_THUMB | XUSB_GAMEPAD_A);
    }

    // Bit 1: ENGINE -> ETS2 Joy D-Pad Right (Hold)
    if ((buttons & (1 << 1)) || (now < engineHoldUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_DPAD_RIGHT;
    }

    // Bit 2: LIGHTS -> ETS2 Joy (X) (cycles parking lights / low beam / off)
    if ((buttons & (1 << 2)) || (now < lightsPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_X;
    }

    // Bit 3: HAZARD -> ETS2 Joy Back (Hold)
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

    // Bit 9: CRUISE CONTROL -> Joy (B) (Hold)
    if ((buttons & (1 << 9)) || (now < cruiseHoldUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_B;
    }

    // Bit 10: GEAR UP -> Joy Right Bumper (RB) + ETS2 default modifier combo (LB + RS Up)
    if ((buttons & (1 << 10)) || (now < gearUpPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_RIGHT_SHOULDER;
        report_.wButtons |= XUSB_GAMEPAD_LEFT_SHOULDER;
        report_.sThumbRY = 32767;
    }

    // Bit 11: GEAR DOWN -> Joy Left Bumper (LB) + ETS2 default modifier combo (LB + RS Down)
    if ((buttons & (1 << 11)) || (now < gearDownPulseUntil_)) {
        report_.wButtons |= XUSB_GAMEPAD_LEFT_SHOULDER;
        report_.sThumbRY = -32768;
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
