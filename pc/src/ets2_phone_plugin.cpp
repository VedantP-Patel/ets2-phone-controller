#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif

#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#include "scssdk_telemetry.h"
#include "scssdk_input.h"
#include "eurotrucks2/scssdk_eut2.h"
#include "eurotrucks2/scssdk_telemetry_eut2.h"

#include "protocol.h"

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "iphlpapi.lib")

namespace {

constexpr size_t NUM_ANALOG_AXES   = 6;
constexpr size_t NUM_BUTTON_CHANS  = 60;
constexpr size_t TOTAL_INPUT_CHANS = NUM_ANALOG_AXES + NUM_BUTTON_CHANS;

struct SharedControlState {
    std::atomic<float>    axes[NUM_ANALOG_AXES]{};
    std::atomic<uint64_t> buttonMask{0};
    std::atomic<uint16_t> lastClientTs{0};
    std::atomic<bool>     hasClient{false};
};

struct LiveTelemetryCache {
    std::mutex mtx;
    TruckTelemetryPacket pkt{};
    float fuelCapacityLiters   = 800.0f;
    float adblueCapacityLiters = 80.0f;
    float wearValues[5]{}; // engine, transmission, cabin, chassis, wheels
};

SharedControlState g_ctrl;
LiveTelemetryCache g_telem;

std::atomic<bool>  g_netRunning{false};
std::thread        g_netThread;
std::thread        g_beaconThread;

std::mutex         g_clientAddrMtx;
sockaddr_in        g_clientAddr{};
SOCKET             g_udpSocket = INVALID_SOCKET;

struct ChannelSpec {
    const char* name;
    const char* displayName;
    scs_value_type_t type;
};

constexpr ChannelSpec kChannels[TOTAL_INPUT_CHANS] = {
    // 6 Analog Axes (Indices 0..5)
    {"steering",          "Phone: Steering Axis",              SCS_VALUE_TYPE_float},
    {"throttle",          "Phone: Throttle Axis",              SCS_VALUE_TYPE_float},
    {"brake",             "Phone: Brake Axis",                 SCS_VALUE_TYPE_float},
    {"clutch",            "Phone: Clutch Axis",                SCS_VALUE_TYPE_float},
    {"look_x",            "Phone: Cabin Look Left/Right",      SCS_VALUE_TYPE_float},
    {"look_y",            "Phone: Cabin Look Up/Down",         SCS_VALUE_TYPE_float},

    // 60 Digital Buttons (Indices 6..65 -> maps 1:1 to buttonMask bits 0..59)
    {"horn",              "Phone: Horn",                       SCS_VALUE_TYPE_bool},
    {"engine_toggle",     "Phone: Engine Start/Stop",          SCS_VALUE_TYPE_bool},
    {"lights_cycle",      "Phone: Lights Cycle",               SCS_VALUE_TYPE_bool},
    {"hazard_lights",     "Phone: Hazard Flashers",            SCS_VALUE_TYPE_bool},
    {"blinker_left",      "Phone: Left Turn Indicator",        SCS_VALUE_TYPE_bool},
    {"blinker_right",     "Phone: Right Turn Indicator",       SCS_VALUE_TYPE_bool},
    {"park_brake",        "Phone: Parking Brake",              SCS_VALUE_TYPE_bool},
    {"engine_brake",      "Phone: Engine Brake Toggle",        SCS_VALUE_TYPE_bool},
    {"advisor_map",       "Phone: Advisor GPS Page",           SCS_VALUE_TYPE_bool},
    {"cruise_toggle",     "Phone: Cruise Control Toggle",      SCS_VALUE_TYPE_bool},
    {"shift_up",          "Phone: Shift Up",                   SCS_VALUE_TYPE_bool},
    {"shift_down",        "Phone: Shift Down",                 SCS_VALUE_TYPE_bool},
    {"wipers_up",         "Phone: Wipers Forward",             SCS_VALUE_TYPE_bool},
    {"cam_interior",      "Phone: Interior Camera",            SCS_VALUE_TYPE_bool},
    {"radio_toggle",      "Phone: Audio Player Toggle",        SCS_VALUE_TYPE_bool},
    {"radio_next",        "Phone: Radio Next Favorite",        SCS_VALUE_TYPE_bool},
    {"radio_prev",        "Phone: Radio Prev Favorite",        SCS_VALUE_TYPE_bool},
    {"shift_neutral",     "Phone: Shift to Neutral",           SCS_VALUE_TYPE_bool},
    {"diff_lock",         "Phone: Differential Lock",          SCS_VALUE_TYPE_bool},
    {"lift_axle",         "Phone: Truck Lift Axle",            SCS_VALUE_TYPE_bool},
    {"trailer_couple",    "Phone: Trailer Attach/Detach",      SCS_VALUE_TYPE_bool},
    {"high_beams",        "Phone: High Beam Headlights",       SCS_VALUE_TYPE_bool},
    {"beacons",           "Phone: Roof Warning Beacons",       SCS_VALUE_TYPE_bool},
    {"air_horn",          "Phone: Roof Air Horn",              SCS_VALUE_TYPE_bool},
    {"retarder_up",       "Phone: Retarder Increase",          SCS_VALUE_TYPE_bool},
    {"retarder_down",     "Phone: Retarder Decrease",          SCS_VALUE_TYPE_bool},
    {"cruise_up",         "Phone: Cruise Speed +",             SCS_VALUE_TYPE_bool},
    {"cruise_down",       "Phone: Cruise Speed -",             SCS_VALUE_TYPE_bool},
    {"cruise_resume",     "Phone: Cruise Control Resume",      SCS_VALUE_TYPE_bool},
    {"dash_info",         "Phone: Dashboard Info Mode",        SCS_VALUE_TYPE_bool},
    {"activate",          "Phone: Activate / Enter",           SCS_VALUE_TYPE_bool},
    {"advisor_job",       "Phone: Advisor Job Info",           SCS_VALUE_TYPE_bool},
    {"advisor_service",   "Phone: Advisor Damage/Service",     SCS_VALUE_TYPE_bool},
    {"cam_window_lean",   "Phone: Lean-Out Window Camera",     SCS_VALUE_TYPE_bool},
    {"cam_chase",         "Phone: Exterior Chase Camera",      SCS_VALUE_TYPE_bool},
    {"light_horn",        "Phone: Light Horn (Flash)",         SCS_VALUE_TYPE_bool},
    {"wipers_down",       "Phone: Wipers Back",                SCS_VALUE_TYPE_bool},
    {"cam_top",           "Phone: Top-Down Parking Camera",    SCS_VALUE_TYPE_bool},
    {"look_left_snap",    "Phone: Quick Look Left",            SCS_VALUE_TYPE_bool},
    {"look_right_snap",   "Phone: Quick Look Right",           SCS_VALUE_TYPE_bool},
    {"mirrors_toggle",    "Phone: Side Mirrors Toggle",        SCS_VALUE_TYPE_bool},
    {"advisor_rest",      "Phone: Advisor Rest/Messages",      SCS_VALUE_TYPE_bool},
    {"world_map",         "Phone: World Map",                  SCS_VALUE_TYPE_bool},
    {"esc_menu",          "Phone: Pause / Menu",               SCS_VALUE_TYPE_bool},
    {"trailer_lift_axle", "Phone: Trailer Lift Axle",          SCS_VALUE_TYPE_bool},
    {"trailer_brake",     "Phone: Trailer Brake",              SCS_VALUE_TYPE_bool},
    {"acc_distance",      "Phone: ACC Following Distance",     SCS_VALUE_TYPE_bool},
    {"lka_toggle",        "Phone: Lane Keeping Assistant",     SCS_VALUE_TYPE_bool},
    {"aebs_toggle",       "Phone: Emergency Brake Mode",       SCS_VALUE_TYPE_bool},
    {"susp_front_up",     "Phone: Front Suspension Up",        SCS_VALUE_TYPE_bool},
    {"susp_front_down",   "Phone: Front Suspension Down",      SCS_VALUE_TYPE_bool},
    {"susp_rear_up",      "Phone: Rear Suspension Up",         SCS_VALUE_TYPE_bool},
    {"susp_rear_down",    "Phone: Rear Suspension Down",       SCS_VALUE_TYPE_bool},
    {"susp_reset",        "Phone: Air Suspension Reset",       SCS_VALUE_TYPE_bool},
    {"window_left",       "Phone: Left Power Window",          SCS_VALUE_TYPE_bool},
    {"window_right",      "Phone: Right Power Window",         SCS_VALUE_TYPE_bool},
    {"shift_drive",       "Phone: Shift to Drive (D)",         SCS_VALUE_TYPE_bool},
    {"shift_reverse",     "Phone: Shift to Reverse (R)",       SCS_VALUE_TYPE_bool},
    {"coach_light",       "Phone: Coach Cabin Lighting",       SCS_VALUE_TYPE_bool},
    {"coach_display",     "Phone: Coach Infotainment Mode",    SCS_VALUE_TYPE_bool}
};

float    g_lastReportedAxes[NUM_ANALOG_AXES]{};
uint64_t g_lastReportedButtons = 0;
size_t   g_pollCursor = 0;
bool     g_deviceActive = false;

SCSAPI_VOID inputActivityCallback(const scs_u8_t active, const scs_context_t) {
    g_deviceActive = (active != 0);
    if (g_deviceActive) {
        g_pollCursor = 0;
    }
}

SCSAPI_RESULT inputNextEventCallback(scs_input_event_t* const eventInfo, const scs_u32_t flags, const scs_context_t) {
    if (flags != 0) {
        g_pollCursor = 0;
    }

    while (g_pollCursor < NUM_ANALOG_AXES) {
        const size_t idx = g_pollCursor++;
        const float val = g_ctrl.axes[idx].load(std::memory_order_relaxed);
        if (std::abs(val - g_lastReportedAxes[idx]) > 0.00015f) {
            g_lastReportedAxes[idx] = val;
            eventInfo->input_index = static_cast<scs_u32_t>(idx);
            eventInfo->value_float.value = val;
            return SCS_RESULT_ok;
        }
    }

    const uint64_t currentMask = g_ctrl.buttonMask.load(std::memory_order_relaxed);
    while (g_pollCursor < TOTAL_INPUT_CHANS) {
        const size_t chanIdx = g_pollCursor++;
        const size_t bitIdx  = chanIdx - NUM_ANALOG_AXES;
        const bool wasDown   = ((g_lastReportedButtons >> bitIdx) & 1ULL) != 0;
        const bool isDown    = ((currentMask >> bitIdx) & 1ULL) != 0;
        if (wasDown != isDown) {
            if (isDown) g_lastReportedButtons |= (1ULL << bitIdx);
            else        g_lastReportedButtons &= ~(1ULL << bitIdx);

            eventInfo->input_index = static_cast<scs_u32_t>(chanIdx);
            eventInfo->value_bool.value = isDown ? 1 : 0;
            return SCS_RESULT_ok;
        }
    }

    g_pollCursor = 0;
    return SCS_RESULT_not_found;
}

void networkWorker() {
    WSADATA wsa{};
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return;

    g_udpSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (g_udpSocket == INVALID_SOCKET) {
        WSACleanup();
        return;
    }

    int rcvBuf = 131072;
    setsockopt(g_udpSocket, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<const char*>(&rcvBuf), sizeof(rcvBuf));

    BOOL connReset = FALSE;
    DWORD bytesRet = 0;
    WSAIoctl(g_udpSocket, _WSAIOW(IOC_VENDOR, 12), &connReset, sizeof(connReset), nullptr, 0, &bytesRet, nullptr, nullptr);

    sockaddr_in bindAddr{};
    bindAddr.sin_family = AF_INET;
    bindAddr.sin_port = htons(PORT_CONTROL);
    bindAddr.sin_addr.s_addr = INADDR_ANY;

    if (bind(g_udpSocket, reinterpret_cast<sockaddr*>(&bindAddr), sizeof(bindAddr)) == SOCKET_ERROR) {
        closesocket(g_udpSocket);
        g_udpSocket = INVALID_SOCKET;
        WSACleanup();
        return;
    }

    WSAEVENT netEvent = WSACreateEvent();
    WSAEventSelect(g_udpSocket, netEvent, FD_READ);

    uint16_t lastSeq = 0;
    bool hasSeq = false;
    auto lastTelemSend = std::chrono::steady_clock::now();
    uint8_t rxBuf[128];

    while (g_netRunning.load(std::memory_order_acquire)) {
        DWORD waitRes = WSAWaitForMultipleEvents(1, &netEvent, FALSE, 20, FALSE);
        if (waitRes == WSA_WAIT_EVENT_0) {
            WSAResetEvent(netEvent);
            sockaddr_in from{};
            int fromLen = sizeof(from);

            uint64_t mergedMask = g_ctrl.buttonMask.load(std::memory_order_relaxed);
            bool gotAny = false;
            PhoneControlPacket latest{};

            while (true) {
                int r = recvfrom(g_udpSocket, reinterpret_cast<char*>(rxBuf), sizeof(rxBuf), 0,
                                 reinterpret_cast<sockaddr*>(&from), &fromLen);
                if (r <= 0) break;

                PhoneControlPacket pkt{};
                if (validateControlPacket(rxBuf, static_cast<size_t>(r), &pkt)) {
                    if (hasSeq) {
                        int16_t diff = static_cast<int16_t>(pkt.sequenceId - lastSeq);
                        if (diff <= 0 && diff > -30000) continue;
                    }
                    lastSeq = pkt.sequenceId;
                    hasSeq = true;
                    mergedMask = gotAny ? (mergedMask | pkt.buttonMask) : pkt.buttonMask;
                    latest = pkt;
                    gotAny = true;
                }
            }

            if (gotAny) {
                g_ctrl.axes[0].store(static_cast<float>(latest.steering) / 32767.0f, std::memory_order_relaxed);
                g_ctrl.axes[1].store(static_cast<float>(latest.throttle) / 65535.0f, std::memory_order_relaxed);
                g_ctrl.axes[2].store(static_cast<float>(latest.brake)    / 65535.0f, std::memory_order_relaxed);
                g_ctrl.axes[3].store(static_cast<float>(latest.clutch)   / 65535.0f, std::memory_order_relaxed);
                g_ctrl.axes[4].store(static_cast<float>(latest.lookX)    / 32767.0f, std::memory_order_relaxed);
                g_ctrl.axes[5].store(static_cast<float>(latest.lookY)    / 32767.0f, std::memory_order_relaxed);
                g_ctrl.buttonMask.store(mergedMask, std::memory_order_relaxed);
                g_ctrl.lastClientTs.store(latest.clientTimestampMs, std::memory_order_relaxed);
                g_ctrl.hasClient.store(true, std::memory_order_relaxed);

                std::lock_guard<std::mutex> lock(g_clientAddrMtx);
                g_clientAddr = from;
            }
        }

        auto now = std::chrono::steady_clock::now();
        if (g_ctrl.hasClient.load(std::memory_order_relaxed) &&
            std::chrono::duration_cast<std::chrono::milliseconds>(now - lastTelemSend).count() >= 25) {
            lastTelemSend = now;

            TruckTelemetryPacket outPkt{};
            {
                std::lock_guard<std::mutex> lock(g_telem.mtx);
                outPkt = g_telem.pkt;
            }
            outPkt.echoTimestampMs = g_ctrl.lastClientTs.load(std::memory_order_relaxed);
            finalizeTelemetryPacket(&outPkt);

            sockaddr_in dest{};
            {
                std::lock_guard<std::mutex> lock(g_clientAddrMtx);
                dest = g_clientAddr;
            }
            sendto(g_udpSocket, reinterpret_cast<const char*>(&outPkt), sizeof(outPkt), 0,
                   reinterpret_cast<const sockaddr*>(&dest), sizeof(dest));
        }
    }

    WSACloseEvent(netEvent);
    closesocket(g_udpSocket);
    g_udpSocket = INVALID_SOCKET;
    WSACleanup();
}

void beaconWorker() {
    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) return;

