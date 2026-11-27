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
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "scssdk_telemetry.h"
#include "scssdk_input.h"
#include "eurotrucks2/scssdk_eut2.h"
#include "eurotrucks2/scssdk_telemetry_eut2.h"

#include "protocol.h"

#ifdef _MSC_VER
#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "iphlpapi.lib")
#pragma comment(lib, "user32.lib")
#endif

namespace {

constexpr size_t NUM_ANALOG_AXES     = 6;
constexpr size_t NUM_BUTTON_CHANS    = 64;
constexpr size_t TOTAL_INPUT_CHANS   = NUM_ANALOG_AXES + NUM_BUTTON_CHANS;
constexpr size_t MAX_MONITORED_WHEELS = 8;

enum class ActiveTransport { NONE, UDP, TCP };

struct SharedControlState {
    std::atomic<float>    axes[NUM_ANALOG_AXES]{};
    std::atomic<uint64_t> buttonMask{0};
    std::atomic<uint64_t> latchedPressMask{0};
    std::atomic<uint16_t> lastClientTs{0};
    std::atomic<bool>     hasClient{false};
    std::atomic<bool>     coachSmoothMode{false};
};

struct LiveTelemetryCache {
    std::mutex mtx;
    TruckTelemetryPacket pkt{};
    float    fuelCapacityLiters   = 800.0f;
    float    adblueCapacityLiters = 80.0f;
    float    wearValues[5]{};
    uint32_t wheelCount           = 4;
    float    prevSuspDefl[MAX_MONITORED_WHEELS]{};
    bool     hasPrevSusp[MAX_MONITORED_WHEELS]{};
    bool     wheelOnGround[MAX_MONITORED_WHEELS]{};
    uint32_t wheelSubstance[MAX_MONITORED_WHEELS]{};
    float    suspRumblePeak       = 0.0f;
    float    surfaceRumbleLevel   = 0.0f;
};

SharedControlState g_ctrl;
LiveTelemetryCache g_telem;
scs_log_t          g_scsLog = nullptr;

std::atomic<bool>  g_netRunning{false};
std::thread        g_netThread;
std::thread        g_beaconThread;

std::mutex         g_clientAddrMtx;
sockaddr_in        g_clientAddr{};
SOCKET             g_udpSocket = INVALID_SOCKET;

void logInfo(const char* msg) {
    if (g_scsLog) g_scsLog(SCS_LOG_TYPE_message, msg);
}

void logWarn(const char* msg) {
    if (g_scsLog) g_scsLog(SCS_LOG_TYPE_warning, msg);
}

struct ChannelSpec {
    const char* name;
    const char* displayName;
    scs_value_type_t type;
};

constexpr ChannelSpec kChannels[TOTAL_INPUT_CHANS] = {
    // 6 Analog Axes (Indices 0..5)
    {"steering",          "Phone Steering Axis",              SCS_VALUE_TYPE_float},
    {"throttle",          "Phone Throttle Axis",              SCS_VALUE_TYPE_float},
    {"brake",             "Phone Brake Axis",                 SCS_VALUE_TYPE_float},
    {"clutch",            "Phone Clutch Axis",                SCS_VALUE_TYPE_float},
    {"look_x",            "Phone Cabin Look Left Right",      SCS_VALUE_TYPE_float},
    {"look_y",            "Phone Cabin Look Up Down",         SCS_VALUE_TYPE_float},

    // 60 Digital Buttons (Indices 6..65 -> maps 1:1 to buttonMask bits 0..59)
    {"horn",              "Phone Horn",                       SCS_VALUE_TYPE_bool},
    {"engine_toggle",     "Phone Engine Start Stop",          SCS_VALUE_TYPE_bool},
    {"lights_cycle",      "Phone Lights Cycle",               SCS_VALUE_TYPE_bool},
    {"hazard_lights",     "Phone Hazard Flashers",            SCS_VALUE_TYPE_bool},
    {"blinker_left",      "Phone Left Turn Indicator",        SCS_VALUE_TYPE_bool},
    {"blinker_right",     "Phone Right Turn Indicator",       SCS_VALUE_TYPE_bool},
    {"park_brake",        "Phone Parking Brake",              SCS_VALUE_TYPE_bool},
    {"engine_brake",      "Phone Engine Brake Toggle",        SCS_VALUE_TYPE_bool},
    {"advisor_map",       "Phone Advisor GPS Page",           SCS_VALUE_TYPE_bool},
    {"cruise_toggle",     "Phone Cruise Control Toggle",      SCS_VALUE_TYPE_bool},
    {"shift_up",          "Phone Shift Up",                   SCS_VALUE_TYPE_bool},
    {"shift_down",        "Phone Shift Down",                 SCS_VALUE_TYPE_bool},
    {"wipers_up",         "Phone Wipers Forward",             SCS_VALUE_TYPE_bool},
    {"cam_interior",      "Phone Interior Camera",            SCS_VALUE_TYPE_bool},
    {"radio_toggle",      "Phone Audio Player Toggle",        SCS_VALUE_TYPE_bool},
    {"radio_next",        "Phone Radio Next Favorite",        SCS_VALUE_TYPE_bool},
    {"radio_prev",        "Phone Radio Prev Favorite",        SCS_VALUE_TYPE_bool},
    {"shift_neutral",     "Phone Shift to Neutral",           SCS_VALUE_TYPE_bool},
    {"diff_lock",         "Phone Differential Lock",          SCS_VALUE_TYPE_bool},
    {"lift_axle",         "Phone Truck Lift Axle",            SCS_VALUE_TYPE_bool},
    {"trailer_couple",    "Phone Trailer Attach Detach",      SCS_VALUE_TYPE_bool},
    {"high_beams",        "Phone High Beam Headlights",       SCS_VALUE_TYPE_bool},
    {"beacons",           "Phone Roof Warning Beacons",       SCS_VALUE_TYPE_bool},
    {"air_horn",          "Phone Roof Air Horn",              SCS_VALUE_TYPE_bool},
    {"retarder_up",       "Phone Retarder Increase",          SCS_VALUE_TYPE_bool},
    {"retarder_down",     "Phone Retarder Decrease",          SCS_VALUE_TYPE_bool},
    {"cruise_up",         "Phone Cruise Speed Up",            SCS_VALUE_TYPE_bool},
    {"cruise_down",       "Phone Cruise Speed Down",          SCS_VALUE_TYPE_bool},
    {"cruise_resume",     "Phone Cruise Control Resume",      SCS_VALUE_TYPE_bool},
    {"dash_info",         "Phone Dashboard Info Mode",        SCS_VALUE_TYPE_bool},
    {"activate",          "Phone Activate Enter",             SCS_VALUE_TYPE_bool},
    {"advisor_job",       "Phone Advisor Job Info",           SCS_VALUE_TYPE_bool},
    {"advisor_service",   "Phone Advisor Damage Service",     SCS_VALUE_TYPE_bool},
    {"cam_window_lean",   "Phone Lean Out Window Camera",     SCS_VALUE_TYPE_bool},
    {"cam_chase",         "Phone Exterior Chase Camera",      SCS_VALUE_TYPE_bool},
    {"light_horn",        "Phone Light Horn Flash",           SCS_VALUE_TYPE_bool},
    {"wipers_down",       "Phone Wipers Back",                SCS_VALUE_TYPE_bool},
    {"cam_top",           "Phone Top Down Parking Camera",    SCS_VALUE_TYPE_bool},
    {"look_left_snap",    "Phone Quick Look Left",            SCS_VALUE_TYPE_bool},
    {"look_right_snap",   "Phone Quick Look Right",           SCS_VALUE_TYPE_bool},
    {"mirrors_toggle",    "Phone Side Mirrors Toggle",        SCS_VALUE_TYPE_bool},
    {"advisor_rest",      "Phone Advisor Rest Messages",      SCS_VALUE_TYPE_bool},
    {"world_map",         "Phone World Map",                  SCS_VALUE_TYPE_bool},
    {"esc_menu",          "Phone Pause Menu",                 SCS_VALUE_TYPE_bool},
    {"trailer_lift_axle", "Phone Trailer Lift Axle",          SCS_VALUE_TYPE_bool},
    {"trailer_brake",     "Phone Trailer Brake",              SCS_VALUE_TYPE_bool},
    {"acc_distance",      "Phone ACC Following Distance",     SCS_VALUE_TYPE_bool},
    {"lka_toggle",        "Phone Lane Keeping Assistant",     SCS_VALUE_TYPE_bool},
    {"aebs_toggle",       "Phone Emergency Brake Mode",       SCS_VALUE_TYPE_bool},
    {"susp_front_up",     "Phone Front Suspension Up",        SCS_VALUE_TYPE_bool},
    {"susp_front_down",   "Phone Front Suspension Down",      SCS_VALUE_TYPE_bool},
    {"susp_rear_up",      "Phone Rear Suspension Up",         SCS_VALUE_TYPE_bool},
    {"susp_rear_down",    "Phone Rear Suspension Down",       SCS_VALUE_TYPE_bool},
    {"susp_reset",        "Phone Air Suspension Reset",       SCS_VALUE_TYPE_bool},
    {"window_left",       "Phone Left Power Window",          SCS_VALUE_TYPE_bool},
    {"window_right",      "Phone Right Power Window",         SCS_VALUE_TYPE_bool},
    {"shift_drive",       "Phone Shift to Drive",             SCS_VALUE_TYPE_bool},
    {"shift_reverse",     "Phone Shift to Reverse",           SCS_VALUE_TYPE_bool},
    {"coach_light",       "Phone Coach Cabin Lighting",       SCS_VALUE_TYPE_bool},
    {"coach_display",     "Phone Coach Infotainment Mode",    SCS_VALUE_TYPE_bool},
    {"radio_play_pause",  "Phone Radio Play Pause",           SCS_VALUE_TYPE_bool},
    {"radio_vol_up",      "Phone Radio Volume Up",            SCS_VALUE_TYPE_bool},
    {"radio_vol_down",    "Phone Radio Volume Down",          SCS_VALUE_TYPE_bool},
    {"radio_fav",         "Phone Radio Favorite",             SCS_VALUE_TYPE_bool}
};

float    g_lastReportedAxes[NUM_ANALOG_AXES]{};
uint64_t g_lastReportedButtons = 0;
size_t   g_pollCursor = 0;
bool     g_deviceActive = false;

SCSAPI_VOID inputActivityCallback(const scs_u8_t active, const scs_context_t) {
    g_deviceActive = (active != 0);
    g_pollCursor = 0;
    logInfo(g_deviceActive
        ? "[ETS2Phone] Input device activated by ETS2."
        : "[ETS2Phone] Input device deactivated by ETS2.");
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

    const uint64_t liveMask      = g_ctrl.buttonMask.load(std::memory_order_relaxed);
    const uint64_t latchedMask   = g_ctrl.latchedPressMask.load(std::memory_order_relaxed);
    const uint64_t effectiveMask = liveMask | latchedMask;
    while (g_pollCursor < TOTAL_INPUT_CHANS) {
        const size_t chanIdx = g_pollCursor++;
        const size_t bitIdx  = chanIdx - NUM_ANALOG_AXES;
        const uint64_t bit   = (1ULL << bitIdx);
        const bool wasDown   = (g_lastReportedButtons & bit) != 0;
        const bool isDown    = (effectiveMask & bit) != 0;
        if (wasDown != isDown) {
            if (isDown) {
                g_lastReportedButtons |= bit;
                g_ctrl.latchedPressMask.fetch_and(~bit, std::memory_order_relaxed);
            } else {
                g_lastReportedButtons &= ~bit;
            }

            eventInfo->input_index = static_cast<scs_u32_t>(chanIdx);
            eventInfo->value_bool.value = isDown ? 1 : 0;
            return SCS_RESULT_ok;
        }
    }

    g_pollCursor = 0;
    return SCS_RESULT_not_found;
}

void zeroAllControls() {
    for (size_t i = 0; i < NUM_ANALOG_AXES; ++i) {
        g_ctrl.axes[i].store(0.0f, std::memory_order_relaxed);
    }
    g_ctrl.buttonMask.store(0ULL, std::memory_order_relaxed);
    g_ctrl.latchedPressMask.store(0ULL, std::memory_order_relaxed);
}

void applyControlPacket(const PhoneControlPacket& pkt, uint64_t mergedMask, uint64_t& prevPacketMask) {
    g_ctrl.axes[0].store(static_cast<float>(pkt.steering) / 32767.0f, std::memory_order_relaxed);
    g_ctrl.axes[1].store(static_cast<float>(pkt.throttle) / 65535.0f, std::memory_order_relaxed);
    g_ctrl.axes[2].store(static_cast<float>(pkt.brake)    / 65535.0f, std::memory_order_relaxed);
    g_ctrl.axes[3].store(static_cast<float>(pkt.clutch)   / 65535.0f, std::memory_order_relaxed);

    const bool lookAutocenter = (pkt.flags & UPLINK_FLAG_LOOK_AUTOCENTER) != 0;
    float lx = static_cast<float>(pkt.lookX) / 32767.0f;
    float ly = static_cast<float>(pkt.lookY) / 32767.0f;
    if (lookAutocenter && std::abs(pkt.lookX) < 16 && std::abs(pkt.lookY) < 16) {
        lx = 0.0f;
        ly = 0.0f;
    }
    g_ctrl.axes[4].store(lx, std::memory_order_relaxed);
    g_ctrl.axes[5].store(ly, std::memory_order_relaxed);
    g_ctrl.coachSmoothMode.store((pkt.flags & UPLINK_FLAG_COACH_SMOOTH_MODE) != 0, std::memory_order_relaxed);

    const uint64_t newlyPressed = mergedMask & ~prevPacketMask;
    prevPacketMask = mergedMask;
    if (newlyPressed != 0) {
        g_ctrl.latchedPressMask.fetch_or(newlyPressed, std::memory_order_relaxed);
    }
    g_ctrl.buttonMask.store(mergedMask, std::memory_order_relaxed);
    g_ctrl.lastClientTs.store(pkt.clientTimestampMs, std::memory_order_relaxed);
}

struct RadioStationEntry {
    std::string payload; // "name\tgenre\tcountry\turl"
    uint16_t    bitrate = 128;
    uint8_t     flags   = 0;
};

std::mutex                     g_radioMtx;
std::vector<RadioStationEntry> g_radioStations;
std::atomic<bool>              g_radioSyncRequested{false};

std::string decodeSiiEscapes(const std::string& raw) {
    std::string out;
    out.reserve(raw.size());
    for (size_t i = 0; i < raw.size(); ++i) {
        if (raw[i] == '\\' && i + 3 < raw.size() && raw[i + 1] == 'x') {
            auto hexVal = [](char c) -> int {
                if (c >= '0' && c <= '9') return c - '0';
                if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
                if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
                return -1;
            };
            int hi = hexVal(raw[i + 2]);
            int lo = hexVal(raw[i + 3]);
            if (hi >= 0 && lo >= 0) {
                out.push_back(static_cast<char>((hi << 4) | lo));
                i += 3;
                continue;
            }
        }
        out.push_back(raw[i]);
    }
    return out;
}

void loadRadioStations() {
    std::vector<std::wstring> candidates;
    wchar_t buf[MAX_PATH];
    if (GetEnvironmentVariableW(L"USERPROFILE", buf, MAX_PATH) > 0) {
        std::wstring up(buf);
        candidates.push_back(up + L"\\OneDrive\\Documents\\Euro Truck Simulator 2\\live_streams.sii");
        candidates.push_back(up + L"\\Documents\\Euro Truck Simulator 2\\live_streams.sii");
    }
    if (GetEnvironmentVariableW(L"OneDrive", buf, MAX_PATH) > 0) {
        candidates.push_back(std::wstring(buf) + L"\\Documents\\Euro Truck Simulator 2\\live_streams.sii");
    }

    std::ifstream file;
    for (const auto& path : candidates) {
        std::string narrow(path.begin(), path.end());
        file.open(narrow, std::ios::in);
        if (file.is_open()) break;
        file.clear();
    }
    if (!file.is_open()) return;

    std::vector<RadioStationEntry> parsed;
    std::string line;
    while (std::getline(file, line)) {
        if (line.find("stream_data[") == std::string::npos) continue;
        size_t q1 = line.find('"');
        size_t q2 = (q1 != std::string::npos) ? line.rfind('"') : std::string::npos;
        if (q1 == std::string::npos || q2 == std::string::npos || q2 <= q1 + 1) continue;
        std::string inner = line.substr(q1 + 1, q2 - q1 - 1);

        // Split into 6 pipe fields: url|name|genre|country|bitrate|fav
        std::vector<std::string> fields;
        size_t start = 0;
        for (int k = 0; k < 5; ++k) {
            size_t pos = inner.find('|', start);
            if (pos == std::string::npos) break;
            fields.push_back(inner.substr(start, pos - start));
            start = pos + 1;
        }
        fields.push_back(inner.substr(start));
        if (fields.size() < 6) continue;

        std::string url     = fields[0];
        std::string name    = decodeSiiEscapes(fields[1]);
        std::string genre   = decodeSiiEscapes(fields[2]);
        std::string country = decodeSiiEscapes(fields[3]);
        uint16_t bitrate    = static_cast<uint16_t>(std::atoi(fields[4].c_str()));
        uint8_t flags       = (std::atoi(fields[5].c_str()) != 0) ? RADIO_FLAG_FAVORITE : 0;

        auto stripTabs = [](std::string& s) {
            for (char& ch : s) if (ch == '\t' || ch == '\r' || ch == '\n') ch = ' ';
        };
        stripTabs(name);
        stripTabs(genre);
        stripTabs(country);
        stripTabs(url);

        std::string payload = name + "\t" + genre + "\t" + country + "\t" + url;
        if (payload.size() > 512) payload.resize(512);
        parsed.push_back(RadioStationEntry{std::move(payload), bitrate, flags});
    }

    std::lock_guard<std::mutex> lock(g_radioMtx);
    g_radioStations = std::move(parsed);
}

void buildRadioRowPacket(uint16_t idx, uint16_t total, const RadioStationEntry& e, std::vector<uint8_t>& out) {
    const uint16_t plen = static_cast<uint16_t>(std::min<size_t>(e.payload.size(), 512));
    out.resize(sizeof(RadioRowHeader) + plen + 1);
    RadioRowHeader hdr{};
    hdr.magic      = PACKET_MAGIC_RADIO_ROW;
    hdr.index      = idx;
    hdr.totalCount = total;
    hdr.flags      = e.flags;
    hdr.bitrate    = e.bitrate;
    hdr.payloadLen = plen;
    std::memcpy(out.data(), &hdr, sizeof(hdr));
    if (plen > 0) std::memcpy(out.data() + sizeof(hdr), e.payload.data(), plen);
    out.back() = computeXorChecksum(out.data(), out.size() - 1);
}

HWND findEts2ForegroundWindow() {
    HWND fg = GetForegroundWindow();
    if (!fg) return nullptr;
    DWORD fgPid = 0;
    GetWindowThreadProcessId(fg, &fgPid);
    return (fgPid == GetCurrentProcessId()) ? fg : nullptr;
}

void sendEts2Key(WORD vk, bool extended) {
    if (!findEts2ForegroundWindow()) return;
    UINT sc = MapVirtualKeyW(vk, MAPVK_VK_TO_VSC);
    INPUT inputs[2]{};
    inputs[0].type = INPUT_KEYBOARD;
    inputs[0].ki.wVk = vk;
    inputs[0].ki.wScan = static_cast<WORD>(sc);
    inputs[0].ki.dwFlags = KEYEVENTF_SCANCODE | (extended ? KEYEVENTF_EXTENDEDKEY : 0);
    inputs[1] = inputs[0];
    inputs[1].ki.dwFlags |= KEYEVENTF_KEYUP;
    SendInput(1, &inputs[0], sizeof(INPUT));
    Sleep(22);
    SendInput(1, &inputs[1], sizeof(INPUT));
}

void clickEts2Ui16x9(float nx, float ny) {
    HWND fg = findEts2ForegroundWindow();
    if (!fg) return;
    RECT rc{};
    if (!GetClientRect(fg, &rc) || rc.right <= rc.left || rc.bottom <= rc.top) return;
    const float cw = static_cast<float>(rc.right - rc.left);
    const float ch = static_cast<float>(rc.bottom - rc.top);
    const float uiW = std::min(cw, ch * (16.0f / 9.0f));
    const float leftPad = (cw - uiW) * 0.5f;

    POINT pt{
        static_cast<LONG>(leftPad + nx * uiW),
        static_cast<LONG>(ny * ch)
    };
    if (!ClientToScreen(fg, &pt)) return;

    const int vx = GetSystemMetrics(SM_XVIRTUALSCREEN);
    const int vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
    const int vw = std::max(1, GetSystemMetrics(SM_CXVIRTUALSCREEN) - 1);
    const int vh = std::max(1, GetSystemMetrics(SM_CYVIRTUALSCREEN) - 1);
    const LONG absX = static_cast<LONG>(((pt.x - vx) * 65535.0) / vw);
    const LONG absY = static_cast<LONG>(((pt.y - vy) * 65535.0) / vh);

    INPUT mv{};
    mv.type = INPUT_MOUSE;
    mv.mi.dx = absX;
    mv.mi.dy = absY;
    mv.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
    SendInput(1, &mv, sizeof(INPUT));
    Sleep(18);

    INPUT down{};
    down.type = INPUT_MOUSE;
    down.mi.dx = absX;
    down.mi.dy = absY;
    down.mi.dwFlags = MOUSEEVENTF_LEFTDOWN | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
    SendInput(1, &down, sizeof(INPUT));
    Sleep(24);

    INPUT up = down;
    up.mi.dwFlags = MOUSEEVENTF_LEFTUP | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
    SendInput(1, &up, sizeof(INPUT));
}

void handleUiCommand(const UiCommandPacket& c) {
    switch (c.cmd) {
        case UI_CMD_UP:               sendEts2Key(VK_UP, true); break;
        case UI_CMD_DOWN:             sendEts2Key(VK_DOWN, true); break;
        case UI_CMD_LEFT:             sendEts2Key(VK_LEFT, true); break;
        case UI_CMD_RIGHT:            sendEts2Key(VK_RIGHT, true); break;
        case UI_CMD_TAB:              sendEts2Key(VK_TAB, false); break;
        case UI_CMD_ENTER:            sendEts2Key(VK_RETURN, false); break;
        case UI_CMD_ESC:              sendEts2Key(VK_ESCAPE, false); break;
        case UI_CMD_DELIVERY_OPT_1:   clickEts2Ui16x9(0.41f, 0.31f); break;
        case UI_CMD_DELIVERY_OPT_2:   clickEts2Ui16x9(0.41f, 0.45f); break;
        case UI_CMD_DELIVERY_OPT_3:   clickEts2Ui16x9(0.41f, 0.59f); break;
        case UI_CMD_DELIVERY_CONFIRM: clickEts2Ui16x9(0.50f, 0.725f); break;
        case UI_CMD_RADIO_LIST_REQ:
            loadRadioStations();
            g_radioSyncRequested.store(true, std::memory_order_relaxed);
            break;
        default: break;
    }
}

void networkWorker() {
    WSADATA wsa{};
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        logWarn("[ETS2Phone] WSAStartup failed.");
        return;
    }

