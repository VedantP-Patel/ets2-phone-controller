#pragma once

#include <cstdint>
#include <cstddef>

constexpr size_t CONTROLLER_PACKET_SIZE = 12;
constexpr size_t RUMBLE_PACKET_SIZE = 4;
constexpr uint8_t RUMBLE_HEADER = 0xFB;

constexpr size_t TELEMETRY_PACKET_SIZE = 32;
constexpr uint8_t TELEMETRY_HEADER = 0xFC;

// Decoded controller state from Phone
struct ControllerState {
    uint16_t sequenceId;
    uint16_t buttons;
    float steering;     // -1.0f (full left) to +1.0f (full right)
    float throttle;     //  0.0f (idle) to 1.0f (full throttle)
    float brake;        //  0.0f (idle) to 1.0f (full brake)
    uint8_t flags;
};

// Force feedback payload sent from PC to Phone
struct RumbleState {
    uint8_t largeMotor; // Heavy motor: engine vibration, curbs, bumps (0 - 255)
    uint8_t smallMotor; // Fine motor: rumble strips, road textures (0 - 255)
};

// Real-time telemetry streamed from PC to Phone at 20Hz (32 bytes)
struct TruckTelemetryPacket {
    uint8_t header;         // 0xFC (TELEMETRY_HEADER)
    float speedKmh;         // Truck road speed in km/h
    float engineRpm;        // Current engine RPM
    float maxRpm;           // Tachometer redline limit (e.g. 2500)
    int8_t gear;            // Current gear (-1 = Reverse, 0 = Neutral, 1..16 = Forward)
    uint8_t fuelPercent;    // 0..100%
    float airPressureBar;   // Brake air pressure (bar)
    uint8_t dashboardFlags; // Bit 0: Left Blinker, Bit 1: Right Blinker, Bit 2: Hazard, 
                            // Bit 3: Parking Brake, Bit 4: Engine Running, Bit 5: Low Air Warning,
                            // Bit 6: Cruise Control
    uint16_t pingTimestamp; // Heartbeat timestamp / echo for RTT ping measurement
    uint8_t speedLimitKmh;  // Legal road speed limit in km/h (0 = none)
    uint16_t routeDistanceKm; // GPS remaining distance in km
    uint16_t routeTimeMin;  // Estimated travel time in minutes
    uint8_t truckDamagePct; // Overall truck wear / damage percentage (0..100%)
    uint8_t cruiseSpeedKmh; // Target cruise control speed in km/h
    uint8_t forwardGears;   // Total forward gears count (e.g. 12, 14, 16)
    uint8_t shifterFlags;   // Bit 0: 1 = automatic / arcade, 0 = manual / sequential
    uint8_t checksum;       // XOR checksum of bytes 0..30
};

// Decode a raw 12-byte datagram from the phone.
// Returns true if length and XOR checksum are valid.
bool decodeControllerPacket(const uint8_t* data, size_t length, ControllerState* outState);

// Pack a 4-byte rumble datagram to send to the phone.
void encodeRumblePacket(const RumbleState& rumble, uint8_t* outBuffer);

// Pack a 32-byte telemetry datagram to send to the phone.
void encodeTelemetryPacket(const TruckTelemetryPacket& telem, uint8_t* outBuffer);

// Decode a 32-byte telemetry datagram on client.
bool decodeTelemetryPacket(const uint8_t* data, size_t length, TruckTelemetryPacket* outTelem);