    BOOL bcast = TRUE;
    setsockopt(sock, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&bcast), sizeof(bcast));
    const int msgLen = static_cast<int>(std::strlen(BEACON_MSG));

    std::vector<uint8_t> adapterBuf(16384);

    while (g_netRunning.load(std::memory_order_acquire)) {
        sockaddr_in globalAddr{};
        globalAddr.sin_family = AF_INET;
        globalAddr.sin_port = htons(PORT_BEACON);
        globalAddr.sin_addr.s_addr = INADDR_BROADCAST;
        sendto(sock, BEACON_MSG, msgLen, 0, reinterpret_cast<sockaddr*>(&globalAddr), sizeof(globalAddr));

        ULONG bufSize = static_cast<ULONG>(adapterBuf.size());
        ULONG ret = GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST,
                                         nullptr, reinterpret_cast<PIP_ADAPTER_ADDRESSES>(adapterBuf.data()), &bufSize);
        if (ret == ERROR_BUFFER_OVERFLOW) {
            adapterBuf.resize(bufSize);
            ret = GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST,
                                       nullptr, reinterpret_cast<PIP_ADAPTER_ADDRESSES>(adapterBuf.data()), &bufSize);
        }

        if (ret == NO_ERROR) {
            for (auto* a = reinterpret_cast<PIP_ADAPTER_ADDRESSES>(adapterBuf.data()); a; a = a->Next) {
                if (a->OperStatus != IfOperStatusUp) continue;
                for (auto* u = a->FirstUnicastAddress; u; u = u->Next) {
                    auto* sa = reinterpret_cast<sockaddr_in*>(u->Address.lpSockaddr);
                    uint32_t ipHost = ntohl(sa->sin_addr.s_addr);
                    if ((ipHost >> 24) == 127) continue;

                    ULONG maskNet = 0;
                    ConvertLengthToIpv4Mask(u->OnLinkPrefixLength, &maskNet);
                    uint32_t maskHost = ntohl(maskNet);

                    sockaddr_in subAddr{};
                    subAddr.sin_family = AF_INET;
                    subAddr.sin_port = htons(PORT_BEACON);
                    subAddr.sin_addr.s_addr = htonl(ipHost | (~maskHost));
                    sendto(sock, BEACON_MSG, msgLen, 0, reinterpret_cast<sockaddr*>(&subAddr), sizeof(subAddr));
                }
            }
        }

        for (int i = 0; i < 15 && g_netRunning.load(std::memory_order_acquire); ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }

    closesocket(sock);
}

