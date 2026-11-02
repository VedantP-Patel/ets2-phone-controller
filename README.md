# 🚛 ETS2 Phone Controller

Transform your Android smartphone into a **high-precision steering wheel, analog pedal set, and complete truck dashboard** for Euro Truck Simulator 2 — with ultra-low latency (~5–12ms over USB), two-way force feedback vibration, and zero-configuration auto-discovery.

> 📖 **Comprehensive Technical Documentation:** See the complete architectural guide, kinematic research, Scania 12+2 Opticruise transmission logic, and dual-dispatch input specifications in [docs/COMPLETE_SYSTEM_DOCUMENTATION.md](file:///d:/Projects/ets2-phone-controller/docs/COMPLETE_SYSTEM_DOCUMENTATION.md).

---

## ⚡ Quick Start in 3 Steps

### Step 1: Start the PC Server

> [!NOTE]
> **First-time setup on a new PC:** Windows requires the free, open-source **ViGEmBus driver** to emulate an Xbox 360 controller.
> - Double-click `install_vigembus.bat` (or run in PowerShell: `winget install --id ViGEm.ViGEmBus -e`).
> - *Tip: `start.bat` will also detect if it's missing and offer to install it for you automatically!*

Double-click:
```text
d:\Projects\ets2-phone-controller\start.bat
```
- It will instantly launch `ets2-controller.exe`.
- Your PC will register a genuine **Xbox 360 controller** (via the kernel-level ViGEmBus driver).
- You can verify this by pressing `Win + R` → typing `joy.cpl` → you will see `Controller (XBOX 360 For Windows)` active.

### Step 2: Install App on Your Phone
Open the Android project in Android Studio:
```text
d:\Projects\ets2-phone-controller\android
```
- Connect your phone via USB.
- In Android Studio, click the green **Run ▶** button (or run `android\build_apk.bat`).
- The app will install and open in landscape mode on your phone.

### Step 3: Connect & Drive
- Connect your phone to the PC either via **USB Tethering** (recommended for ~5ms latency) or **Same Wi-Fi**.
- The app will automatically discover the PC and show **`Connected: <IP> (100 pps)`**.
- Hold your phone at your preferred driving angle and tap **🎯 Calibrate Center**.
- Open ETS2 and enjoy driving!

---

## 🎮 Euro Truck Simulator 2 In-Game Configuration

Go to **Options > Controls** in ETS2 and apply these settings (matching your in-game setup):

### 1. Control Method
- Set configuration profile to: **Controls** (Steering wheel, gamepad or joystick)
- Gearbox type: **Real Automatic** (easiest for pedals) or **Sequential**

### 2. Controller Axes Setup
```
┌────────────────────────────────────────────────────────┐
│ CONTROLLER AXES                                        │
│ Steering axis:          [ Joy X Axis                 ] │
│ Steering axis deadzone: [ ■□□□□□□□□□□□□□□□ ] 2-3%     │
│ Steering axis mode:     [ Centered                   ▼]│
│                                                        │
│ Acceleration axis:      [ Joy RT                     ] │
│ Acceleration deadzone:  [ ■□□□□□□□□□□□□□□□ ] 2%       │
│ Acceleration axis mode: [ Normal                     ▼]│  <-- IMPORTANT!
│                                                        │
│ Brake axis:             [ Joy LT                     ] │
│ Brake deadzone:         [ ■□□□□□□□□□□□□□□□ ] 2%       │
│ Brake axis mode:        [ Normal                     ▼]│  <-- IMPORTANT!
└────────────────────────────────────────────────────────┘
```
> [!IMPORTANT]
> **Pedal Axis Mode**:
> In ETS2, make sure both **Acceleration axis mode** and **Brake axis mode** are set to **`Normal`** (not `Centered`).
> `Normal` mode gives you the full range of the pedal (0% released at the bottom to 100% full throttle at the top).

### 3. Steering Sensitivity "Sweet Spot"
Scroll up in the **Controls** menu:
- **Steering Sensitivity**: `35% to 45%`
- **Steering Non-Linearity**: `75% to 85%`
  - *Why this works:* Non-linearity prevents minor hand tremors from jerking the truck when driving straight at 80 km/h on highways, while full wrist rotation gives you instant sharp lock for 90° motorway exits and roundabout turns.

### 4. Force Feedback / Rumble (Tactile Road Feel)
Ensure these are set to `100%` under **Controls**:
- **Terrain surface vibration**: `100%`
- **Bumps vibration**: `100%`
- **Collisions vibration**: `100%`
When your truck hits highway rumble strips, curbs, or revs high, **your phone will vibrate dynamically in your hands!**

---

## 📱 Cockpit Button Cheat Sheet

| Button Icon | Label | Xbox Button | ETS2 Default Action |
|:---:|:---|:---:|:---|
| 🔊 | **HORN / OK** | `A` | Sound Horn + Confirm Tutorial / Dialogue |
| 🔑 | **ENGINE** | `B` | Start / Stop Engine (Key `E`) |
| 💡 | **LIGHTS** | `X` | Cycle Headlights (Off → Parking → Low → High) |
| ⚠️ | **HAZARD** | `Y` | Hazard Warning Flashers + Zoom Advisor |
| ◀ | **L. TURN** | `LB` | Left Turn Signal |
| ▶ | **R. TURN** | `RB` | Right Turn Signal |
| 🅿️ | **PARK BRK** | `LS` (Click) | Parking Handbrake (Key `Space`) |
| 🛑 | **EXHAUST** | `RS` (Click) | Engine / Exhaust Brake (Key `B`) |
| 🗺 | **MAP / ADVISOR** | `Back` | Toggle Route Advisor Navigation Modes |
| ⏸ | **PAUSE** | `Start` | Pause Menu / Cruise Control |
| 🔼 | **GEAR UP** | `D-Pad Up` | Shift Up Gear (Sequential) / Cabin Camera |
| 🔽 | **GEAR DOWN** | `D-Pad Down`| Shift Down Gear (Sequential) |
| 🧹 | **WIPERS** | `D-Pad Left`| Cycle Windshield Wipers |
| 📷 | **NEXT CAM** | `D-Pad Right`| Cycle Camera Views (1 through 8) |

---

## ⚙️ Fine-Tuning From Inside the Phone App

Tap the **`⚙️` Settings** icon at the top right of your phone screen to adjust:
- **Steering Deadzone**: `0° to 10°` (Default: `3.0°`)
- **Full Lock Angle**: `25° to 90°` (Default: `45°` — rotate your wrist 45° for 100% lock)
- **Response Curve**: `1.0x (Linear)` to `2.2x (Progressive)`
- **Resting Tremor Smoothing**: `0.4` to `2.5` (1-Euro adaptive filter parameter)
- **Force Feedback Rumble**: Toggle ON/OFF

---

## 🛠️ Architecture Details

- **Transport Protocol**: Raw binary UDP datagrams (`12 bytes` per packet at 100Hz).
- **Auto-Discovery**: PC broadcasts `ETS2_CTRL_BEACON` on UDP Port `5006`. Android app listens and connects automatically.
- **Reverse Haptic Stream**: PC sends 4-byte UDP vibration packets on Port `5007` to trigger Android vibrator motors.
- **Kernel-Level Input**: Direct IOCTL to `ViGEmBus.sys` creates a native XInput device with sub-microsecond latency and zero translation layers.

