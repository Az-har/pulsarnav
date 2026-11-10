---
name: pulsarnav
description: Architecture, BLE protocol, packet structures, UI design system, and contribution patterns for the PulsarNav open-source Android motorcycle companion app. Use when adding features, modifying BLE communication, enhancing navigation interception, or refactoring PulsarNav.
---

# PulsarNav Engineering Guide

PulsarNav is a privacy-first, zero-bloat, open-source Android companion app for Bajaj motorcycles equipped with Bluetooth LCD/LED digital consoles (Pulsar NS200, NS160, N250, F250, Dominar 400/250, Rouser). It replaces the proprietary Bajaj Ride Connect app, giving riders clean Google Maps & Waze Turn-by-Turn navigation, handlebar media controls, trip tracking, and offline data vault backups.

## Repository Architecture

```
app/src/main/
├── java/com/pulsarnav/app/
│   ├── ble/                # Bluetooth Low Energy lifecycle
│   │   ├── BajajBleManager.kt       # GATT connection, MTU negotiation, timeout auto-scan loop
│   │   └── BleConnectionState.kt    # Disconnected, Scanning, Connecting, Connected, Error
│   ├── protocol/           # OTC Engineering binary packet builders
│   │   ├── OtcPacketBuilder.kt      # 48-byte TBT frames, 89-byte General status, Alerts
│   │   └── CallState.kt             # NO_CALL, INCOMING_CALL, ACTIVE_CALL, CALL_ENDED
│   ├── parser/             # Navigation notification interceptor
│   │   ├── ParsedNavigationStep.kt  # Step distance, ETA, street name, roundabout exits
│   │   └── Maneuver.kt              # 24 console icon opcodes (Straight, Turns, Exits, U-Turn)
│   ├── service/            # Background & listener services
│   │   ├── BikeBleForegroundService.kt      # Foreground service with PARTIAL_WAKE_LOCK
│   │   └── GoogleMapsNotificationListener.kt# Android NotificationListener for Maps/Waze
│   ├── data/               # Persistent storage & vehicle database
│   │   ├── AppSettings.kt           # SharedPreferences wrapper with StateFlow properties
│   │   ├── VehicleCatalog.kt        # Country specs, fuel tank sizes, reserve limits, currencies
│   │   └── DataVaultManager.kt      # Encrypted .pnav backup & QR code cross-device sync
│   ├── maintenance/        # Maintenance, service records & fuel ledger
│   │   ├── FuelLogManager.kt        # Fuel consumption, mileage calculation (km/L), cost tracking
│   │   └── MaintenanceManager.kt   # Chain lube (500 km) & periodic service alert calculations
│   ├── media/              # Handlebar media controls
│   │   └── MediaControlManager.kt   # MediaSessionManager KeyEvent dispatch (Next, Play/Pause)
│   ├── telephony/          # Caller ID & call management
│   │   └── CallManager.kt           # TelecomManager answer/hangup on handlebar button press
│   ├── tracking/           # GPS Ride Tracker
│   │   └── RideTracker.kt           # Real-time speed, average speed, trip duration, GPX
│   └── ui/                 # Activity and Obsidian UI
│       └── MainActivity.kt          # Single-activity architecture with 4 main navigation tabs
└── res/
    ├── layout/             # Obsidian Dark UI cards and bottom sheets
    └── values/             # Design tokens, color palettes (Wine Red, Stealth, Pulsar Blue)
```

## Binary Bluetooth LE Protocol (OTC Engineering)

The motorcycle cluster uses the OTC Engineering BLE chipset. Communication uses custom 128-bit UUIDs:

- **Service UUID**: `0010676e-6972-6565-6e69-676e4543544f`
- **TBT Characteristic UUID**: `0110676e-6972-6565-6e69-676e4543544f` (Write without response)
- **General Telemetry Characteristic UUID**: `0210676e-6972-6565-6e69-676e4543544f` (Heartbeat status)
- **Missed Call Characteristic UUID**: `0310676e-6972-6565-6e69-676e4543544f`
- **Alerts Characteristic UUID**: `0410676e-6972-6565-6e69-676e4543544f`
- **Handlebar Buttons UUID**: `0a10676e-6972-6565-6e69-676e4543544f` (Read / Poll)