void ensureNetworkStarted() {
    bool expected = false;
    if (g_netRunning.compare_exchange_strong(expected, true)) {
        g_netThread = std::thread(networkWorker);
        g_beaconThread = std::thread(beaconWorker);
    }
}

void ensureNetworkStopped() {
    bool expected = true;
    if (g_netRunning.compare_exchange_strong(expected, false)) {
        if (g_netThread.joinable()) g_netThread.join();
        if (g_beaconThread.joinable()) g_beaconThread.join();
    }
}

SCSAPI_VOID onConfiguration(const scs_event_t, const void* const eventInfo, const scs_context_t) {
    const auto* info = static_cast<const scs_telemetry_configuration_t*>(eventInfo);
    if (!info || !info->id || std::strcmp(info->id, SCS_TELEMETRY_CONFIG_truck) != 0) return;

    std::lock_guard<std::mutex> lock(g_telem.mtx);
    for (const scs_named_value_t* cur = info->attributes; cur && cur->name; ++cur) {
        if (std::strcmp(cur->name, SCS_TELEMETRY_CONFIG_ATTRIBUTE_rpm_limit) == 0 && cur->value.type == SCS_VALUE_TYPE_float) {
            g_telem.pkt.maxRpm = cur->value.value_float.value;
        } else if (std::strcmp(cur->name, SCS_TELEMETRY_CONFIG_ATTRIBUTE_fuel_capacity) == 0 && cur->value.type == SCS_VALUE_TYPE_float) {
            g_telem.fuelCapacityLiters = std::max(1.0f, cur->value.value_float.value);
        } else if (std::strcmp(cur->name, SCS_TELEMETRY_CONFIG_ATTRIBUTE_adblue_capacity) == 0 && cur->value.type == SCS_VALUE_TYPE_float) {
            g_telem.adblueCapacityLiters = std::max(1.0f, cur->value.value_float.value);
        } else if (std::strcmp(cur->name, SCS_TELEMETRY_CONFIG_ATTRIBUTE_forward_gear_count) == 0 && cur->value.type == SCS_VALUE_TYPE_u32) {
            g_telem.pkt.forwardGears = static_cast<uint8_t>(cur->value.value_u32.value);
        }
    }
}