    g_udpSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (g_udpSocket == INVALID_SOCKET) {
        logWarn("[ETS2Phone] Failed to create UDP control socket.");
        WSACleanup();
        return;
    }

    int rcvBuf = 131072;
    setsockopt(g_udpSocket, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<const char*>(&rcvBuf), sizeof(rcvBuf));

    BOOL connReset = FALSE;
    DWORD bytesRet = 0;
    WSAIoctl(g_udpSocket, _WSAIOW(IOC_VENDOR, 12), &connReset, sizeof(connReset), nullptr, 0, &bytesRet, nullptr, nullptr);

    u_long nonBlocking = 1;
    ioctlsocket(g_udpSocket, FIONBIO, &nonBlocking);

    sockaddr_in bindAddr{};
    bindAddr.sin_family = AF_INET;
    bindAddr.sin_port = htons(PORT_CONTROL);
    bindAddr.sin_addr.s_addr = INADDR_ANY;

    if (bind(g_udpSocket, reinterpret_cast<sockaddr*>(&bindAddr), sizeof(bindAddr)) == SOCKET_ERROR) {
        char errBuf[128];
        std::snprintf(errBuf, sizeof(errBuf), "[ETS2Phone] Failed to bind UDP port %u (WSA error %d).", PORT_CONTROL, WSAGetLastError());
        logWarn(errBuf);
        closesocket(g_udpSocket);
        g_udpSocket = INVALID_SOCKET;
        WSACleanup();
        return;
    }

