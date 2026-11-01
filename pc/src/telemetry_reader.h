#pragma once

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>
#include <chrono>
#include "protocol.h"

class TelemetryReader {
public:
    TelemetryReader();
    ~TelemetryReader();

    bool initialize();
    void shutdown();

    // Read live telemetry from SCS Shared Memory if available,
    // otherwise compute responsive simulated physics from input
    void pollTelemetry(const ControllerState& latestInput, TruckTelemetryPacket* outPacket);

    bool isSdkActive() const { return sdkConnected_; }

private:
    HANDLE hMapFile_ = NULL;
    void* pBuffer_ = nullptr;
    bool sdkConnected_ = false;
    std::chrono::steady_clock::time_point lastCheckTime_{};

    // Internal physics simulation fallback states
    float simSpeedKmh_ = 0.0f;
    float simRpm_ = 0.0f;
    int8_t simGear_ = 0; // 0 = Neutral, 1..12 = Gears, -1 = Reverse
    bool simEngineOn_ = false;
    bool simBlinkerLeft_ = false;
    bool simBlinkerRight_ = false;
    bool simHazard_ = false;
    bool simParkBrake_ = false;
    float simAirPressure_ = 8.5f;
    std::chrono::steady_clock::time_point lastSimTime_{};
    uint16_t prevButtons_ = 0;
    uint8_t prevFlags_ = 0;

    void updateSimulation(const ControllerState& input, float dt, TruckTelemetryPacket* outPacket);
    bool tryConnectSharedMemory();
};

