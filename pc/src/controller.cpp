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
        std::cerr << "========================================================================\n"
                  << "[ACTION REQUIRED] ViGEmBus Driver is NOT installed on this PC!\n"
                  << "ETS2 Phone Controller requires ViGEmBus to emulate an Xbox 360 controller.\n\n"
                  << "To fix this:\n"
                  << "  1. Run 'install_vigembus.bat' in the project folder\n"
                  << "  2. Or run in PowerShell: winget install --id ViGEm.ViGEmBus -e\n"
                  << "  3. Or download manually: https://github.com/nefarius/ViGEmBus/releases\n"
                  << "========================================================================\n";
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

void VirtualGamepad::update(float steering, float throttle, float brake, uint16_t buttons, uint8_t flags, float lookX) {
    if (!initialized_ || !target_ || !client_) return;

    int steerInt = static_cast<int>(steering * 32767.0f);
    report_.sThumbLX = static_cast<SHORT>(std::clamp(steerInt, -32768, 32767));
    report_.sThumbLY = 0;

    // Right thumbstick X: Smooth Cabin Camera Panning (Look Left / Look Right at intersections)
    int lookInt = static_cast<int>(lookX * 32767.0f);
    report_.sThumbRX = static_cast<SHORT>(std::clamp(lookInt, -32768, 32767));
    report_.sThumbRY = 0;

    // Map throttle (0.0 to 1.0) -> Right Trigger RT (0 to 255)
    int gasInt = static_cast<int>(throttle * 255.0f);
    report_.bRightTrigger = static_cast<BYTE>(std::clamp(gasInt, 0, 255));

    // Map brake (0.0 to 1.0) -> Left Trigger LT (0 to 255)
    int brakeInt = static_cast<int>(brake * 255.0f);
    report_.bLeftTrigger = static_cast<BYTE>(std::clamp(brakeInt, 0, 255));

    // Map phone button bits to Xbox 360 XUSB_GAMEPAD buttons
    USHORT xbtns = 0;
    if (buttons & (1 << 0))  xbtns |= XUSB_GAMEPAD_RIGHT_THUMB;    // Bit 0: Horn (Joy R3)
    if (buttons & (1 << 1))  xbtns |= XUSB_GAMEPAD_START;          // Bit 1: Engine
    if (buttons & (1 << 2))  xbtns |= XUSB_GAMEPAD_X;              // Bit 2: Lights (Joy X)
    if (buttons & (1 << 3))  xbtns |= XUSB_GAMEPAD_BACK;           // Bit 3: Hazard (Joy Back)
    if (buttons & (1 << 4))  xbtns |= XUSB_GAMEPAD_DPAD_LEFT;      // Bit 4: Left Turn
    if (buttons & (1 << 5))  xbtns |= XUSB_GAMEPAD_DPAD_RIGHT;     // Bit 5: Right Turn
    if (buttons & (1 << 6))  xbtns |= XUSB_GAMEPAD_DPAD_DOWN;      // Bit 6: Park Brake
    if (buttons & (1 << 7))  xbtns |= XUSB_GAMEPAD_LEFT_THUMB;     // Bit 7: Retarder (Joy L3)
    if (buttons & (1 << 8))  xbtns |= XUSB_GAMEPAD_Y;              // Bit 8: Map / Advisor (Joy Y)
    if (buttons & (1 << 9))  xbtns |= XUSB_GAMEPAD_B;              // Bit 9: Cruise (Joy B)
    if (buttons & (1 << 10)) xbtns |= XUSB_GAMEPAD_RIGHT_SHOULDER; // Bit 10: Shift Up (Joy RB)
    if (buttons & (1 << 11)) xbtns |= XUSB_GAMEPAD_LEFT_SHOULDER;  // Bit 11: Shift Down (Joy LB)
    if (buttons & (1 << 13)) xbtns |= XUSB_GAMEPAD_DPAD_UP;        // Bit 13: Camera Cycle

    (void)buttons;
    // Flags Bit 1: Shift To Neutral -> ETS2 Default Secondary is "Joy LB + Joy RB (Hold)"
    if (flags & (1 << 1)) {
        report_.wButtons = XUSB_GAMEPAD_LEFT_SHOULDER | XUSB_GAMEPAD_RIGHT_SHOULDER;
    } else {
        report_.wButtons = 0;
    }

    // Direct kernel IOCTL update
    vigem_target_x360_update(client_, target_, report_);
}