    // Optional local TCP loopback listener on 127.0.0.1:5005 for ADB reverse wired USB mode
    SOCKET tcpListenSock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (tcpListenSock != INVALID_SOCKET) {
        BOOL reuse = TRUE;
        setsockopt(tcpListenSock, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuse), sizeof(reuse));
        ioctlsocket(tcpListenSock, FIONBIO, &nonBlocking);

        sockaddr_in tcpAddr{};
        tcpAddr.sin_family = AF_INET;
        tcpAddr.sin_port = htons(PORT_CONTROL);
        tcpAddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (bind(tcpListenSock, reinterpret_cast<sockaddr*>(&tcpAddr), sizeof(tcpAddr)) == SOCKET_ERROR ||
            listen(tcpListenSock, 2) == SOCKET_ERROR) {
            closesocket(tcpListenSock);
            tcpListenSock = INVALID_SOCKET;
        }
    }

    {
        char okBuf[128];
        std::snprintf(okBuf, sizeof(okBuf), "[ETS2Phone] Listening on UDP %u (and local TCP loopback %s).",
                      PORT_CONTROL, tcpListenSock != INVALID_SOCKET ? "active" : "unavailable");
        logInfo(okBuf);
    }

    loadRadioStations();

    SOCKET tcpClientSock = INVALID_SOCKET;
    uint8_t tcpStream[1024]{};
    size_t tcpStreamLen = 0;

    uint16_t lastSeq = 0;
    uint64_t prevPacketMask = 0;
    bool hasSeq = false;
    sockaddr_in activeSource{};
    ActiveTransport activeTransport = ActiveTransport::NONE;
    auto lastPacketRx = std::chrono::steady_clock::now();
    auto lastTelemSend = std::chrono::steady_clock::now();
    uint8_t rxBuf[128];
    size_t radioSendCursor = 0;
    bool radioSending = false;
    std::vector<uint8_t> radioPktBuf;

    while (g_netRunning.load(std::memory_order_acquire)) {
        fd_set readSet;
        FD_ZERO(&readSet);
        FD_SET(g_udpSocket, &readSet);
        if (tcpListenSock != INVALID_SOCKET) FD_SET(tcpListenSock, &readSet);
        if (tcpClientSock != INVALID_SOCKET) FD_SET(tcpClientSock, &readSet);

        timeval tv{0, 10000}; // 10 ms select timeout
        int sel = select(0, &readSet, nullptr, nullptr, &tv);

        if (sel > 0) {
            // 1. Accept incoming ADB USB TCP client
            if (tcpListenSock != INVALID_SOCKET && FD_ISSET(tcpListenSock, &readSet)) {
                SOCKET incoming = accept(tcpListenSock, nullptr, nullptr);
                if (incoming != INVALID_SOCKET) {
                    if (tcpClientSock != INVALID_SOCKET) closesocket(tcpClientSock);
                    tcpClientSock = incoming;
                    tcpStreamLen = 0;
                    BOOL noDelay = TRUE;
                    setsockopt(tcpClientSock, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&noDelay), sizeof(noDelay));
                    ioctlsocket(tcpClientSock, FIONBIO, &nonBlocking);
                    hasSeq = false;
                    logInfo("[ETS2Phone] USB ADB TCP client connected on loopback.");
                }
            }

            // 2. Drain UDP packets
            if (FD_ISSET(g_udpSocket, &readSet)) {
                uint64_t mergedMask = g_ctrl.buttonMask.load(std::memory_order_relaxed);
                bool gotAny = false;
                PhoneControlPacket latest{};
                sockaddr_in latestFrom{};

                while (true) {
                    sockaddr_in from{};
                    int fromLen = sizeof(from);
                    int r = recvfrom(g_udpSocket, reinterpret_cast<char*>(rxBuf), sizeof(rxBuf), 0,
                                     reinterpret_cast<sockaddr*>(&from), &fromLen);
                    if (r <= 0) break;

                    PhoneControlPacket pkt{};
                    UiCommandPacket uiCmd{};
                    if (validateControlPacket(rxBuf, static_cast<size_t>(r), &pkt)) {
                        const bool sourceChanged = (activeTransport != ActiveTransport::UDP) ||
                                                   (from.sin_addr.s_addr != activeSource.sin_addr.s_addr) ||
                                                   (from.sin_port != activeSource.sin_port);
                        if (sourceChanged) {
                            hasSeq = false;
                            activeSource = from;
                        }
                        if (hasSeq) {
                            int16_t diff = static_cast<int16_t>(pkt.sequenceId - lastSeq);
                            if (diff <= 0 && diff > -200) continue;
                        }
                        lastSeq = pkt.sequenceId;
                        hasSeq = true;
                        mergedMask = gotAny ? (mergedMask | pkt.buttonMask) : pkt.buttonMask;
                        latest = pkt;
                        latestFrom = from;
                        gotAny = true;
                    } else if (validateUiCommandPacket(rxBuf, static_cast<size_t>(r), &uiCmd)) {
                        handleUiCommand(uiCmd);
                    }
                }

                if (gotAny) {
                    lastPacketRx = std::chrono::steady_clock::now();
                    activeTransport = ActiveTransport::UDP;
                    applyControlPacket(latest, mergedMask, prevPacketMask);

                    bool wasConnected = g_ctrl.hasClient.exchange(true, std::memory_order_relaxed);
                    {
                        std::lock_guard<std::mutex> lock(g_clientAddrMtx);
                        g_clientAddr = latestFrom;
                    }
                    if (!wasConnected) {
                        radioSendCursor = 0;
                        radioSending = true;
                        char ipStr[INET_ADDRSTRLEN]{};
                        inet_ntop(AF_INET, &latestFrom.sin_addr, ipStr, sizeof(ipStr));
                        char msg[128];
                        std::snprintf(msg, sizeof(msg), "[ETS2Phone] Phone connected (UDP) from %s:%u", ipStr, ntohs(latestFrom.sin_port));
                        logInfo(msg);
                    }
                }
            }

            // 3. Drain TCP stream packets (ADB USB wired mode)
            if (tcpClientSock != INVALID_SOCKET && FD_ISSET(tcpClientSock, &readSet)) {
                while (tcpStreamLen < sizeof(tcpStream)) {
                    int r = recv(tcpClientSock, reinterpret_cast<char*>(tcpStream + tcpStreamLen),
                                 static_cast<int>(sizeof(tcpStream) - tcpStreamLen), 0);
                    if (r > 0) {
                        tcpStreamLen += static_cast<size_t>(r);
                    } else if (r == 0 || (r == SOCKET_ERROR && WSAGetLastError() != WSAEWOULDBLOCK)) {
                        closesocket(tcpClientSock);
                        tcpClientSock = INVALID_SOCKET;
                        tcpStreamLen = 0;
                        if (activeTransport == ActiveTransport::TCP) {
                            activeTransport = ActiveTransport::NONE;
                            zeroAllControls();
                        }
                        break;
                    } else {
                        break;
                    }
                }

                uint64_t mergedMask = g_ctrl.buttonMask.load(std::memory_order_relaxed);
                bool gotTcp = false;
                PhoneControlPacket latestTcp{};
                size_t cursor = 0;
                while (cursor + 2 <= tcpStreamLen) {
                    const uint16_t magic = static_cast<uint16_t>(tcpStream[cursor]) |
                                           (static_cast<uint16_t>(tcpStream[cursor + 1]) << 8);
                    if (magic == PROTOCOL_MAGIC) {
                        if (cursor + sizeof(PhoneControlPacket) > tcpStreamLen) break;
                        PhoneControlPacket pkt{};
                        if (validateControlPacket(tcpStream + cursor, sizeof(PhoneControlPacket), &pkt)) {
                            mergedMask = gotTcp ? (mergedMask | pkt.buttonMask) : pkt.buttonMask;
                            latestTcp = pkt;
                            gotTcp = true;
                            cursor += sizeof(PhoneControlPacket);
                        } else {
                            ++cursor;
                        }
                    } else if (magic == PACKET_MAGIC_UI_CMD) {
                        if (cursor + sizeof(UiCommandPacket) > tcpStreamLen) break;
                        UiCommandPacket uiCmd{};
                        if (validateUiCommandPacket(tcpStream + cursor, sizeof(UiCommandPacket), &uiCmd)) {
                            handleUiCommand(uiCmd);
                            cursor += sizeof(UiCommandPacket);
                        } else {
                            ++cursor;
                        }
                    } else {
                        ++cursor; // Resynchronize
                    }
                }
                if (cursor > 0) {
                    if (cursor < tcpStreamLen) {
                        std::memmove(tcpStream, tcpStream + cursor, tcpStreamLen - cursor);
                    }
                    tcpStreamLen -= cursor;
                }

                if (gotTcp) {
                    lastPacketRx = std::chrono::steady_clock::now();
                    activeTransport = ActiveTransport::TCP;
                    hasSeq = true;
                    lastSeq = latestTcp.sequenceId;
                    applyControlPacket(latestTcp, mergedMask, prevPacketMask);
                    bool wasConn = g_ctrl.hasClient.exchange(true, std::memory_order_relaxed);
                    if (!wasConn) {
                        radioSendCursor = 0;
                        radioSending = true;
                    }
                }
            }
        }

        auto now = std::chrono::steady_clock::now();

        // Deadman failsafe: zero axes & buttons if no packet arrives for 350ms
        if (g_ctrl.hasClient.load(std::memory_order_relaxed) &&
            std::chrono::duration_cast<std::chrono::milliseconds>(now - lastPacketRx).count() > 350) {
            zeroAllControls();
        }

        if (g_ctrl.hasClient.load(std::memory_order_relaxed) &&
            std::chrono::duration_cast<std::chrono::milliseconds>(now - lastTelemSend).count() >= 25) {
            lastTelemSend = now;

            TruckTelemetryPacket outPkt{};
            {
                std::lock_guard<std::mutex> lock(g_telem.mtx);
                outPkt = g_telem.pkt;
                // Exponential decay on suspension impulse and surface buzz between physics frames
                g_telem.suspRumblePeak *= 0.65f;
                g_telem.surfaceRumbleLevel *= 0.75f;
            }
            outPkt.echoTimestampMs = g_ctrl.lastClientTs.load(std::memory_order_relaxed);
            finalizeTelemetryPacket(&outPkt);

            if (activeTransport == ActiveTransport::TCP && tcpClientSock != INVALID_SOCKET) {
                int sent = send(tcpClientSock, reinterpret_cast<const char*>(&outPkt), sizeof(outPkt), 0);
                if (sent == SOCKET_ERROR && WSAGetLastError() != WSAEWOULDBLOCK) {
                    closesocket(tcpClientSock);
                    tcpClientSock = INVALID_SOCKET;
                    activeTransport = ActiveTransport::NONE;
                }
            } else if (activeTransport == ActiveTransport::UDP) {
                sockaddr_in dest{};
                {
                    std::lock_guard<std::mutex> lock(g_clientAddrMtx);
                    dest = g_clientAddr;
                }
                sendto(g_udpSocket, reinterpret_cast<const char*>(&outPkt), sizeof(outPkt), 0,
                       reinterpret_cast<const sockaddr*>(&dest), sizeof(dest));
            }
        }

        if (g_radioSyncRequested.exchange(false, std::memory_order_relaxed)) {
            radioSendCursor = 0;
            radioSending = true;
        }
        if (radioSending && g_ctrl.hasClient.load(std::memory_order_relaxed)) {
            std::lock_guard<std::mutex> rlock(g_radioMtx);
            const size_t total = g_radioStations.size();
            const size_t burstEnd = std::min(total, radioSendCursor + 12);
            for (size_t idx = radioSendCursor; idx < burstEnd; ++idx) {
                buildRadioRowPacket(static_cast<uint16_t>(idx), static_cast<uint16_t>(total), g_radioStations[idx], radioPktBuf);
                if (activeTransport == ActiveTransport::TCP && tcpClientSock != INVALID_SOCKET) {
                    int sent = send(tcpClientSock, reinterpret_cast<const char*>(radioPktBuf.data()), static_cast<int>(radioPktBuf.size()), 0);
                    if (sent == SOCKET_ERROR && WSAGetLastError() != WSAEWOULDBLOCK) break;
                } else if (activeTransport == ActiveTransport::UDP) {
                    sockaddr_in dest{};
                    {
                        std::lock_guard<std::mutex> lock(g_clientAddrMtx);
                        dest = g_clientAddr;
                    }
                    sendto(g_udpSocket, reinterpret_cast<const char*>(radioPktBuf.data()), static_cast<int>(radioPktBuf.size()), 0,
                           reinterpret_cast<const sockaddr*>(&dest), sizeof(dest));
                }
            }
            radioSendCursor = burstEnd;
            if (radioSendCursor >= total) radioSending = false;
        }
    }

    if (tcpClientSock != INVALID_SOCKET) closesocket(tcpClientSock);
    if (tcpListenSock != INVALID_SOCKET) closesocket(tcpListenSock);
    closesocket(g_udpSocket);
    g_udpSocket = INVALID_SOCKET;
    WSACleanup();
}