SCSAPI_VOID onTelemFloat(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t ctx) {
    if (!val || val->type != SCS_VALUE_TYPE_float || !ctx) return;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    *static_cast<float*>(ctx) = val->value_float.value;
}

SCSAPI_VOID onTelemS32(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t ctx) {
    if (!val || val->type != SCS_VALUE_TYPE_s32 || !ctx) return;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    *static_cast<int8_t*>(ctx) = static_cast<int8_t>(val->value_s32.value);
}

SCSAPI_VOID onTelemU32(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t ctx) {
    if (!val || val->type != SCS_VALUE_TYPE_u32 || !ctx) return;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    *static_cast<uint8_t*>(ctx) = static_cast<uint8_t>(val->value_u32.value);
}

SCSAPI_VOID onTelemBoolDash(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t ctx) {
    if (!val || val->type != SCS_VALUE_TYPE_bool || !ctx) return;
    const uint16_t bit = static_cast<uint16_t>(reinterpret_cast<uintptr_t>(ctx));
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    if (val->value_bool.value) g_telem.pkt.dashboardFlags |= bit;
    else                       g_telem.pkt.dashboardFlags &= ~bit;
}

SCSAPI_VOID onTelemBoolSafety(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t ctx) {
    if (!val || val->type != SCS_VALUE_TYPE_bool || !ctx) return;
    const uint8_t bit = static_cast<uint8_t>(reinterpret_cast<uintptr_t>(ctx));
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    if (val->value_bool.value) g_telem.pkt.safetyFlags |= bit;
    else                       g_telem.pkt.safetyFlags &= ~bit;
}

