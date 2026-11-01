#include "protocol.h"
#include <cstring>
#include <algorithm>

bool decodeControllerPacket(const uint8_t* data, size_t length, ControllerState* outState) {
    if (!data || !outState || length != CONTROLLER_PACKET_SIZE) {
        return false;
    }

    // Verify XOR checksum (bytes 0 to 10 XOR'd must equal byte 11)
    uint8_t calculatedChecksum = 0;
    for (size_t i = 0; i < 11; i++) {
        calculatedChecksum ^= data[i];
    }
    if (calculatedChecksum != data[11]) {
        return false; // Packet corrupted
    }

    // Extract little-endian fields directly
    uint16_t seq = 0;
    uint16_t buttons = 0;
    int16_t steerRaw = 0;
    uint16_t gasRaw = 0;

    std::memcpy(&seq, &data[0], 2);
    std::memcpy(&buttons, &data[2], 2);
    std::memcpy(&steerRaw, &data[4], 2);
    std::memcpy(&gasRaw, &data[6], 2);

    // Decode 10-bit brake (bits 0..9) and 6-bit signed lookX (bits 10..15) from bytes 8..9
    uint16_t rawBrake = static_cast<uint16_t>(data[8]) | (static_cast<uint16_t>(data[9]) << 8);
    uint16_t brake10 = rawBrake & 0x03FF;
    int8_t look6 = static_cast<int8_t>((rawBrake >> 10) & 0x3F);
    if (look6 & 0x20) {
        look6 |= 0xC0; // Sign-extend 6-bit two's complement (-31..+31)
    }

    outState->sequenceId = seq;
    outState->buttons = buttons;
    outState->steering = std::clamp(static_cast<float>(steerRaw) / 32767.0f, -1.0f, 1.0f);
    outState->throttle = std::clamp(static_cast<float>(gasRaw) / 65535.0f, 0.0f, 1.0f);
    outState->brake = std::clamp(static_cast<float>(brake10) / 1023.0f, 0.0f, 1.0f);
    outState->lookX = std::clamp(static_cast<float>(look6) / 31.0f, -1.0f, 1.0f);
    outState->flags = data[10];

    return true;
}

void encodeRumblePacket(const RumbleState& rumble, uint8_t* outBuffer) {
    if (!outBuffer) return;

    outBuffer[0] = RUMBLE_HEADER;
    outBuffer[1] = rumble.largeMotor;
    outBuffer[2] = rumble.smallMotor;
    outBuffer[3] = outBuffer[0] ^ outBuffer[1] ^ outBuffer[2]; // XOR checksum
}

void encodeTelemetryPacket(const TruckTelemetryPacket& telem, uint8_t* outBuffer) {
    if (!outBuffer) return;

    outBuffer[0] = TELEMETRY_HEADER;
    std::memcpy(&outBuffer[1], &telem.speedKmh, 4);
    std::memcpy(&outBuffer[5], &telem.engineRpm, 4);
    std::memcpy(&outBuffer[9], &telem.maxRpm, 4);
    outBuffer[13] = static_cast<uint8_t>(telem.gear);
    outBuffer[14] = telem.fuelPercent;
    std::memcpy(&outBuffer[15], &telem.airPressureBar, 4);
    outBuffer[19] = telem.dashboardFlags;
    outBuffer[20] = telem.auxStateFlags;
    std::memcpy(&outBuffer[21], &telem.pingTimestamp, 2);
    outBuffer[23] = telem.speedLimitKmh;
    std::memcpy(&outBuffer[24], &telem.routeDistanceKm, 2);
    std::memcpy(&outBuffer[26], &telem.routeTimeMin, 2);
    outBuffer[28] = telem.truckDamagePct;
    outBuffer[29] = telem.cruiseSpeedKmh;
    outBuffer[30] = telem.forwardGears;
    outBuffer[31] = telem.shifterFlags;

    // XOR checksum of bytes 0..31 into byte 32
    uint8_t csum = 0;
    for (size_t i = 0; i < 32; i++) {
        csum ^= outBuffer[i];
    }
    outBuffer[32] = csum;
}

bool decodeTelemetryPacket(const uint8_t* data, size_t length, TruckTelemetryPacket* outTelem) {
    if (!data || !outTelem || length != TELEMETRY_PACKET_SIZE) return false;
    if (data[0] != TELEMETRY_HEADER) return false;

    uint8_t csum = 0;
    for (size_t i = 0; i < 32; i++) {
        csum ^= data[i];
    }
    if (csum != data[32]) return false;

    outTelem->header = data[0];
    std::memcpy(&outTelem->speedKmh, &data[1], 4);
    std::memcpy(&outTelem->engineRpm, &data[5], 4);
    std::memcpy(&outTelem->maxRpm, &data[9], 4);
    outTelem->gear = static_cast<int8_t>(data[13]);
    outTelem->fuelPercent = data[14];
    std::memcpy(&outTelem->airPressureBar, &data[15], 4);
    outTelem->dashboardFlags = data[19];
    outTelem->auxStateFlags = data[20];
    std::memcpy(&outTelem->pingTimestamp, &data[21], 2);
    outTelem->speedLimitKmh = data[23];
    std::memcpy(&outTelem->routeDistanceKm, &data[24], 2);
    std::memcpy(&outTelem->routeTimeMin, &data[26], 2);
    outTelem->truckDamagePct = data[28];
    outTelem->cruiseSpeedKmh = data[29];
    outTelem->forwardGears = data[30];
    outTelem->shifterFlags = data[31];
    outTelem->checksum = data[32];

    return true;
}
