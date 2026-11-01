#include <iostream>
#include <iomanip>
#include <thread>
#include <atomic>
#include <chrono>
#include <mutex>
#include <cstring>
#include <algorithm>

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <timeapi.h>
#include <iphlpapi.h>

#include "config.h"
#include "protocol.h"
#include "filter.h"
#include "controller.h"
#include "telemetry_reader.h"

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "winmm.lib")
#pragma comment(lib, "iphlpapi.lib")

std::atomic<int> g_currentTruckGear{0};

namespace {
    std::atomic<bool> g_running{true};

    // Client tracking for sending force feedback & telemetry back to phone
    std::mutex g_clientMutex;
    sockaddr_in g_clientAddr{};
    bool g_hasClient = false;
    SOCKET g_rumbleSocket = INVALID_SOCKET;

    std::mutex g_stateMutex;
    ControllerState g_latestState{};
    bool g_hasState = false;

    // Telemetry streaming worker thread (20Hz / 50ms)
    void telemetryWorker(TelemetryReader* reader) {
        uint8_t telemBuf[TELEMETRY_PACKET_SIZE];
        while (g_running) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));

            sockaddr_in targetAddr{};
            bool send = false;
            ControllerState stateCopy{};

            {
                std::lock_guard<std::mutex> lock(g_clientMutex);
                if (g_hasClient && g_rumbleSocket != INVALID_SOCKET) {
                    targetAddr = g_clientAddr;
                    targetAddr.sin_port = htons(PORT_RUMBLE);
                    send = true;
                }
            }

            {
                std::lock_guard<std::mutex> lock(g_stateMutex);
                stateCopy = g_latestState;
            }

            if (send && reader) {
                TruckTelemetryPacket telem{};
                reader->pollTelemetry(stateCopy, &telem);
                g_currentTruckGear.store(telem.gear);
                encodeTelemetryPacket(telem, telemBuf);
                sendto(g_rumbleSocket, reinterpret_cast<char*>(telemBuf), sizeof(telemBuf), 0,
                       reinterpret_cast<sockaddr*>(&targetAddr), sizeof(targetAddr));
            }
        }
    }

    BOOL WINAPI consoleCtrlHandler(DWORD signal) {
        if (signal == CTRL_C_EVENT || signal == CTRL_CLOSE_EVENT) {
            std::cout << "\n[INFO] Exiting cleanly..." << std::endl;
            g_running = false;
            return TRUE;
        }
        return FALSE;
    }

    // Pure DirectInput Hardware ScanCode Dispatcher for ETS2
    void injectKeyPulse(BYTE vKey, WORD scanCode = 0, bool extended = false, int holdMs = 120) {
        WORD finalScan = scanCode ? scanCode : (WORD)MapVirtualKeyA(vKey, MAPVK_VK_TO_VSC);
        std::thread([finalScan, extended, holdMs]() {
            INPUT ipDown = {};
            ipDown.type = INPUT_KEYBOARD;
            ipDown.ki.wVk = 0; // Must be 0 when using KEYEVENTF_SCANCODE
            ipDown.ki.wScan = finalScan;
            ipDown.ki.dwFlags = KEYEVENTF_SCANCODE | (extended ? KEYEVENTF_EXTENDEDKEY : 0);
            SendInput(1, &ipDown, sizeof(INPUT));

            std::this_thread::sleep_for(std::chrono::milliseconds(holdMs));

            INPUT ipUp = {};
            ipUp.type = INPUT_KEYBOARD;
            ipUp.ki.wVk = 0;
            ipUp.ki.wScan = finalScan;
            ipUp.ki.dwFlags = KEYEVENTF_SCANCODE | KEYEVENTF_KEYUP | (extended ? KEYEVENTF_EXTENDEDKEY : 0);
            SendInput(1, &ipUp, sizeof(INPUT));
        }).detach();
    }

    // Continuous touch-hold state dispatcher for Horn ('H') and Roof Air Horn ('N')
    void setKeyHold(BYTE vKey, WORD scanCode, bool extended, bool isPressed) {
        WORD finalScan = scanCode ? scanCode : (WORD)MapVirtualKeyA(vKey, MAPVK_VK_TO_VSC);
        INPUT ip = {};
        ip.type = INPUT_KEYBOARD;
        ip.ki.wVk = 0;
        ip.ki.wScan = finalScan;
        ip.ki.dwFlags = KEYEVENTF_SCANCODE | (isPressed ? 0 : KEYEVENTF_KEYUP) | (extended ? KEYEVENTF_EXTENDEDKEY : 0);
        SendInput(1, &ip, sizeof(INPUT));
    }

    // Background thread that broadcasts an auto-discovery beacon every 1.5 seconds
    void beaconBroadcastWorker() {
        SOCKET beaconSock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (beaconSock == INVALID_SOCKET) return;

        BOOL broadcastEnable = TRUE;
        setsockopt(beaconSock, SOL_SOCKET, SO_BROADCAST, (char*)&broadcastEnable, sizeof(broadcastEnable));

        const size_t msgLen = strlen(BEACON_MESSAGE);

        while (g_running) {
            // 1. Global broadcast (255.255.255.255)
            sockaddr_in bcastAddr{};
            bcastAddr.sin_family = AF_INET;
            bcastAddr.sin_port = htons(PORT_BEACON);
            bcastAddr.sin_addr.s_addr = INADDR_BROADCAST;
            sendto(beaconSock, BEACON_MESSAGE, static_cast<int>(msgLen), 0,
                   reinterpret_cast<sockaddr*>(&bcastAddr), sizeof(bcastAddr));

            // 2. Direct subnet broadcast on all active network adapters
            ULONG bufSize = 15000;
            PIP_ADAPTER_ADDRESSES addrs = (PIP_ADAPTER_ADDRESSES)malloc(bufSize);
            if (addrs && GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST, NULL, addrs, &bufSize) == NO_ERROR) {
                for (auto a = addrs; a; a = a->Next) {
                    if (a->OperStatus != IfOperStatusUp) continue;
                    for (auto u = a->FirstUnicastAddress; u; u = u->Next) {
                        sockaddr_in* sa = (sockaddr_in*)u->Address.lpSockaddr;
                        uint32_t ip = ntohl(sa->sin_addr.s_addr);
                        if ((ip >> 24) == 127) continue;

                        uint32_t mask = 0xFFFFFF00; // /24 broadcast
                        uint32_t subnetBcast = ip | (~mask);
                        sockaddr_in subAddr{};
                        subAddr.sin_family = AF_INET;
                        subAddr.sin_port = htons(PORT_BEACON);
                        subAddr.sin_addr.s_addr = htonl(subnetBcast);
                        sendto(beaconSock, BEACON_MESSAGE, static_cast<int>(msgLen), 0,
                               reinterpret_cast<sockaddr*>(&subAddr), sizeof(subAddr));
                    }
                }
                free(addrs);
            }

            std::this_thread::sleep_for(std::chrono::milliseconds(BEACON_INTERVAL_MS));
        }

        closesocket(beaconSock);
    }
}