### 1. MTU Negotiation
Upon connection, the app **must request MTU 256** (`gatt.requestMtu(256)`). The 48-byte TBT frame and 89-byte General Status frame exceed default BLE 23-byte MTU. A 2.5s fallback timer proceeds if the cluster does not emit `onMtuChanged`.

### 2. Turn-By-Turn (TBT) Packet Structure (48 bytes)
Built in `OtcPacketBuilder.buildTbtFrame()`:
- `Byte 0..3`: Packet header / stream magic
- `Byte 4`: Maneuver Opcode (`0x01` straight, `0x02` slight right, `0x03` right, `0x07` roundabout, etc.)
- `Byte 5`: Roundabout exit number (`1..7`) or default exit (`1`)
- `Byte 6..9`: Next turn distance (Big-endian integer meters)
- `Byte 10..13`: Total remaining trip distance (Big-endian integer meters)
- `Byte 14`: ETA Hour (`0..23` or `1..12`)
- `Byte 15`: ETA Minute (`0..59`)
- `Byte 16`: ETA AM/PM flag (`0` = AM, `1` = PM)
- `Byte 17..47`: Street name (Null-terminated ASCII, truncated to 30 characters)

### 3. General Telemetry Packet Structure (89 bytes)
Built in `OtcPacketBuilder.buildGeneralPacketV2()` and streamed every 5000ms:
- Phone battery percentage (`0..100`)
- Signal strength bars (`0..5`)
- Media / ring volume (`0..15`)
- Call state opcode: `0x00` (idle), `0x01` (incoming ringing), `0x02` (active)
- Caller ID string (truncated to 20 ASCII characters)

### 4. Handlebar Controls & Media Interception
Polls characteristic `0a10...` every 700ms when GATT write queue is idle:
- Single tap on button: Toggle Play/Pause (`KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE`).
- Double tap / long press: Next track (`KeyEvent.KEYCODE_MEDIA_NEXT`).
- Incoming call: Tap answers (`telecomManager.acceptRingingCall()`), long press rejects (`telecomManager.endCall()`).

## BLE Auto-Connect Loop Pattern

To prevent battery drain and avoid Android OS scan throttling (max 5 scans per 30 seconds):
1. **Active Scan Window**: 10 seconds in `SCAN_MODE_LOW_LATENCY` with `ScanFilter` for `OTC_SERVICE_UUID` and `savedAddress`.
2. **Pause Interval**: 15 seconds radio shutoff (`stopScan()`).
3. **Reactive Reconnection**:
   - `BluetoothAdapter.ACTION_STATE_CHANGED`: Turning Bluetooth ON immediately triggers an auto-scan cycle.
   - Disconnect: Automatic retry scheduled after a 4-second backoff.
   - Manual Disconnect: Sets `userRequestedDisconnect = true` until user taps Scan or turns Bluetooth OFF/ON.

## UI & Design System

The app follows the **Pulsar Obsidian Dark** design system:
- **Background**: Obsidian Black (`#0D0E12` / `#08090C`).
- **Cards**: Surface elevated (`#16181F`), rounded corners (`16dp`), border stroke (`1dp`, `#222530`).
- **Accent Palettes**:
  - `Wine Red`: `#E52030` primary, `#FF4D5A` bright (Pulsar signature).
  - `AMOLED Black`: High-contrast pure black for battery saving.
  - `Stealth Mono`: `#788296` slate monochrome.
  - `Pulsar Blue`: `#0066FF` electric blue.
- **Top Bar Chips**: Hardware readiness status chips (`BT ON/OFF`, `GPS ON/OFF`, `PERMS`).
- **Input Fields**: Dynamic `windowSoftInputMode="adjustResize"` with bottom sheet padding so keyboards never obscure input buttons.

## Contribution & Verification Checklist

Before submitting PRs or distributing APKs:
1. **Clean Compilation**: Run `./gradlew assembleRelease` and ensure 0 errors.
2. **Security Integrity**:
   - No hardcoded secrets, keys, or cleartext HTTP traffic.
   - Keep `android:exported="false"` on internal services (`BikeBleForegroundService`).
   - Keep `android:showWhenLocked="false"` (no lock screen bypass).
3. **Offline Privacy**: No external tracking SDKs or internet telemetry. All metrics remain on-device.