SCSAPI_VOID onTelemSpeedMps(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_float) return;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    g_telem.pkt.speedKmh = std::abs(val->value_float.value) * 3.6f;
}

SCSAPI_VOID onTelemCruiseMps(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_float) return;
    const float kmh = val->value_float.value * 3.6f;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    g_telem.pkt.cruiseSpeedKmh = static_cast<uint8_t>(kmh > 0.0f ? kmh + 0.5f : 0.0f);
    if (kmh > 0.5f) g_telem.pkt.dashboardFlags |= DASH_CRUISE_ACTIVE;
    else            g_telem.pkt.dashboardFlags &= ~DASH_CRUISE_ACTIVE;
}

SCSAPI_VOID onTelemSpeedLimitMps(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_float) return;
    const float kmh = val->value_float.value * 3.6f;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    g_telem.pkt.speedLimitKmh = static_cast<uint8_t>(kmh > 0.0f ? kmh + 0.5f : 0.0f);
}

SCSAPI_VOID onTelemFuelLiters(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_float) return;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    float pct = (val->value_float.value / g_telem.fuelCapacityLiters) * 100.0f;
    g_telem.pkt.fuelPercent = static_cast<uint8_t>(std::clamp(pct, 0.0f, 100.0f));
}

SCSAPI_VOID onTelemAdblueLiters(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_float) return;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    float pct = (val->value_float.value / g_telem.adblueCapacityLiters) * 100.0f;
    g_telem.pkt.adbluePercent = static_cast<uint8_t>(std::clamp(pct, 0.0f, 100.0f));
}

