#include "telemetry_reader.h"
#include "scs-telemetry-common.hpp"
#include <iostream>
#include <cmath>
#include <algorithm>

TelemetryReader::TelemetryReader() {
    lastSimTime_ = std::chrono::steady_clock::now();
    lastCheckTime_ = std::chrono::steady_clock::now();
}

TelemetryReader::~TelemetryReader() {
    shutdown();
}

bool TelemetryReader::initialize() {
    return tryConnectSharedMemory();
}

void TelemetryReader::shutdown() {
    if (pBuffer_) {
        UnmapViewOfFile(pBuffer_);
        pBuffer_ = nullptr;
    }
    if (hMapFile_) {
        CloseHandle(hMapFile_);
        hMapFile_ = NULL;
    }
    sdkConnected_ = false;
}

bool TelemetryReader::tryConnectSharedMemory() {
    if (sdkConnected_) return true;

    hMapFile_ = OpenFileMappingA(FILE_MAP_READ, FALSE, "Local\\SCSTelemetry");
    if (!hMapFile_) {
        return false;
    }

    pBuffer_ = MapViewOfFile(hMapFile_, FILE_MAP_READ, 0, 0, sizeof(scsTelemetryMap_t));
    if (!pBuffer_) {
        CloseHandle(hMapFile_);
        hMapFile_ = NULL;
        return false;
    }

    sdkConnected_ = true;
    std::cout << "[TELEMETRY] Hooked into ETS2 Shared Memory (Local\\SCSTelemetry)!" << std::endl;
    return true;
}

