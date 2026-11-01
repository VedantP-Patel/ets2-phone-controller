#pragma once

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include <iostream>
#include <vector>
#include <string>

// Network ports
constexpr int PORT_DATA = 5005;    // Phone -> PC: Controller inputs
constexpr int PORT_BEACON = 5006;  // PC -> Broadcast: Auto-discovery beacon
constexpr int PORT_RUMBLE = 5007;  // PC -> Phone: Force feedback rumble

// Beacon configuration
constexpr int BEACON_INTERVAL_MS = 1500;
constexpr const char* BEACON_MESSAGE = "ETS2_CTRL_BEACON";

// 1-Euro Filter parameters (tuned for responsive, jitter-free steering)
constexpr float FILTER_MINCUTOFF = 1.2f;  // Lower = smoother when hands are still
constexpr float FILTER_BETA = 0.006f;      // Higher = faster response during rapid turns
constexpr float FILTER_DCUTOFF = 1.0f;

// Helper function to print all active local IPv4 addresses
inline void printLocalIPs() {
    std::cout << "=========================================================" << std::endl;
    std::cout << "  Active PC IP Addresses (for phone connection):" << std::endl;
    std::cout << "=========================================================" << std::endl;

    ULONG bufSize = 15000;
    PIP_ADAPTER_ADDRESSES addrs = (PIP_ADAPTER_ADDRESSES)malloc(bufSize);
    if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST, NULL, addrs, &bufSize) == ERROR_BUFFER_OVERFLOW) {
        free(addrs);
        addrs = (PIP_ADAPTER_ADDRESSES)malloc(bufSize);
    }

    if (addrs && GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST, NULL, addrs, &bufSize) == NO_ERROR) {
        for (auto a = addrs; a; a = a->Next) {
            if (a->OperStatus != IfOperStatusUp) continue;
            for (auto u = a->FirstUnicastAddress; u; u = u->Next) {
                sockaddr_in* sa = (sockaddr_in*)u->Address.lpSockaddr;
                char ipStr[INET_ADDRSTRLEN] = {0};
                inet_ntop(AF_INET, &(sa->sin_addr), ipStr, sizeof(ipStr));
                if (strcmp(ipStr, "127.0.0.1") == 0) continue;

                char nameBuf[256] = {0};
                WideCharToMultiByte(CP_UTF8, 0, a->FriendlyName, -1, nameBuf, sizeof(nameBuf) - 1, NULL, NULL);

                std::string type = "Network";
                if (strstr(nameBuf, "Wi-Fi") || strstr(nameBuf, "Wireless")) {
                    type = "Wi-Fi (Wireless)";
                } else if (strstr(nameBuf, "Ethernet") || strstr(nameBuf, "RNDIS") || strstr(nameBuf, "USB")) {
                    type = "USB / Wired";
                }

                printf("  [%-22s] %-16s (%s)\n", nameBuf, ipStr, type.c_str());
            }
        }
    }
    if (addrs) free(addrs);
    std::cout << "=========================================================" << std::endl;
}

