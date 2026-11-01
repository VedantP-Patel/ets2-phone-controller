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

    // Right thumbstick permanently centered
    report_.sThumbRX = 0;
    report_.sThumbRY = 0;

    // Map throttle (0.0 to 1.0) -> Right Trigger RT (0 to 255)
    int gasInt = static_cast<int>(throttle * 255.0f);
    report_.bRightTrigger = static_cast<BYTE>(std::clamp(gasInt, 0, 255));

    // Map brake (0.0 to 1.0) -> Left Trigger LT (0 to 255)
    int brakeInt = static_cast<int>(brake * 255.0f);
    report_.bLeftTrigger = static_cast<BYTE>(std::clamp(brakeInt, 0, 255));

    // Keep wButtons = 0 so ViGEm Xbox buttons NEVER double-toggle or conflict
    // with the DirectInput hardware scan codes dispatched by main.cpp!
    (void)buttons;
    (void)flags;
    report_.wButtons = 0;

    // Direct kernel IOCTL update
    vigem_target_x360_update(client_, target_, report_);
}