void TelemetryReader::pollTelemetry(const ControllerState& latestInput, TruckTelemetryPacket* outPacket) {
    if (!outPacket) return;

    auto now = std::chrono::steady_clock::now();
    float dt = std::chrono::duration<float>(now - lastSimTime_).count();
    lastSimTime_ = now;
    if (dt <= 0.0f || dt > 0.5f) dt = 0.05f;

    // Retry connecting to shared memory every 2 seconds if not connected
    if (!sdkConnected_) {
        float checkDt = std::chrono::duration<float>(now - lastCheckTime_).count();
        if (checkDt >= 2.0f) {
            lastCheckTime_ = now;
            tryConnectSharedMemory();
        }
    }

    bool readSucceeded = false;
    if (sdkConnected_ && pBuffer_) {
        const auto* map = static_cast<const scsTelemetryMap_t*>(pBuffer_);

        // Ensure game process is running and updating telemetry
        if (map->sdkActive) {
            outPacket->header = TELEMETRY_HEADER;
            outPacket->speedKmh = std::max(0.0f, map->truck_f.speed * 3.6f);
            outPacket->engineRpm = std::max(0.0f, map->truck_f.engineRpm);
            outPacket->maxRpm = (map->config_f.engineRpmMax > 500.0f) ? map->config_f.engineRpmMax : 2500.0f;
            outPacket->gear = static_cast<int8_t>(map->truck_i.gear);

            float fuelCap = map->config_f.fuelCapacity;
            if (fuelCap > 10.0f) {
                outPacket->fuelPercent = static_cast<uint8_t>(std::clamp((map->truck_f.fuel / fuelCap) * 100.0f, 0.0f, 100.0f));
            } else {
                outPacket->fuelPercent = 85;
            }

            outPacket->airPressureBar = (map->truck_f.airPressure > 0.0f) ? map->truck_f.airPressure : 8.5f;

            uint8_t flags = 0;
            if (map->truck_b.blinkerLeftActive || map->truck_b.blinkerLeftOn)   flags |= (1 << 0);
            if (map->truck_b.blinkerRightActive || map->truck_b.blinkerRightOn) flags |= (1 << 1);
            if (map->truck_b.lightsHazard)                                      flags |= (1 << 2);
            if (map->truck_b.parkBrake)                                         flags |= (1 << 3);
            if (map->truck_b.engineEnabled)                                     flags |= (1 << 4);
            if (map->truck_b.airPressureWarning)                                flags |= (1 << 5);
            if (map->truck_b.cruiseControl)                                     flags |= (1 << 6);

            outPacket->dashboardFlags = flags;

            // Auxiliary truck accessory states
            uint8_t auxFlags = 0;
            if (map->truck_b.differentialLock)                                  auxFlags |= (1 << 0);
            if (map->truck_b.liftAxle || map->truck_b.liftAxleIndicator)        auxFlags |= (1 << 1);
            if (map->trailer.trailer[0].com_b.attached)                         auxFlags |= (1 << 2);
            if (map->truck_b.lightsBeamHigh)                                    auxFlags |= (1 << 3);
            if (map->truck_b.lightsBeacon)                                      auxFlags |= (1 << 4);
            if (map->truck_b.wipers)                                            auxFlags |= (1 << 5);

            outPacket->auxStateFlags = auxFlags;
            outPacket->pingTimestamp = latestInput.sequenceId;

            // v2.0 Extended Telemetry Channels
            outPacket->speedLimitKmh = (map->truck_f.speedLimit > 0.0f) ? static_cast<uint8_t>(std::clamp(map->truck_f.speedLimit * 3.6f, 0.0f, 255.0f)) : 0;
            outPacket->routeDistanceKm = static_cast<uint16_t>(std::clamp(map->truck_f.routeDistance / 1000.0f, 0.0f, 65535.0f));
            outPacket->routeTimeMin = static_cast<uint16_t>(std::clamp(map->truck_f.routeTime / 60.0f, 0.0f, 65535.0f));

            float maxWear = std::max({map->truck_f.wearEngine, map->truck_f.wearTransmission, map->truck_f.wearChassis, map->truck_f.wearCabin});
            outPacket->truckDamagePct = static_cast<uint8_t>(std::clamp(maxWear * 100.0f, 0.0f, 100.0f));
            outPacket->cruiseSpeedKmh = static_cast<uint8_t>(std::clamp(map->truck_f.cruiseControlSpeed * 3.6f, 0.0f, 255.0f));

            // Forward gears count (e.g. 14 for Scania 12+2 Opticruise)
            outPacket->forwardGears = (map->config_ui.gears > 0) ? static_cast<uint8_t>(map->config_ui.gears) : 14;

            // Shifter mode (Bit 0: 1 = automatic / arcade, 0 = manual / sequential)
            bool isAuto = true;
            if (map->config_s.shifterType[0] != '\0') {
                std::string sType(map->config_s.shifterType);
                if (sType.find("manual") != std::string::npos || sType.find("hshifter") != std::string::npos) {
                    isAuto = false;
                }
            }
            outPacket->shifterFlags = isAuto ? 1 : 0;

            readSucceeded = true;
        } else {
            // SDK inactive (game exited or unloaded)
            shutdown();
        }
    }

    // Fallback simulation if ETS2 is not running
    if (!readSucceeded) {
        updateSimulation(latestInput, dt, outPacket);
    }
}