int main() {
    SetConsoleCtrlHandler(consoleCtrlHandler, TRUE);

    // Boost timer resolution to 1ms
    timeBeginPeriod(1);

    // Elevate thread priority for minimal input jitter
    SetPriorityClass(GetCurrentProcess(), HIGH_PRIORITY_CLASS);
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL);

    std::cout << "=========================================================" << std::endl;
    std::cout << "   ETS2 Phone Controller - Native C++ Server             " << std::endl;
    std::cout << "=========================================================" << std::endl;

    // Display local IP addresses for user reference
    printLocalIPs();

    // Initialize ViGEm virtual Xbox 360 controller
    VirtualGamepad gamepad;
    if (!gamepad.initialize()) {
        std::cerr << "[FATAL] Failed to initialize virtual gamepad. Is ViGEmBus installed?" << std::endl;
        timeEndPeriod(1);
        return 1;
    }

    // Initialize WinSock
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        std::cerr << "[FATAL] WSAStartup failed." << std::endl;
        gamepad.shutdown();
        timeEndPeriod(1);
        return 1;
    }

    // Create UDP listening socket for controller inputs (Port 5005)
    SOCKET dataSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (dataSocket == INVALID_SOCKET) {
        std::cerr << "[FATAL] Could not create UDP data socket." << std::endl;
        WSACleanup();
        gamepad.shutdown();
        timeEndPeriod(1);
        return 1;
    }

    // Enlarge receive buffer to prevent dropping bursts
    int rcvBufSize = 131072; // 128 KB
    setsockopt(dataSocket, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<char*>(&rcvBufSize), sizeof(rcvBufSize));

    // Non-blocking socket mode with brief poll
    DWORD timeoutMs = 200;
    setsockopt(dataSocket, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<char*>(&timeoutMs), sizeof(timeoutMs));

    sockaddr_in serverAddr{};
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(PORT_DATA);
    serverAddr.sin_addr.s_addr = INADDR_ANY; // Listen on all network interfaces (USB + Wi-Fi)

    if (bind(dataSocket, reinterpret_cast<sockaddr*>(&serverAddr), sizeof(serverAddr)) == SOCKET_ERROR) {
        std::cerr << "[FATAL] Failed to bind UDP port " << PORT_DATA << ". Is another instance running?" << std::endl;
        closesocket(dataSocket);
        WSACleanup();
        gamepad.shutdown();
        timeEndPeriod(1);
        return 1;
    }

    // Create dedicated UDP socket for sending rumble back to phone
    g_rumbleSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);

    // Register rumble callback: when ETS2 sends force feedback, forward it to the phone
    gamepad.setRumbleCallback([](uint8_t largeMotor, uint8_t smallMotor) {
        std::lock_guard<std::mutex> lock(g_clientMutex);
        if (g_hasClient && g_rumbleSocket != INVALID_SOCKET) {
            RumbleState rumble{largeMotor, smallMotor};
            uint8_t packet[RUMBLE_PACKET_SIZE];
            encodeRumblePacket(rumble, packet);

            sockaddr_in targetAddr = g_clientAddr;
            targetAddr.sin_port = htons(PORT_RUMBLE);

            sendto(g_rumbleSocket, reinterpret_cast<char*>(packet), sizeof(packet), 0,
                   reinterpret_cast<sockaddr*>(&targetAddr), sizeof(targetAddr));
        }
    });

    // Start background beacon broadcaster
    std::thread beaconThread(beaconBroadcastWorker);

    // Initialize telemetry reader and 20Hz broadcast thread
    TelemetryReader telemetryReader;
    telemetryReader.initialize();
    std::thread telemetryThread(telemetryWorker, &telemetryReader);

    std::cout << "[READY] Listening on UDP Port " << PORT_DATA << " (USB + Wi-Fi)" << std::endl;
    std::cout << "[BEACON] Auto-discovery beacon active on Port " << PORT_BEACON << std::endl;
    std::cout << "[INFO] Waiting for phone connection..." << std::endl;
    std::cout << "---------------------------------------------------------" << std::endl;

    // High-resolution performance timer
    LARGE_INTEGER perfFreq, lastStatsTime, now;
    QueryPerformanceFrequency(&perfFreq);
    QueryPerformanceCounter(&lastStatsTime);

    uint8_t buffer[64];
    sockaddr_in fromAddr{};
    int fromLen = sizeof(fromAddr);
    uint16_t lastSeq = 0;
    bool hasLastSeq = false;
    uint32_t packetCount = 0;
    bool clientConnectedMessage = false;

    // Main ultra-low-latency input loop
    while (g_running) {
        int bytesReceived = recvfrom(dataSocket, reinterpret_cast<char*>(buffer), sizeof(buffer), 0,
                                     reinterpret_cast<sockaddr*>(&fromAddr), &fromLen);

        if (bytesReceived <= 0) {
            continue; // Timeout or no data, loop again
        }

        ControllerState state;
        if (!decodeControllerPacket(buffer, bytesReceived, &state)) {
            continue; // Ignore corrupted packet
        }

        // Drop out-of-order or duplicate stale packets
        if (hasLastSeq) {
            int16_t diff = static_cast<int16_t>(state.sequenceId - lastSeq);
            if (diff <= 0 && diff > -30000) {
                continue;
            }
        }
        lastSeq = state.sequenceId;
        hasLastSeq = true;
        packetCount++;

        // Track connected client for reverse rumble & telemetry packets
        {
            std::lock_guard<std::mutex> lock(g_clientMutex);
            g_clientAddr = fromAddr;
            g_hasClient = true;
        }

        // Track latest input state for telemetry worker
        {
            std::lock_guard<std::mutex> lock(g_stateMutex);
            g_latestState = state;
            g_hasState = true;
        }

        if (!clientConnectedMessage) {
            char clientIp[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &(fromAddr.sin_addr), clientIp, sizeof(clientIp));
            std::cout << "\n[CONNECTED] Phone connected from IP: " << clientIp << std::endl;
            std::cout << "            Driving inputs streaming in real-time!" << std::endl;
            clientConnectedMessage = true;
        }

        // Improved Steering Pipeline:
        // 1. Subtle non-linear curve (15% cubic blend) for fine highway center precision
        //    without sacrificing 1:1 linear feel across the rest of the rotation range.
        float rawSteer = std::clamp(state.steering, -1.0f, 1.0f);
        constexpr float NON_LINEARITY = 0.15f; // 0.0 = pure linear, 0.3 = softer center
        float shapedSteer = (1.0f - NON_LINEARITY) * rawSteer + NON_LINEARITY * (rawSteer * rawSteer * rawSteer);

        // 2. Speed-adaptive smoothing: smooths micro-jitter near center,
        //    but opens up instantly during fast counter-steering or tight maneuvers.
        static float s_smoothedSteering = 0.0f;
        static bool s_hasSteerInit = false;
        if (!s_hasSteerInit) {
            s_smoothedSteering = shapedSteer;
            s_hasSteerInit = true;
        } else {
            float delta = shapedSteer - s_smoothedSteering;
            float absDelta = std::abs(delta);
            // Dynamic alpha: 0.45 for tiny micro-movements, scaling up to 0.90 for fast turns
            float alpha = std::clamp(0.45f + absDelta * 3.0f, 0.45f, 0.90f);
            s_smoothedSteering += delta * alpha;
        }
        // Update virtual Xbox 360 controller with steering, pedals, and right-stick camera look
        gamepad.update(s_smoothedSteering, state.throttle, state.brake, state.buttons, state.flags, state.lookX);

        // Auto-center interior camera ('1') when releasing finger after looking left/right
        static bool s_wasLooking = false;
        bool isLooking = std::abs(state.lookX) > 0.08f;
        if (s_wasLooking && !isLooking) {
            injectKeyPulse('1', 0x02, false, 80);
            std::cout << "[CAMERA] Auto-centered interior view ('1')" << std::endl;
        }
        s_wasLooking = isLooking;

        // Handle ETS2 Hotkeys via Dual-Dispatch SendInput & DirectInput:
        static uint16_t s_prevButtons = 0;
        static uint8_t s_prevFlags = 0;
        uint16_t newBtns = state.buttons & ~s_prevButtons;
        uint8_t newFlags = state.flags & ~s_prevFlags;
        s_prevButtons = state.buttons;
        s_prevFlags = state.flags;

        // 1. Bit 0: Continuous HORN tracking ('H' key, wVk = 'H' 0x48, scan code 0x23)
        // In ETS2, horn is an active hold control: hold to honk continuously, release to stop.
        bool hornPressed = (state.buttons & (1 << 0)) != 0;
        static bool s_hornActive = false;
        if (hornPressed != s_hornActive) {
            setKeyHold('H', 0x23, false, hornPressed);
            s_hornActive = hornPressed;
            if (hornPressed) {
                std::cout << "[HORN] Continuous Horn ACTIVE (Hold 'H' / Joy R3)" << std::endl;
            } else {
                std::cout << "[HORN] Horn RELEASED" << std::endl;
            }
        }

       // 2. Flags Bit 2: Continuous Roof Air Horn ('N' key, scan code 0x31)
        bool airHornPressed = (state.flags & (1 << 2)) != 0;
        static bool s_airHornActive = false;
        if (airHornPressed != s_airHornActive) {
            setKeyHold('N', 0x31, false, airHornPressed);
            s_airHornActive = airHornPressed;
            if (airHornPressed) {
                std::cout << "[AIR HORN] Roof Air Horn ACTIVE (Hold 'N')" << std::endl;
            } else {
                std::cout << "[AIR HORN] Roof Air Horn RELEASED" << std::endl;
            }
        }

        // 3. Bit 1: Engine Start/Stop -> 'E' (Hold 800ms) + Joy D-Pad Right
        if (newBtns & (1 << 1)) {
            injectKeyPulse('E', 0x12, false, 800);
            std::cout << "[ENGINE] Start/Stop Engine ('E' / Joy D-Pad Right)" << std::endl;
        }

        // 4. Bit 2: LIGHTS -> 'L' + Joy (X)
        if (newBtns & (1 << 2)) {
            injectKeyPulse('L', 0x26, false, 150);
            std::cout << "[LIGHTS] Headlights toggle ('L' / Joy X)" << std::endl;
        }

        // 5. Bit 3: HAZARD -> 'F' + Joy Back
        if (newBtns & (1 << 3)) {
            injectKeyPulse('F', 0x21, false, 150);
            std::cout << "[HAZARD] Hazard Warning Flasher ('F' / Joy Back)" << std::endl;
        }

        // 6. Bit 4: LEFT TURN INDICATOR -> '[' + Joy D-Pad Left
        if (newBtns & (1 << 4)) {
            injectKeyPulse(VK_OEM_4, 0x1A, false, 150);
            std::cout << "[INDICATOR] Left Turn Indicator ('[' / Joy D-Pad Left)" << std::endl;
        }

        // 7. Bit 5: RIGHT TURN INDICATOR -> ']' + Joy D-Pad Right
        if (newBtns & (1 << 5)) {
            injectKeyPulse(VK_OEM_6, 0x1B, false, 150);
            std::cout << "[INDICATOR] Right Turn Indicator (']' / Joy D-Pad Right)" << std::endl;
        }

        // 8. Bit 6: Parking Brake -> Space + Joy D-Pad Down
        if (newBtns & (1 << 6)) {
            injectKeyPulse(VK_SPACE, 0x39, false, 150);
            std::cout << "[PARK BRK] Parking Brake ('Space' / Joy D-Pad Down)" << std::endl;
        }

        // 9. Bit 7: RETARDER / MOTOR BRAKE -> 'B' + Joy L3
        if (newBtns & (1 << 7)) {
            injectKeyPulse('B', 0x30, false, 150);
            std::cout << "[RETARDER] Motor Brake ('B' / Joy L3)" << std::endl;
        }

        // 10. Bit 8: MAP / ADVISOR -> F5 + Joy Y
        if (newBtns & (1 << 8)) {
            injectKeyPulse(VK_F5, 0x3F, false, 150);
            std::cout << "[MAP / ADVISOR] Route Advisor Mode ('F5' / Joy Y)" << std::endl;
        }

        // 11. Bit 9: PAUSE / CRUISE -> 'C' + Joy B
        if (newBtns & (1 << 9)) {
            injectKeyPulse('C', 0x2E, false, 200);
            std::cout << "[CRUISE] Cruise Control toggle ('C' / Joy B)" << std::endl;
        }

        // 12. Transmission Triggers: Native 'Shift' / 'Ctrl' / 'N' + Joy RB / LB / Y
        if (newBtns & (1 << 10)) {
            injectKeyPulse(VK_LSHIFT, 0x2A, false, 150);
            std::cout << "[TRANSMISSION] Shift Up ('Shift' / Joy RB)" << std::endl;
        }
        if (newBtns & (1 << 11)) {
            injectKeyPulse(VK_LCONTROL, 0x1D, false, 150);
            std::cout << "[TRANSMISSION] Shift Down ('Ctrl' / Joy LB)" << std::endl;
        }
        // Flags Bit 1: Smart Neutral
        if (newFlags & (1 << 1)) {
            int curGear = g_currentTruckGear.load();
            if (curGear < 0) {
                injectKeyPulse(VK_LSHIFT, 0x2A, false, 120);
                std::cout << "[TRANSMISSION] Smart Neutral from Reverse ('Left Shift')" << std::endl;
            } else if (curGear > 0) {
                injectKeyPulse(VK_LCONTROL, 0x1D, false, 120);
                std::cout << "[TRANSMISSION] Smart Neutral from Forward ('Left Ctrl')" << std::endl;
            } else {
                std::cout << "[TRANSMISSION] Already in Neutral" << std::endl;
            }
        }

        // 13. In-Game Radio Hotkeys
        if (newBtns & (1 << 14)) {
            injectKeyPulse('R', 0x13, false, 150);
            std::cout << "[RADIO] Audio Player toggled ('R')" << std::endl;
        }
        if (newBtns & (1 << 15)) {
            injectKeyPulse(VK_NEXT, 0x51, true, 150);
            std::cout << "[RADIO] Next Favorite station ('Page Down')" << std::endl;
        }
        // Flags Bit 0: Audio Player Previous Favorite (Page Up)
        if (newFlags & (1 << 0)) {
            injectKeyPulse(VK_PRIOR, 0x49, true, 150);
            std::cout << "[RADIO] Previous Favorite station ('Page Up')" << std::endl;
        }

        // 14. Bit 12: Wipers -> 'P' + Joy D-Pad Down
        if (newBtns & (1 << 12)) {
            injectKeyPulse('P', 0x19, false, 150);
            std::cout << "[WIPERS] Windshield Wipers ('P')" << std::endl;
        }

        // 15. Bit 13: Camera -> '1' (Interior / Cycle Views) + Joy D-Pad Up
        if (newBtns & (1 << 13)) {
            injectKeyPulse('1', 0x02, false, 150);
            std::cout << "[CAMERA] Interior / Camera cycle ('1')" << std::endl;
        }

        // 16. Extended Command Switch (Flags bits 3..7)
        uint8_t extCmdId = (state.flags >> 3) & 0x1F;
        static uint8_t s_prevExtCmdId = 0;
        uint8_t newExtCmd = (extCmdId != s_prevExtCmdId) ? extCmdId : 0;
        s_prevExtCmdId = extCmdId;

        switch (newExtCmd) {
            case 1: // Bit 18: Diff Lock ('V' scan 0x2F - Factory Default)
                injectKeyPulse('V', 0x2F, false, 120);
                std::cout << "[DIFF LOCK] Toggled ('V')" << std::endl;
                break;
            case 2: // Bit 19: Lift Axle ('U' scan 0x16 - Factory Default)
                injectKeyPulse('U', 0x16, false, 120);
                std::cout << "[LIFT AXLE] Toggled ('U')" << std::endl;
                break;
            case 3: // Bit 20: Trailer Hook ('T' scan 0x14 - Factory Default)
                injectKeyPulse('T', 0x14, false, 180);
                std::cout << "[TRAILER] Hook / Decouple ('T')" << std::endl;
                break;
            case 4: // Bit 21: High Beams ('K' scan 0x25 - Factory Default)
                injectKeyPulse('K', 0x25, false, 120);
                std::cout << "[HIGH BEAMS] Toggled ('K')" << std::endl;
                break;
            case 5: // Bit 22: Beacons ('O' scan 0x18 - Factory Default)
                injectKeyPulse('O', 0x18, false, 120);
                std::cout << "[BEACONS] Roof Strobe ('O')" << std::endl;
                break;
            case 7: // Bit 24: Retarder Increase (';' scan 0x27 - Factory Default)
                injectKeyPulse(VK_OEM_1, 0x27, false, 100);
                std::cout << "[RETARDER +] Step Up (';')" << std::endl;
                break;
            case 8: // Bit 25: Retarder Decrease (''' scan 0x28 - Factory Default)
                injectKeyPulse(VK_OEM_7, 0x28, false, 100);
                std::cout << "[RETARDER -] Step Down (''')" << std::endl;
                break;
            case 9: // Bit 26: Full World Map ('M' scan 0x32 - Factory Default)
                injectKeyPulse('M', 0x32, false, 120);
                std::cout << "[WORLD MAP] Toggle Full Map ('M')" << std::endl;
                break;
            case 10: // Bit 27: Route Advisor Next Page ('F6' scan 0x40 - Factory Default)
                injectKeyPulse(VK_F6, 0x40, false, 120);
                std::cout << "[ADVISOR] Job Info Page ('F6')" << std::endl;
                break;
            case 11: // Bit 28: Route Advisor Damage/Tow ('F7' scan 0x41 - Factory Default)
                injectKeyPulse(VK_F7, 0x41, false, 120);
                std::cout << "[ADVISOR] Damage & Tow Service ('F7')" << std::endl;
                break;
            case 12: // Bit 29: Dashboard Info Computer ('I' scan 0x17 - Factory Default)
                injectKeyPulse('I', 0x17, false, 120);
                std::cout << "[DASH DISPLAY] Cycle Truck Info ('I')" << std::endl;
                break;
            case 13: // Bit 30: Enter / Service Confirm (VK_RETURN scan 0x1C - Factory Default)
                injectKeyPulse(VK_RETURN, 0x1C, false, 150);
                std::cout << "[ENTER] Activate Gas / Toll / Ferry / Parking Prompt" << std::endl;
                break;
            case 14: // Bit 31: Cargo & Job Page ('F6' scan 0x40 - Factory Default)
                injectKeyPulse(VK_F6, 0x40, false, 120);
                std::cout << "[JOB INFO] Route Advisor Cargo ('F6')" << std::endl;
                break;
            case 15: // Bit 32: Damage & Emergency Tow ('F7' scan 0x41 - Factory Default)
                injectKeyPulse(VK_F7, 0x41, false, 120);
                std::cout << "[DAMAGE / TOW] Route Advisor Service ('F7')" << std::endl;
                break;
            case 16: // Bit 33: Lean Out Window Reverse Cam ('5' scan 0x06 - Factory Default)
                injectKeyPulse('5', 0x06, false, 120);
                std::cout << "[CAMERA] Lean Out Driver Window ('5')" << std::endl;
                break;
            case 17: // Bit 34: Exterior Chase Cam ('2' scan 0x03 - Factory Default)
                injectKeyPulse('2', 0x03, false, 120);
                std::cout << "[CAMERA] Exterior Chase View ('2')" << std::endl;
                break;
            default:
                break;
        }

        // Print stats every 5 seconds
        QueryPerformanceCounter(&now);
        double timeSinceStats = static_cast<double>(now.QuadPart - lastStatsTime.QuadPart) / perfFreq.QuadPart;
        if (timeSinceStats >= 5.0) {
            float pps = static_cast<float>(packetCount) / static_cast<float>(timeSinceStats);
            std::cout << "[STATS] " << std::fixed << std::setprecision(1) << pps 
                      << " pkts/sec | Steer: " << std::setprecision(2) << state.steering
                      << " | Gas: " << static_cast<int>(state.throttle * 100) << "%"
                      << " | Brake: " << static_cast<int>(state.brake * 100) << "%"
                      << std::endl;
            packetCount = 0;
            lastStatsTime = now;
        }
    }

    // Teardown
    if (telemetryThread.joinable()) {
        telemetryThread.join();
    }
    if (beaconThread.joinable()) {
        beaconThread.join();
    }

    if (g_rumbleSocket != INVALID_SOCKET) {
        closesocket(g_rumbleSocket);
    }
    closesocket(dataSocket);
    WSACleanup();
    gamepad.shutdown();
    timeEndPeriod(1);

    std::cout << "[SHUTDOWN] Server stopped." << std::endl;
    return 0;
}