SCSAPI_VOID onTelemNavDistance(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_float) return;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    g_telem.pkt.routeDistanceKm = static_cast<uint16_t>(std::max(0.0f, val->value_float.value / 1000.0f));
}

SCSAPI_VOID onTelemNavTime(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_float) return;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    g_telem.pkt.routeTimeMin = static_cast<uint16_t>(std::max(0.0f, val->value_float.value / 60.0f));
}

SCSAPI_VOID onTelemWear(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t ctx) {
    if (!val || val->type != SCS_VALUE_TYPE_float) return;
    const size_t idx = reinterpret_cast<uintptr_t>(ctx);
    if (idx >= 5) return;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    g_telem.wearValues[idx] = val->value_float.value;
    float maxW = 0.0f;
    for (float w : g_telem.wearValues) maxW = std::max(maxW, w);
    g_telem.pkt.truckDamagePct = static_cast<uint8_t>(std::clamp(maxW * 100.0f, 0.0f, 100.0f));
}

SCSAPI_VOID onTelemAccel(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_fvector) return;
    const float latG  = (val->value_fvector.x / 9.80665f) * 100.0f;
    const float longG = (val->value_fvector.z / 9.80665f) * 100.0f;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    g_telem.pkt.lateralG100      = static_cast<int8_t>(std::clamp(latG, -127.0f, 127.0f));
    g_telem.pkt.longitudinalG100 = static_cast<int8_t>(std::clamp(longG, -127.0f, 127.0f));

    const float rpmNorm = (g_telem.pkt.maxRpm > 100.0f) ? (g_telem.pkt.engineRpm / g_telem.pkt.maxRpm) : 0.0f;
    const float gPeak   = std::max(std::abs(latG), std::abs(longG));
    g_telem.pkt.rumbleSmall = static_cast<uint8_t>(std::min(255.0f, rpmNorm * 55.0f));
    g_telem.pkt.rumbleLarge = (gPeak > 28.0f) ? static_cast<uint8_t>(std::min(255.0f, (gPeak - 28.0f) * 4.5f)) : 0;
}

} // namespace

