# 🚛 ETS2 Phone Controller

Transform your Android smartphone into a **precision steering wheel, analog pedal set, and live truck telemetry dashboard** for *Euro Truck Simulator 2* — powered by a **native C++ SCS Input + Telemetry SDK plugin** (`ets2_phone_plugin.dll`), ultra-low-latency binary UDP/TCP transport, and two-way road-surface & suspension haptics.

> 📖 **Technical Reference:** See [docs/COMPLETE_SYSTEM_DOCUMENTATION.md](file:///d:/Projects/ets2-phone-controller/docs/COMPLETE_SYSTEM_DOCUMENTATION.md) for kinematic sensor research, 1-Euro filtering, and `controls.sii` binding details.

---

## ⚡ Quick Start in 3 Steps

### Step 1: Build & Deploy the Native ETS2 Plugin
Double-click:
```text
d:\Projects\ets2-phone-controller\start.bat
```
- Automatically detects your ETS2 installation (`Steam` registry / `libraryfolders.vdf` / common paths).
- Compiles `ets2_phone_plugin.dll` (SCS Input SDK + SCS Telemetry SDK) and copies it into `<ETS2>\bin\win_x64\plugins\`.
- Press **`[A]`** in the launcher (with ETS2 closed) to run [auto_bind_ets2.ps1](file:///d:/Projects/ets2-phone-controller/pc/scripts/auto_bind_ets2.ps1) and map all 6 analog axes + 60 cockpit buttons into your `controls.sii` profile automatically.
- Press **`[F]`** if needed to open Windows Firewall for UDP ports `5005` & `5006`.

### Step 2: Install App on Your Phone
Open the Android project in Android Studio (`d:\Projects\ets2-phone-controller\android`) or press **`[B]`** in `start.bat` to build and install the debug APK via ADB (`gradlew.bat assembleDebug`).

### Step 3: Connect & Drive
- **Wi-Fi or USB Tethering (Recommended UDP Mode):** Connect your phone to the same LAN or enable **USB Tethering (RNDIS)** (~1–3 ms latency). The plugin broadcasts `ETS2_COCKPIT_V2` on UDP port `5006` and the app connects automatically.
- **USB ADB Wired Mode (No Tethering Needed):** Run [start_usb_mode.bat](file:///d:/Projects/ets2-phone-controller/pc/scripts/start_usb_mode.bat) (`adb reverse tcp:5005 tcp:5005`) and enter `127.0.0.1` in the app's Connection dialog.
- Hold your phone at your natural driving angle and hold **CENTER** to calibrate.

---

## 🎮 Architecture Overview (V2 Native SDK)

| Layer | Details |
|:---|:---|
| **PC Plugin (`pc/src/ets2_phone_plugin.cpp`)** | Native C++20 DLL exporting `scs_input_init` (6 axes, 60 buttons) & `scs_telemetry_init` (truck, trailer, shifter config, wheel suspension & surface channels). |
| **Uplink Control (`29 bytes @ 100 Hz`)** | `PhoneControlPacket` (`0x4532` magic, v2, flags, 16-bit seq & timestamp, 64-bit button mask, steering, throttle, brake, clutch, look X/Y, XOR checksum). |
| **Downlink Telemetry (`42 bytes @ 40 Hz`)** | `TruckTelemetryPacket` (speed, RPM, air pressure, gear, fuel, AdBlue, speed limit, cruise, damage, retarder, G-forces, suspension/substance haptics, dashboard & safety flags, route ETA, echoed timestamp for RTT). |
| **Auto-Discovery** | Per-interface subnet & global UDP beacon (`ETS2_COCKPIT_V2`) on port `5006`. |
| **Transport** | UDP (`5005`) over Wi-Fi / USB Tethering, plus local TCP loopback (`127.0.0.1:5005`, `TCP_NODELAY`) for `adb reverse`. |

---

## 🛠️ Verification & Building Manually

- **Build PC Plugin (`CMake` / MSVC):**
  ```powershell
  cmake -B pc/build -S pc
  cmake --build pc/build --config Release
  ```
- **Run Android Unit Tests & Build APK:**
  ```powershell
  cd android
  .\gradlew.bat testDebugUnitTest assembleDebug
  ```