void beaconWorker() {
    const int msgLen = static_cast<int>(std::strlen(BEACON_MSG));
    std::vector<uint8_t> adapterBuf(16384);

    while (g_netRunning.load(std::memory_order_acquire)) {
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
                    if ((ipHost >> 24) == 127 || (ipHost >> 16) == 0xA9FE) continue;

                    // Bind directly to each adapter's local IP so Windows sends out Wi-Fi AND USB Tethering
                    SOCKET ifSock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
                    if (ifSock == INVALID_SOCKET) continue;

                    BOOL bcast = TRUE;
                    setsockopt(ifSock, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&bcast), sizeof(bcast));

                    sockaddr_in localBind{};
                    localBind.sin_family = AF_INET;
                    localBind.sin_addr = sa->sin_addr;
                    localBind.sin_port = 0;

                    if (bind(ifSock, reinterpret_cast<sockaddr*>(&localBind), sizeof(localBind)) == 0) {
                        ULONG maskNet = 0;
                        ConvertLengthToIpv4Mask(u->OnLinkPrefixLength, &maskNet);
                        uint32_t maskHost = ntohl(maskNet);

                        sockaddr_in subAddr{};
                        subAddr.sin_family = AF_INET;
                        subAddr.sin_port = htons(PORT_BEACON);
                        subAddr.sin_addr.s_addr = htonl(ipHost | (~maskHost));
                        sendto(ifSock, BEACON_MSG, msgLen, 0, reinterpret_cast<sockaddr*>(&subAddr), sizeof(subAddr));

                        sockaddr_in globAddr{};
                        globAddr.sin_family = AF_INET;
                        globAddr.sin_port = htons(PORT_BEACON);
                        globAddr.sin_addr.s_addr = INADDR_BROADCAST;
                        sendto(ifSock, BEACON_MSG, msgLen, 0, reinterpret_cast<sockaddr*>(&globAddr), sizeof(globAddr));
                    }
                    closesocket(ifSock);
                }
            }
        }

        for (int i = 0; i < 20 && g_netRunning.load(std::memory_order_acquire); ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
    }
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
    if (!info || !info->id) return;

    std::lock_guard<std::mutex> lock(g_telem.mtx);
    if (std::strcmp(info->id, SCS_TELEMETRY_CONFIG_controls) == 0) {
        for (const scs_named_value_t* cur = info->attributes; cur && cur->name; ++cur) {
            if (std::strcmp(cur->name, SCS_TELEMETRY_CONFIG_ATTRIBUTE_shifter_type) == 0 &&
                cur->value.type == SCS_VALUE_TYPE_string && cur->value.value_string.value) {
                const char* st = cur->value.value_string.value;
                const bool isAuto = (std::strcmp(st, SCS_SHIFTER_TYPE_arcade) == 0 ||
                                     std::strcmp(st, SCS_SHIFTER_TYPE_automatic) == 0);
                if (isAuto) g_telem.pkt.safetyFlags |= SAFETY_AUTO_TRANSMISSION;
                else        g_telem.pkt.safetyFlags &= ~SAFETY_AUTO_TRANSMISSION;
            }
        }
        return;
    }

    if (std::strcmp(info->id, SCS_TELEMETRY_CONFIG_truck) != 0) return;

    for (const scs_named_value_t* cur = info->attributes; cur && cur->name; ++cur) {
        if (std::strcmp(cur->name, SCS_TELEMETRY_CONFIG_ATTRIBUTE_rpm_limit) == 0 && cur->value.type == SCS_VALUE_TYPE_float) {
            g_telem.pkt.maxRpm = cur->value.value_float.value;
        } else if (std::strcmp(cur->name, SCS_TELEMETRY_CONFIG_ATTRIBUTE_fuel_capacity) == 0 && cur->value.type == SCS_VALUE_TYPE_float) {
            g_telem.fuelCapacityLiters = std::max(1.0f, cur->value.value_float.value);
        } else if (std::strcmp(cur->name, SCS_TELEMETRY_CONFIG_ATTRIBUTE_adblue_capacity) == 0 && cur->value.type == SCS_VALUE_TYPE_float) {
            g_telem.adblueCapacityLiters = std::max(1.0f, cur->value.value_float.value);
        } else if (std::strcmp(cur->name, SCS_TELEMETRY_CONFIG_ATTRIBUTE_forward_gear_count) == 0 && cur->value.type == SCS_VALUE_TYPE_u32) {
            g_telem.pkt.forwardGears = static_cast<uint8_t>(cur->value.value_u32.value);
        } else if (std::strcmp(cur->name, SCS_TELEMETRY_CONFIG_ATTRIBUTE_wheel_count) == 0 && cur->value.type == SCS_VALUE_TYPE_u32) {
            g_telem.wheelCount = std::min<uint32_t>(cur->value.value_u32.value, static_cast<uint32_t>(MAX_MONITORED_WHEELS));
            for (size_t w = 0; w < MAX_MONITORED_WHEELS; ++w) {
                g_telem.hasPrevSusp[w] = false;
                g_telem.prevSuspDefl[w] = 0.0f;
            }
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

SCSAPI_VOID onTelemRetarder(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_u32) return;
    const uint8_t lvl = static_cast<uint8_t>(val->value_u32.value);
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    g_telem.pkt.retarderLevel = lvl;
    if (lvl > 0) g_telem.pkt.dashboardFlags |= DASH_RETARDER_ACTIVE;
    else         g_telem.pkt.dashboardFlags &= ~DASH_RETARDER_ACTIVE;
}

SCSAPI_VOID onTelemTempC(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t ctx) {
    if (!val || val->type != SCS_VALUE_TYPE_float || !ctx) return;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    *static_cast<int16_t*>(ctx) = static_cast<int16_t>(std::lround(val->value_float.value));
}

SCSAPI_VOID onTelemVolts10(const scs_string_t, const scs_u32_t, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_float) return;
    const float v10 = std::clamp(val->value_float.value * 10.0f, 0.0f, 255.0f);
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    g_telem.pkt.batteryVolts10 = static_cast<uint8_t>(std::lround(v10));
}