SCSAPI_RESULT scs_input_init(const scs_u32_t version, const scs_input_init_params_t* const params) {
    if (version != SCS_INPUT_VERSION_1_00 || !params) return SCS_RESULT_unsupported;

    const auto* v100 = reinterpret_cast<const scs_input_init_params_v100_t*>(params);

    scs_input_device_input_t scsInputs[TOTAL_INPUT_CHANS]{};
    for (size_t i = 0; i < TOTAL_INPUT_CHANS; ++i) {
        scsInputs[i].name = kChannels[i].name;
        scsInputs[i].display_name = kChannels[i].displayName;
        scsInputs[i].value_type = kChannels[i].type;
    }

    scs_input_device_t device{};
    device.name = "ets2_phone_cockpit";
    device.display_name = "ETS2 Phone Cockpit Controller";
    device.type = SCS_INPUT_DEVICE_TYPE_generic;
    device.input_count = static_cast<scs_u32_t>(TOTAL_INPUT_CHANS);
    device.inputs = scsInputs;
    device.input_active_callback = &inputActivityCallback;
    device.input_event_callback = &inputNextEventCallback;
    device.callback_context = nullptr;

    if (v100->register_device(&device) != SCS_RESULT_ok) {
        return SCS_RESULT_generic_error;
    }

    ensureNetworkStarted();
    return SCS_RESULT_ok;
}

