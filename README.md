# 🚛 ETS2 Phone Controller

Turn your Android smartphone into a **precision steering wheel, analog pedal set, live truck telemetry dashboard, and cab radio controller** for *Euro Truck Simulator 2* — powered by a **native C++ SCS Input + Telemetry SDK plugin** (`ets2_phone_plugin.dll`), low-latency UDP + TCP fallback, and road-surface haptics.

> 📖 **Technical Reference:** See [docs/COMPLETE_SYSTEM_DOCUMENTATION.md](docs/COMPLETE_SYSTEM_DOCUMENTATION.md) for kinematic sensor filtering, UDP/TCP protocol specs, and `controls.sii` bindings.

---

## ⚡ Plug-and-Play Setup (No Compilers or Android Studio Required!)

### Option A: Download Ready-to-Use Portable Release (Recommended)
1. Download **`ETS2-Phone-Controller-Portable.zip`** from the [Latest GitHub Release](https://github.com/VedantP-Patel/ets2-phone-controller/releases/latest) and extract it anywhere on Windows. *(Or clone this repo—`start.bat` automatically downloads the prebuilt DLL & APK if you don't have compilers installed.)*
2. Double-click **`start.bat`**.
3. If your ETS2 install wasn't detected automatically, press **`[P]`** to paste your Euro Truck Simulator 2 folder path (e.g. `C:\Program Files (x86)\Steam\steamapps\common\Euro Truck Simulator 2`).
4. Press **`[F]`** once to allow UDP `5005/5006` + TCP `5005` through Windows Firewall (required for Wi-Fi / Phone Hotspot / USB Tethering).

### Install the App on Your Phone
In `start.bat`, press **`[B]`**:
- **Phone connected via USB Debugging (ADB):** Automatically downloads/builds and installs the APK.
- **No USB cable / No ADB / No Android Studio:** `start.bat` launches a local **Wi-Fi APK Installer (`http://<PC-IP>:8099`)** — open that address in Chrome on your phone (same Wi-Fi or Hotspot) and tap **Download APK**.

### Bind Controls & Drive
1. If you haven't created a driver profile in ETS2 yet, launch ETS2 once, open/create your profile, click **OK** when ETS2 asks **"Request to use advanced SDK features"**, and exit ETS2.
2. With ETS2 closed, press **`[A]`** in `start.bat` to auto-bind all 6 analog axes + 64 cockpit buttons into your profile (supports local `Documents`, `OneDrive`, and **Steam Cloud** profiles).
3. Press **`Enter`** in `start.bat` to launch ETS2. Click **OK** on the SDK prompt if shown, load your truck, hold your phone naturally, and hold **CENTER** to calibrate steering.

---

## 🔌 Connecting Your Phone (`NO PC` Troubleshooting)

1. **Same Wi-Fi or Mobile Hotspot (Easiest):** Connect PC and phone to the same router or turn on your phone's Wi-Fi Hotspot / USB Tethering (`RNDIS`). The app auto-discovers the PC on UDP port `5006`.
2. **Manual IP (Strict Routers / Firewalls):** Tap the top-left `NO PC` bar in the phone app and enter the green **Wi-Fi / Ethernet IP** shown in `start.bat`. If UDP is blocked by your router, the app automatically falls back to **TCP port `5005`**.
3. **USB Wired (`127.0.0.1`, Zero Wi-Fi Needed):** Connect with USB Debugging enabled, run `start.bat` (or [pc/scripts/start_usb_mode.bat](pc/scripts/start_usb_mode.bat)), and tap **`USB 127.0.0.1`** in the Connection dialog.

### Common First-Time Pitfalls & Fixes
| Symptom | Fix |
|:---|:---|
| **"Missing device `sdk.ets2_phone_cockpit`" in ETS2** | Make sure `ets2_phone_plugin.dll` is in `<ETS2>\bin\win_x64\plugins\`, launch ETS2, click **OK** on **"Request to use advanced SDK features"**, exit ETS2, then run **`[A]`** in `start.bat`. Press **`[L]`** in `start.bat` to check `game.log.txt`. |
| **Plugin DLL missing (`No C++ compiler found`)** | `start.bat` downloads the prebuilt DLL from GitHub Releases automatically, or grab `ets2_phone_plugin.dll` from [Releases](https://github.com/VedantP-Patel/ets2-phone-controller/releases/latest) and copy it into `<ETS2>\bin\win_x64\plugins\`. |
| **Phone stays on `NO PC`** | Press **`[F]`** in `start.bat` (opens Public + Private Firewall rules), disable PC VPNs temporarily, verify ETS2 is running (`scs_input_init` active), or try Mobile Hotspot / **`USB 127.0.0.1`**. |
| **Steam Cloud profile didn't update** | Make sure ETS2 is **completely closed** before pressing **`[A]`** so Steam/ETS2 doesn't overwrite `controls.sii`. |

---

## 🛠️ Building from Source (Developers)

- **PC Native Plugin (`CMake` / MSVC Static CRT or MinGW `g++`):**
  ```powershell
  cmake -B pc/build-cmake -S pc
  cmake --build pc/build-cmake --config Release
  ```
- **Android Unit Tests & APK Build:**
  ```powershell
  cd android
  .\gradlew.bat testDebugUnitTest assembleDebug
  ```
