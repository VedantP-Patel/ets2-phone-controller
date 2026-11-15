#pragma once

#include <cstdint>
#include <cstddef>

constexpr uint16_t PROTOCOL_MAGIC   = 0x4532; // "E2"
constexpr uint8_t  PROTOCOL_VERSION = 2;

constexpr uint16_t PORT_CONTROL     = 5005;
constexpr uint16_t PORT_BEACON      = 5006;
constexpr const char* BEACON_MSG    = "ETS2_COCKPIT_V2";

// Uplink flag bits (Phone -> PC)
constexpr uint8_t UPLINK_FLAG_COACH_SMOOTH_MODE = (1 << 0);
constexpr uint8_t UPLINK_FLAG_LOOK_AUTOCENTER   = (1 << 1);
constexpr uint8_t UPLINK_FLAG_PING_REQUEST      = (1 << 2);

// Downlink dashboardFlags bits (PC -> Phone)
constexpr uint16_t DASH_LEFT_BLINKER      = (1 << 0);
constexpr uint16_t DASH_RIGHT_BLINKER     = (1 << 1);
constexpr uint16_t DASH_HAZARD            = (1 << 2);
constexpr uint16_t DASH_PARK_BRAKE        = (1 << 3);
constexpr uint16_t DASH_ENGINE_RUNNING    = (1 << 4);
constexpr uint16_t DASH_LOW_AIR_WARN      = (1 << 5);
constexpr uint16_t DASH_CRUISE_ACTIVE     = (1 << 6);
constexpr uint16_t DASH_RETARDER_ACTIVE   = (1 << 7);
constexpr uint16_t DASH_ENGINE_BRK_ACTIVE = (1 << 8);
constexpr uint16_t DASH_LOW_BEAMS         = (1 << 9);
constexpr uint16_t DASH_HIGH_BEAMS        = (1 << 10);
constexpr uint16_t DASH_BEACONS           = (1 << 11);
constexpr uint16_t DASH_WIPERS_ACTIVE     = (1 << 12);
constexpr uint16_t DASH_DIFF_LOCK         = (1 << 13);
constexpr uint16_t DASH_TRUCK_LIFT_AXLE   = (1 << 14);
constexpr uint16_t DASH_TRAILER_LIFT_AXLE = (1 << 15);

// Downlink safetyFlags bits (PC -> Phone)
constexpr uint8_t SAFETY_TRAILER_CONNECTED = (1 << 0);
constexpr uint8_t SAFETY_BLIND_SPOT_LEFT   = (1 << 1);
constexpr uint8_t SAFETY_BLIND_SPOT_RIGHT  = (1 << 2);
constexpr uint8_t SAFETY_COLLISION_WARN    = (1 << 3);
constexpr uint8_t SAFETY_LKA_ACTIVE        = (1 << 4);
constexpr uint8_t SAFETY_AUTO_TRANSMISSION = (1 << 5);
constexpr uint8_t SAFETY_PARK_LIGHTS       = (1 << 6);

#pragma pack(push, 1)

// 29-byte Phone -> PC Control Packet
struct PhoneControlPacket {
    uint16_t magic;             // 0x4532 (2)
    uint8_t  version;           // 2      (1)
    uint8_t  flags;             //        (1)
    uint16_t sequenceId;        //        (2)
    uint16_t clientTimestampMs; //        (2)
    uint64_t buttonMask;        //        (8) -> 64 independent simultaneous buttons
    int16_t  steering;          //        (2) -> -32767 .. +32767
    uint16_t throttle;          //        (2) -> 0 .. 65535
    uint16_t brake;             //        (2) -> 0 .. 65535
    uint16_t clutch;            //        (2) -> 0 .. 65535
    int16_t  lookX;             //        (2) -> -32767 .. +32767
    int16_t  lookY;             //        (2) -> -32767 .. +32767
    uint8_t  checksum;          //        (1) -> XOR of bytes [0..27]
};

// 42-byte PC -> Phone Telemetry + Haptic Packet
struct TruckTelemetryPacket {
    uint16_t magic;             // 0x4532 (2)
    uint8_t  version;           // 2      (1)
    uint8_t  safetyFlags;       //        (1)
    float    speedKmh;          //        (4)
    float    engineRpm;         //        (4)
    float    maxRpm;            //        (4)
    float    airPressureBar;    //        (4)
    int8_t   gear;              //        (1)
    uint8_t  forwardGears;      //        (1)
    uint8_t  fuelPercent;       //        (1)
    uint8_t  adbluePercent;     //        (1)
    uint8_t  speedLimitKmh;     //        (1)
    uint8_t  cruiseSpeedKmh;    //        (1)
    uint8_t  truckDamagePct;    //        (1)
    uint8_t  retarderLevel;     //        (1)
    uint8_t  accDistanceLevel;  //        (1)
    int8_t   lateralG100;       //        (1) -> Lateral G * 100 (-127..+127)
    int8_t   longitudinalG100;  //        (1) -> Longitudinal G * 100 (-127..+127)
    uint8_t  rumbleLarge;       //        (1)
    uint8_t  rumbleSmall;       //        (1)
    uint16_t dashboardFlags;    //        (2)
    uint16_t routeDistanceKm;   //        (2)
    uint16_t routeTimeMin;      //        (2)
    uint16_t echoTimestampMs;   //        (2)
    uint8_t  checksum;          //        (1) -> XOR of bytes [0..40]
};

#pragma pack(pop)

static_assert(sizeof(PhoneControlPacket) == 29, "PhoneControlPacket must be 29 bytes");
static_assert(sizeof(TruckTelemetryPacket) == 42, "TruckTelemetryPacket must be 42 bytes");

inline uint8_t computeXorChecksum(const uint8_t* data, size_t len) {
    uint8_t cs = 0;
    for (size_t i = 0; i < len; ++i) cs ^= data[i];
    return cs;
}

inline bool validateControlPacket(const uint8_t* buf, size_t len, PhoneControlPacket* out) {
    if (len != sizeof(PhoneControlPacket) || !buf || !out) return false;
    const auto* pkt = reinterpret_cast<const PhoneControlPacket*>(buf);
    if (pkt->magic != PROTOCOL_MAGIC || pkt->version != PROTOCOL_VERSION) return false;
    if (computeXorChecksum(buf, sizeof(PhoneControlPacket) - 1) != pkt->checksum) return false;
    *out = *pkt;
    return true;
}

inline void finalizeTelemetryPacket(TruckTelemetryPacket* pkt) {
    pkt->magic = PROTOCOL_MAGIC;
    pkt->version = PROTOCOL_VERSION;
    pkt->checksum = computeXorChecksum(reinterpret_cast<const uint8_t*>(pkt), sizeof(TruckTelemetryPacket) - 1);
}