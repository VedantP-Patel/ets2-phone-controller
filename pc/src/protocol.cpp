#include "protocol.h"
#include <cstring>

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
    uint16_t brakeRaw = 0;

    std::memcpy(&seq, &data[0], 2);
    std::memcpy(&buttons, &data[2], 2);
    std::memcpy(&steerRaw, &data[4], 2);
    std::memcpy(&gasRaw, &data[6], 2);
    std::memcpy(&brakeRaw, &data[8], 2);

    outState->sequenceId = seq;
    outState->buttons = buttons;
    outState->steering = static_cast<float>(steerRaw) / 32767.0f;
    outState->throttle = static_cast<float>(gasRaw) / 65535.0f;
    outState->brake = static_cast<float>(brakeRaw) / 65535.0f;
    outState->flags = data[10];

    // Clamp normalized values
    if (outState->steering < -1.0f) outState->steering = -1.0f;
    if (outState->steering > 1.0f)  outState->steering = 1.0f;
    if (outState->throttle < 0.0f)  outState->throttle = 0.0f;
    if (outState->throttle > 1.0f)  outState->throttle = 1.0f;
    if (outState->brake < 0.0f)     outState->brake = 0.0f;
    if (outState->brake > 1.0f)     outState->brake = 1.0f;

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
    std::memcpy(&outBuffer[20], &telem.pingTimestamp, 2);
    outBuffer[22] = telem.speedLimitKmh;
    std::memcpy(&outBuffer[23], &telem.routeDistanceKm, 2);
    std::memcpy(&outBuffer[25], &telem.routeTimeMin, 2);
    outBuffer[27] = telem.truckDamagePct;
    outBuffer[28] = telem.cruiseSpeedKmh;
    outBuffer[29] = telem.forwardGears;
    outBuffer[30] = telem.shifterFlags;

    // XOR checksum of bytes 0..30 into byte 31
    uint8_t csum = 0;
    for (size_t i = 0; i < 31; i++) {
        csum ^= outBuffer[i];
    }
    outBuffer[31] = csum;
}

bool decodeTelemetryPacket(const uint8_t* data, size_t length, TruckTelemetryPacket* outTelem) {
    if (!data || !outTelem || length != TELEMETRY_PACKET_SIZE) return false;
    if (data[0] != TELEMETRY_HEADER) return false;

    uint8_t csum = 0;
    for (size_t i = 0; i < 31; i++) {
        csum ^= data[i];
    }
    if (csum != data[31]) return false;

    outTelem->header = data[0];
    std::memcpy(&outTelem->speedKmh, &data[1], 4);
    std::memcpy(&outTelem->engineRpm, &data[5], 4);
    std::memcpy(&outTelem->maxRpm, &data[9], 4);
    outTelem->gear = static_cast<int8_t>(data[13]);
    outTelem->fuelPercent = data[14];
    std::memcpy(&outTelem->airPressureBar, &data[15], 4);
    outTelem->dashboardFlags = data[19];
    std::memcpy(&outTelem->pingTimestamp, &data[20], 2);
    outTelem->speedLimitKmh = data[22];
    std::memcpy(&outTelem->routeDistanceKm, &data[23], 2);
    std::memcpy(&outTelem->routeTimeMin, &data[25], 2);
    outTelem->truckDamagePct = data[27];
    outTelem->cruiseSpeedKmh = data[28];
    outTelem->forwardGears = data[29];
    outTelem->shifterFlags = data[30];
    outTelem->checksum = data[31];

    return true;
}