void TelemetryReader::updateSimulation(const ControllerState& input, float dt, TruckTelemetryPacket* outPacket) {
    uint16_t newBtns = input.buttons & ~prevButtons_;
    uint8_t newFlags = input.flags & ~prevFlags_;
    prevButtons_ = input.buttons;
    prevFlags_ = input.flags;

    // Toggle simulated engine
    if (newBtns & (1 << 1)) {
        simEngineOn_ = !simEngineOn_;
    }

    // Toggle parking brake
    if (newBtns & (1 << 6)) {
        simParkBrake_ = !simParkBrake_;
    }

    // Blinkers
    if (newBtns & (1 << 4)) {
        simBlinkerLeft_ = !simBlinkerLeft_;
        if (simBlinkerLeft_) simBlinkerRight_ = false;
    }
    if (newBtns & (1 << 5)) {
        simBlinkerRight_ = !simBlinkerRight_;
        if (simBlinkerRight_) simBlinkerLeft_ = false;
    }
    if (newBtns & (1 << 3)) {
        simHazard_ = !simHazard_;
    }

    // Transmission Shift Up
    if (newBtns & (1 << 10)) {
        if (simGear_ < 12) simGear_++;
    }
    // Transmission Shift Down
    if (newBtns & (1 << 11)) {
        if (simGear_ > -1) simGear_--;
    }
    // Shift to Neutral
    if (newFlags & (1 << 1)) {
        simGear_ = 0;
    }

    // Simple truck acceleration / deceleration dynamics
    if (simEngineOn_ && !simParkBrake_) {
        if (simGear_ > 0) {
            float topSpeedForGear = simGear_ * 8.5f; // e.g. Gear 12 ~ 102 km/h
            float accel = input.throttle * (25.0f / (simGear_ * 0.7f + 1.0f));
            simSpeedKmh_ += accel * dt;
            if (simSpeedKmh_ > topSpeedForGear) simSpeedKmh_ = topSpeedForGear;
        } else if (simGear_ == -1) {
            float accel = input.throttle * 8.0f;
            simSpeedKmh_ += accel * dt;
            if (simSpeedKmh_ > 20.0f) simSpeedKmh_ = 20.0f;
        } else {
            // Neutral: coast down
            simSpeedKmh_ -= 5.0f * dt;
        }

        // Braking
        simSpeedKmh_ -= input.brake * 45.0f * dt;
        if (simSpeedKmh_ < 0.0f) simSpeedKmh_ = 0.0f;

        // RPM calculation
        if (simGear_ != 0) {
            float ratio = (simGear_ > 0) ? (simGear_ * 8.5f) : 20.0f;
            simRpm_ = 650.0f + (simSpeedKmh_ / ratio) * 1600.0f + (input.throttle * 250.0f);
        } else {
            // Free rev in neutral
            simRpm_ = 650.0f + input.throttle * 1700.0f;
        }
    } else {
        // Engine off
        simSpeedKmh_ -= 10.0f * dt;
        if (simSpeedKmh_ < 0.0f) simSpeedKmh_ = 0.0f;
        simRpm_ = 0.0f;
    }

    // Air pressure simulation
    if (input.brake > 0.5f) {
        simAirPressure_ -= 0.15f * dt;
        if (simAirPressure_ < 4.5f) simAirPressure_ = 4.5f;
    } else if (simEngineOn_) {
        simAirPressure_ += 0.2f * dt;
        if (simAirPressure_ > 8.5f) simAirPressure_ = 8.5f;
    }

    // Populate packet
    outPacket->header = TELEMETRY_HEADER;
    outPacket->speedKmh = simSpeedKmh_;
    outPacket->engineRpm = simRpm_;
    outPacket->maxRpm = 2500.0f;
    outPacket->gear = simGear_;
    outPacket->fuelPercent = 82;
    outPacket->airPressureBar = simAirPressure_;

    uint8_t flags = 0;
    if (simBlinkerLeft_)  flags |= (1 << 0);
    if (simBlinkerRight_) flags |= (1 << 1);
    if (simHazard_)       flags |= (1 << 2);
    if (simParkBrake_)    flags |= (1 << 3);
    if (simEngineOn_)     flags |= (1 << 4);
    if (simAirPressure_ < 6.0f) flags |= (1 << 5);
    outPacket->dashboardFlags = flags;
    outPacket->auxStateFlags = 0;
    outPacket->pingTimestamp = input.sequenceId;

    // Simulation v2.0 fields
    outPacket->speedLimitKmh = 80;
    outPacket->routeDistanceKm = 240;
    outPacket->routeTimeMin = 180;
    outPacket->truckDamagePct = 0;
    outPacket->cruiseSpeedKmh = 80;
    outPacket->forwardGears = 14;
    outPacket->shifterFlags = 1;
}
