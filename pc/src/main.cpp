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

    // DirectInput hardware scan code simulation for DirectX games (ETS2 / ATS)
    void triggerScanCode(WORD scanCode, bool extended = false, int holdMs = 200) {
        std::thread([scanCode, extended, holdMs]() {
            INPUT ip[2] = {};

            // 1. Key Down (wVk must strictly be 0 when KEYEVENTF_SCANCODE is specified for DirectX DirectInput)
            ip[0].type = INPUT_KEYBOARD;
            ip[0].ki.wVk = 0;
            ip[0].ki.wScan = scanCode;
            ip[0].ki.dwFlags = KEYEVENTF_SCANCODE | (extended ? KEYEVENTF_EXTENDEDKEY : 0);

            // 2. Key Up
            ip[1].type = INPUT_KEYBOARD;
            ip[1].ki.wVk = 0;
            ip[1].ki.wScan = scanCode;
            ip[1].ki.dwFlags = KEYEVENTF_SCANCODE | KEYEVENTF_KEYUP | (extended ? KEYEVENTF_EXTENDEDKEY : 0);

            // Dual dispatch: keybd_event with scan code + SendInput
            keybd_event(0, (BYTE)scanCode, (extended ? KEYEVENTF_EXTENDEDKEY : 0), 0);
            SendInput(1, &ip[0], sizeof(INPUT));

            std::this_thread::sleep_for(std::chrono::milliseconds(holdMs));

            keybd_event(0, (BYTE)scanCode, KEYEVENTF_KEYUP | (extended ? KEYEVENTF_EXTENDEDKEY : 0), 0);
            SendInput(1, &ip[1], sizeof(INPUT));
        }).detach();
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

        // Over-Engineered Slew-Rate Limiter:
        // Caps maximum instantaneous change to Δsteer ≤ 0.08 per 10ms frame (full lock in 125ms).
        // Prevents any rogue network packet burst, UDP drop, or sudden spike from jerking the virtual Xbox stick!
        static float s_smoothedSteering = 0.0f;
        static bool s_hasSteerInit = false;
        if (!s_hasSteerInit) {
            s_smoothedSteering = state.steering;
            s_hasSteerInit = true;
        } else {
            float delta = state.steering - s_smoothedSteering;
            constexpr float MAX_DELTA_PER_FRAME = 0.08f;
            s_smoothedSteering += std::clamp(delta, -MAX_DELTA_PER_FRAME, MAX_DELTA_PER_FRAME);
        }

        // Direct kernel-level update: ViGEm virtual Xbox 360 controller
        gamepad.update(s_smoothedSteering, state.throttle, state.brake, state.buttons, state.flags);

        // Handle ETS2 Hotkeys via SendInput & Gamepad combos:
        static uint16_t s_prevButtons = 0;
        static uint8_t s_prevFlags = 0;
        uint16_t newBtns = state.buttons & ~s_prevButtons;
        uint8_t newFlags = state.flags & ~s_prevFlags;
        s_prevButtons = state.buttons;
        s_prevFlags = state.flags;

        // Bit 0: HORN / OK -> DirectInput hardware scan code 0x23 ('H') + Joy R3 / Joy A
        if (newBtns & (1 << 0)) {
            triggerScanCode(0x23, false, 250); // 'H' key
            std::cout << "[HORN] Horn blast ('H' / Joy R3)" << std::endl;
        }

        // Bit 1: Engine Start/Stop -> DirectInput hardware scan code 0x12 ('E') + Joy D-Pad Right (Hold 1.2s)
        if (newBtns & (1 << 1)) {
            triggerScanCode(0x12, false, 800);
            std::cout << "[ENGINE] Start/Stop Engine ('E' / Joy D-Pad Right (Hold))" << std::endl;
        }

        // Bit 2: LIGHTS -> DirectInput hardware scan code 0x26 ('L') + Joy (X)
        if (newBtns & (1 << 2)) {
            triggerScanCode(0x26, false, 150); // 'L' key
            std::cout << "[LIGHTS] Headlights toggle ('L' / Joy X)" << std::endl;
        }

        // Bit 3: HAZARD -> DirectInput hardware scan code 0x21 ('F') + Joy Back
        if (newBtns & (1 << 3)) {
            triggerScanCode(0x21, false, 150); // 'F' key
            std::cout << "[HAZARD] Hazard Warning Flasher ('F' / Joy Back)" << std::endl;
        }

        // Bit 6: Parking Brake -> DirectInput hardware scan code 0x39 (Space) + Joy D-Pad Down (Hold 1.0s)
        if (newBtns & (1 << 6)) {
            triggerScanCode(0x39, false, 150);
            std::cout << "[PARK BRK] Parking Brake ('Space' / Joy D-Pad Down (Hold))" << std::endl;
        }

        // Bit 7: RETARDER / MOTOR BRAKE -> DirectInput hardware scan code 0x30 ('B') + Joy L3
        if (newBtns & (1 << 7)) {
            triggerScanCode(0x30, false, 150); // 'B' key
            std::cout << "[RETARDER] Motor Brake ('B' / Joy L3)" << std::endl;
        }

        // Bit 8: MAP / ADVISOR -> DirectInput hardware scan code 0x3F ('F5') + Joy Y
        if (newBtns & (1 << 8)) {
            triggerScanCode(0x3F, false, 150); // 'F5' Route Advisor zoom
            std::cout << "[MAP / ADVISOR] Route Advisor Mode ('F5' / Joy Y)" << std::endl;
        }

        // Bit 9: PAUSE / CRUISE -> DirectInput hardware scan code 0x2E ('C') + Joy B (Hold 600ms)
        if (newBtns & (1 << 9)) {
            triggerScanCode(0x2E, false, 200); // 'C' key
            std::cout << "[CRUISE] Cruise Control toggle ('C' / Joy B)" << std::endl;
        }

        // Transmission triggers: Dual dispatch (DirectInput 'Shift'/'Ctrl'/'/' + Joy RB/LB/Y)
        if (newBtns & (1 << 10)) {
            triggerScanCode(0x2A, false, 200); // Left Shift (scancode 0x2A -> "Shift")
            std::cout << "[TRANSMISSION] Shift Up ('Shift' / Joy RB)" << std::endl;
        }
        if (newBtns & (1 << 11)) {
            triggerScanCode(0x1D, false, 200); // Left Ctrl (scancode 0x1D -> "Ctrl")
            std::cout << "[TRANSMISSION] Shift Down ('Ctrl' / Joy LB)" << std::endl;
        }
        if (newFlags & (1 << 1)) {
            triggerScanCode(0x35, false, 200); // '/' key
            std::cout << "[TRANSMISSION] Shift to Neutral ('/' / Joy Y)" << std::endl;
        }

        // Handle ETS2 in-game Radio hotkeys via DirectInput hardware scan codes:
        // Bit 14: Audio Player Toggle ('R' -> scan code 0x13)
        // Bit 15: Audio Player Next Favorite (Page Down -> scan code 0x51 extended)
        // Flags Bit 0: Audio Player Previous Favorite (Page Up -> scan code 0x49 extended)
        if (newBtns & (1 << 14)) {
            triggerScanCode(0x13, false, 150);
            std::cout << "[RADIO] Audio Player toggled ('R')" << std::endl;
        }
        if (newBtns & (1 << 15)) {
            triggerScanCode(0x51, true, 150);
            std::cout << "[RADIO] Next Favorite station ('Page Down')" << std::endl;
        }
        if (newFlags & (1 << 0)) {
            triggerScanCode(0x49, true, 150);
            std::cout << "[RADIO] Previous Favorite station ('Page Up')" << std::endl;
        }

        // Indicator triggers: Send DirectInput scan codes '[' (0x1A) and ']' (0x1B) + Gamepad D-Pad Left/Right
        if (newBtns & (1 << 4)) {
            triggerScanCode(0x1A, false, 150); // '[' key
            std::cout << "[INDICATOR] Left Turn Indicator ('[' / Joy D-Pad Left)" << std::endl;
        }
        if (newBtns & (1 << 5)) {
            triggerScanCode(0x1B, false, 150); // ']' key
            std::cout << "[INDICATOR] Right Turn Indicator (']' / Joy D-Pad Right)" << std::endl;
        }

        // Bit 12: Wipers -> DirectInput scan code 0x19 ('P')
        if (newBtns & (1 << 12)) {
            triggerScanCode(0x19, false, 150);
            std::cout << "[WIPERS] Windshield Wipers ('P')" << std::endl;
        }

        // Bit 13: Camera -> DirectInput scan code 0x02 ('1' for interior / cycle views)
        if (newBtns & (1 << 13)) {
            triggerScanCode(0x02, false, 150);
            std::cout << "[CAMERA] Interior / Camera cycle ('1')" << std::endl;
        }

        // Auxiliary Heavy Truck Switchboard Controls (Flags Bits 2..7):
        // Flags Bit 2: Differential Lock ('V' -> scan code 0x2F)
        if (newFlags & (1 << 2)) {
            triggerScanCode(0x2F, false, 150);
            std::cout << "[DIFF LOCK] Differential Lock toggled ('V')" << std::endl;
        }

        // Flags Bit 3: Lift / Drop Axle ('U' -> scan code 0x16)
        if (newFlags & (1 << 3)) {
            triggerScanCode(0x16, false, 150);
            std::cout << "[LIFT AXLE] Lift Axle toggled ('U')" << std::endl;
        }

        // Flags Bit 4: Trailer Hook / Decouple ('T' -> scan code 0x14)
        if (newFlags & (1 << 4)) {
            triggerScanCode(0x14, false, 200);
            std::cout << "[TRAILER] Trailer Hook / Decouple ('T')" << std::endl;
        }

        // Flags Bit 5: High Beams ('K' -> scan code 0x25)
        if (newFlags & (1 << 5)) {
            triggerScanCode(0x25, false, 150);
            std::cout << "[HIGH BEAMS] High Beams toggled ('K')" << std::endl;
        }

        // Flags Bit 6: Beacon Strobe Lights ('O' -> scan code 0x18)
        if (newFlags & (1 << 6)) {
            triggerScanCode(0x18, false, 150);
            std::cout << "[BEACONS] Roof Strobe Beacons ('O')" << std::endl;
        }

        // Flags Bit 7: Heavy Air Horn ('N' -> scan code 0x31)
        if (newFlags & (1 << 7)) {
            triggerScanCode(0x31, false, 350);
            std::cout << "[AIR HORN] Roof Air Horn blast ('N')" << std::endl;
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
