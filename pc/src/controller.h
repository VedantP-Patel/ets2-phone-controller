#pragma once

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>
#include <ViGEm/Client.h>
#include <functional>
#include <atomic>
#include <cstdint>
#include <chrono>

class VirtualGamepad {
public:
    using RumbleCallback = std::function<void(uint8_t largeMotor, uint8_t smallMotor)>;

    VirtualGamepad();
    ~VirtualGamepad();

    // Connects to ViGEmBus and plugs in virtual Xbox 360 controller
    bool initialize();

    // Unplugs virtual controller and disconnects from driver
    void shutdown();

    bool isConnected() const { return initialized_; }

    // Sets callback to be notified when ETS2 triggers force feedback rumble
    void setRumbleCallback(RumbleCallback cb);

    // Updates controller axes and buttons in a single atomic IOCTL call
    void update(float steering, float throttle, float brake, uint16_t buttons, uint8_t flags = 0);

private:
    static VOID CALLBACK onNotificationStatic(
        PVIGEM_CLIENT client,
        PVIGEM_TARGET target,
        UCHAR largeMotor,
        UCHAR smallMotor,
        UCHAR ledNumber,
        LPVOID userData
    );

    void handleNotification(UCHAR largeMotor, UCHAR smallMotor);

    PVIGEM_CLIENT client_ = nullptr;
    PVIGEM_TARGET target_ = nullptr;
    XUSB_REPORT report_;
    RumbleCallback rumbleCallback_;
    std::atomic<bool> initialized_{false};

    // Hold & pulse timers for ETS2 default button combos
    std::chrono::steady_clock::time_point hornPulseUntil_{};
    std::chrono::steady_clock::time_point engineHoldUntil_{};
    std::chrono::steady_clock::time_point lightsPulseUntil_{};
    std::chrono::steady_clock::time_point parkHoldUntil_{};
    std::chrono::steady_clock::time_point hazardHoldUntil_{};
    std::chrono::steady_clock::time_point cruiseHoldUntil_{};
    std::chrono::steady_clock::time_point wiperPulseUntil_{};
    std::chrono::steady_clock::time_point leftTurnPulseUntil_{};
    std::chrono::steady_clock::time_point rightTurnPulseUntil_{};
    std::chrono::steady_clock::time_point exhaustPulseUntil_{};
    std::chrono::steady_clock::time_point mapPulseUntil_{};
    std::chrono::steady_clock::time_point camPulseUntil_{};
    std::chrono::steady_clock::time_point gearUpPulseUntil_{};
    std::chrono::steady_clock::time_point gearDownPulseUntil_{};
    std::chrono::steady_clock::time_point neutralHoldUntil_{};
    uint16_t prevButtons_ = 0;
    uint8_t prevFlags_ = 0;
    uint32_t activeKeyMask_ = 0;
};