SCSAPI_VOID onTelemWheelSusp(const scs_string_t, const scs_u32_t index, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_float || index >= MAX_MONITORED_WHEELS) return;
    const float defl = val->value_float.value;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    if (g_telem.hasPrevSusp[index]) {
        const float delta = std::abs(defl - g_telem.prevSuspDefl[index]);
        // Rapid suspension deflection (>8mm in one tick) signals curbs, bumps, or rumble strips
        if (delta > 0.008f && g_telem.pkt.speedKmh > 2.0f) {
            const float impulse = std::min(255.0f, (delta - 0.008f) * 3200.0f);
            g_telem.suspRumblePeak = std::max(g_telem.suspRumblePeak, impulse);
        }
    }
    g_telem.prevSuspDefl[index] = defl;
    g_telem.hasPrevSusp[index] = true;
}

SCSAPI_VOID onTelemWheelOnGround(const scs_string_t, const scs_u32_t index, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_bool || index >= MAX_MONITORED_WHEELS) return;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    g_telem.wheelOnGround[index] = (val->value_bool.value != 0);
}

SCSAPI_VOID onTelemWheelSubstance(const scs_string_t, const scs_u32_t index, const scs_value_t* const val, const scs_context_t) {
    if (!val || val->type != SCS_VALUE_TYPE_u32 || index >= MAX_MONITORED_WHEELS) return;
    const uint32_t sub = val->value_u32.value;
    std::lock_guard<std::mutex> lock(g_telem.mtx);
    const uint32_t prev = g_telem.wheelSubstance[index];
    g_telem.wheelSubstance[index] = sub;
    if (g_telem.pkt.speedKmh > 5.0f && g_telem.wheelOnGround[index]) {
        // Substance transition (e.g. asphalt <-> rumble strip / gravel) yields tactile kick
        if (sub != prev) {
            g_telem.surfaceRumbleLevel = std::max(g_telem.surfaceRumbleLevel, 110.0f);
        } else if (sub > 0) {
            // Non-asphalt continuous road texture scaled by speed
            const float speedNorm = std::min(1.0f, g_telem.pkt.speedKmh / 60.0f);
            g_telem.surfaceRumbleLevel = std::max(g_telem.surfaceRumbleLevel, 65.0f * speedNorm);
        }
    }
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
    const float gLarge  = (gPeak > 28.0f) ? std::min(255.0f, (gPeak - 28.0f) * 4.5f) : 0.0f;

    const float combinedSmall = std::max(rpmNorm * 55.0f, g_telem.surfaceRumbleLevel);
    const float combinedLarge = std::max(gLarge, g_telem.suspRumblePeak);
    g_telem.pkt.rumbleSmall = static_cast<uint8_t>(std::clamp(combinedSmall, 0.0f, 255.0f));
    g_telem.pkt.rumbleLarge = static_cast<uint8_t>(std::clamp(combinedLarge, 0.0f, 255.0f));
}

} // namespace

