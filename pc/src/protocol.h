#pragma once

#include <cstdint>
#include <cstddef>

constexpr uint16_t PROTOCOL_MAGIC         = 0x4532; // "E2"
constexpr uint16_t PACKET_MAGIC_UI_CMD    = 0x4533; // "E3" -> Phone -> PC UI/Radio command
constexpr uint16_t PACKET_MAGIC_RADIO_ROW = 0x4534; // "E4" -> PC -> Phone Radio station entry
constexpr uint8_t  PROTOCOL_VERSION       = 3;

enum UiNavCommand : uint8_t {
    UI_CMD_NONE             = 0,
    UI_CMD_UP               = 1,
    UI_CMD_DOWN             = 2,
    UI_CMD_LEFT             = 3,
    UI_CMD_RIGHT            = 4,
    UI_CMD_TAB              = 5,
    UI_CMD_ENTER            = 6,
    UI_CMD_ESC              = 7,
    UI_CMD_DELIVERY_OPT_1   = 8,
    UI_CMD_DELIVERY_OPT_2   = 9,
    UI_CMD_DELIVERY_OPT_3   = 10,
    UI_CMD_DELIVERY_CONFIRM = 11,
    UI_CMD_RADIO_LIST_REQ   = 12
};

constexpr uint8_t RADIO_FLAG_FAVORITE = (1 << 0);

constexpr uint16_t PORT_CONTROL     = 5005;
constexpr uint16_t PORT_BEACON      = 5006;
constexpr const char* BEACON_MSG    = "ETS2_COCKPIT_V3";

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
constexpr uint8_t SAFETY_AUTO_TRANSMISSION = (1 << 5); // Populated via SCS_TELEMETRY_CONFIG_controls ("shifter.type")
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

// 47-byte PC -> Phone Telemetry + Haptic Packet (v3)
struct TruckTelemetryPacket {
    uint16_t magic;             // 0x4532 (2)
    uint8_t  version;           // 3      (1)
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
    int16_t  oilTempC;          //        (2) -> Engine oil temp (°C)
    int16_t  waterTempC;        //        (2) -> Coolant water temp (°C)
    uint8_t  batteryVolts10;    //        (1) -> Battery voltage * 10
    uint8_t  checksum;          //        (1) -> XOR of bytes [0..45]
};

// 5-byte Phone -> PC UI Navigation / Radio Sync Command
struct UiCommandPacket {
    uint16_t magic;             // 0x4533 (2)
    uint8_t  cmd;               // UiNavCommand (1)
    uint8_t  arg;               // optional arg (1)
    uint8_t  checksum;          // XOR of bytes [0..3] (1)
};

// 11-byte header + UTF-8 payload ("name\tgenre\tcountry\turl") + 1-byte XOR checksum
struct RadioRowHeader {
    uint16_t magic;             // 0x4534 (2)
    uint16_t index;             // 0-based index (2)
    uint16_t totalCount;        // total station count (2)
    uint8_t  flags;             // bit 0: favorite (1)
    uint16_t bitrate;           // kbps (2)
    uint16_t payloadLen;        // byte length of UTF-8 payload <= 512 (2)
};

#pragma pack(pop)

static_assert(sizeof(PhoneControlPacket) == 29, "PhoneControlPacket must be 29 bytes");
static_assert(sizeof(TruckTelemetryPacket) == 47, "TruckTelemetryPacket must be 47 bytes");
static_assert(sizeof(UiCommandPacket) == 5, "UiCommandPacket must be 5 bytes");
static_assert(sizeof(RadioRowHeader) == 11, "RadioRowHeader must be 11 bytes");

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

inline bool validateUiCommandPacket(const uint8_t* buf, size_t len, UiCommandPacket* out) {
    if (len != sizeof(UiCommandPacket) || !buf || !out) return false;
    const auto* pkt = reinterpret_cast<const UiCommandPacket*>(buf);
    if (pkt->magic != PACKET_MAGIC_UI_CMD) return false;
    if (computeXorChecksum(buf, sizeof(UiCommandPacket) - 1) != pkt->checksum) return false;
    *out = *pkt;
    return true;
}