SCSAPI_VOID scs_input_shutdown(void) {
    ensureNetworkStopped();
}

SCSAPI_RESULT scs_telemetry_init(const scs_u32_t version, const scs_telemetry_init_params_t* const params) {
    if (version != SCS_TELEMETRY_VERSION_1_01 || !params) return SCS_RESULT_unsupported;

    {
        std::lock_guard<std::mutex> lock(g_telem.mtx);
        g_telem.pkt.maxRpm = 2500.0f;
        g_telem.pkt.forwardGears = 12;
        g_telem.pkt.fuelPercent = 100;
        g_telem.pkt.adbluePercent = 100;
    }

    const auto* v100 = reinterpret_cast<const scs_telemetry_init_params_v100_t*>(params);
    v100->register_for_event(SCS_TELEMETRY_EVENT_configuration, &onConfiguration, nullptr);

    auto reg = v100->register_for_channel;
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_speed, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemSpeedMps, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_engine_rpm, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemFloat, &g_telem.pkt.engineRpm);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_brake_air_pressure, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemFloat, &g_telem.pkt.airPressureBar);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_engine_gear, SCS_U32_NIL, SCS_VALUE_TYPE_s32, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemS32, &g_telem.pkt.gear);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_cruise_control, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemCruiseMps, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_navigation_speed_limit, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemSpeedLimitMps, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_navigation_distance, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemNavDistance, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_navigation_time, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemNavTime, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_fuel, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemFuelLiters, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_adblue, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemAdblueLiters, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_local_linear_acceleration, SCS_U32_NIL, SCS_VALUE_TYPE_fvector, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemAccel, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_retarder_level, SCS_U32_NIL, SCS_VALUE_TYPE_u32, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemU32, &g_telem.pkt.retarderLevel);

    reg(SCS_TELEMETRY_TRUCK_CHANNEL_wear_engine, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemWear, reinterpret_cast<scs_context_t>(0));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_wear_transmission, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemWear, reinterpret_cast<scs_context_t>(1));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_wear_cabin, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemWear, reinterpret_cast<scs_context_t>(2));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_wear_chassis, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemWear, reinterpret_cast<scs_context_t>(3));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_wear_wheels, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemWear, reinterpret_cast<scs_context_t>(4));

    reg(SCS_TELEMETRY_TRUCK_CHANNEL_lblinker, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_LEFT_BLINKER));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_rblinker, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_RIGHT_BLINKER));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_hazard_warning, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_HAZARD));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_parking_brake, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_PARK_BRAKE));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_electric_enabled, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_ENGINE_RUNNING));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_brake_air_pressure_warning, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_LOW_AIR_WARN));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_motor_brake, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_ENGINE_BRK_ACTIVE));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_light_low_beam, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_LOW_BEAMS));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_light_high_beam, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_HIGH_BEAMS));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_light_beacon, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_BEACONS));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_wipers, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_WIPERS_ACTIVE));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_differential_lock, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_DIFF_LOCK));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_lift_axle, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_TRUCK_LIFT_AXLE));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_trailer_lift_axle, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_TRAILER_LIFT_AXLE));

    reg(SCS_TELEMETRY_TRAILER_CHANNEL_connected, 0, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolSafety, reinterpret_cast<scs_context_t>(SAFETY_TRAILER_CONNECTED));

    ensureNetworkStarted();
    return SCS_RESULT_ok;
}

SCSAPI_VOID scs_telemetry_shutdown(void) {
    ensureNetworkStopped();
}