SCSAPI_RESULT scs_input_init(const scs_u32_t version, const scs_input_init_params_t* const params) {
    if (version != SCS_INPUT_VERSION_1_00 || !params) return SCS_RESULT_unsupported;

    const auto* v100 = reinterpret_cast<const scs_input_init_params_v100_t*>(params);
    if (!g_scsLog) g_scsLog = v100->common.log;

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
        logWarn("[ETS2Phone] register_device failed.");
        return SCS_RESULT_generic_error;
    }

    logInfo("[ETS2Phone] scs_input_init succeeded: ETS2 Phone Cockpit Controller registered.");
    ensureNetworkStarted();
    return SCS_RESULT_ok;
}

SCSAPI_VOID scs_input_shutdown(void) {
    ensureNetworkStopped();
}

SCSAPI_RESULT scs_telemetry_init(const scs_u32_t version, const scs_telemetry_init_params_t* const params) {
    if (version != SCS_TELEMETRY_VERSION_1_01 || !params) return SCS_RESULT_unsupported;

    const auto* v100 = reinterpret_cast<const scs_telemetry_init_params_v100_t*>(params);
    if (!g_scsLog) g_scsLog = v100->common.log;

    {
        std::lock_guard<std::mutex> lock(g_telem.mtx);
        g_telem.pkt.maxRpm = 2500.0f;
        g_telem.pkt.forwardGears = 12;
        g_telem.pkt.fuelPercent = 100;
        g_telem.pkt.adbluePercent = 100;
    }

    v100->register_for_event(SCS_TELEMETRY_EVENT_configuration, &onConfiguration, nullptr);

    auto reg = v100->register_for_channel;
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_speed, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemSpeedMps, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_engine_rpm, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemFloat, &g_telem.pkt.engineRpm);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_brake_air_pressure, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemFloat, &g_telem.pkt.airPressureBar);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_displayed_gear, SCS_U32_NIL, SCS_VALUE_TYPE_s32, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemS32, &g_telem.pkt.gear);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_cruise_control, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemCruiseMps, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_navigation_speed_limit, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemSpeedLimitMps, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_navigation_distance, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemNavDistance, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_navigation_time, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemNavTime, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_fuel, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemFuelLiters, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_adblue, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemAdblueLiters, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_local_linear_acceleration, SCS_U32_NIL, SCS_VALUE_TYPE_fvector, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemAccel, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_retarder_level, SCS_U32_NIL, SCS_VALUE_TYPE_u32, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemRetarder, nullptr);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_oil_temperature, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemTempC, &g_telem.pkt.oilTempC);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_water_temperature, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemTempC, &g_telem.pkt.waterTempC);
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_battery_voltage, SCS_U32_NIL, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemVolts10, nullptr);

    for (scs_u32_t w = 0; w < static_cast<scs_u32_t>(MAX_MONITORED_WHEELS); ++w) {
        reg(SCS_TELEMETRY_TRUCK_CHANNEL_wheel_susp_deflection, w, SCS_VALUE_TYPE_float, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemWheelSusp, nullptr);
        reg(SCS_TELEMETRY_TRUCK_CHANNEL_wheel_on_ground, w, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemWheelOnGround, nullptr);
        reg(SCS_TELEMETRY_TRUCK_CHANNEL_wheel_substance, w, SCS_VALUE_TYPE_u32, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemWheelSubstance, nullptr);
    }

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
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_light_parking, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolSafety, reinterpret_cast<scs_context_t>(SAFETY_PARK_LIGHTS));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_light_low_beam, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_LOW_BEAMS));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_light_high_beam, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_HIGH_BEAMS));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_light_beacon, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_BEACONS));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_wipers, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_WIPERS_ACTIVE));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_differential_lock, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_DIFF_LOCK));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_lift_axle, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_TRUCK_LIFT_AXLE));
    reg(SCS_TELEMETRY_TRUCK_CHANNEL_trailer_lift_axle, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolDash, reinterpret_cast<scs_context_t>(DASH_TRAILER_LIFT_AXLE));

    reg(SCS_TELEMETRY_TRAILER_CHANNEL_connected, SCS_U32_NIL, SCS_VALUE_TYPE_bool, SCS_TELEMETRY_CHANNEL_FLAG_none, &onTelemBoolSafety, reinterpret_cast<scs_context_t>(SAFETY_TRAILER_CONNECTED));

    logInfo("[ETS2Phone] scs_telemetry_init succeeded.");
    ensureNetworkStarted();
    return SCS_RESULT_ok;
}

SCSAPI_VOID scs_telemetry_shutdown(void) {
    ensureNetworkStopped();
}