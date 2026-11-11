---
name: pulsarnav
description: Architecture, BLE protocol, packet structures, UI design system, and contribution patterns for the PulsarNav open-source Android motorcycle companion app. Use when adding features, modifying BLE communication, enhancing navigation interception, or refactoring PulsarNav.
---

# PulsarNav Engineering Guide

PulsarNav is a privacy-first, zero-bloat, open-source Android companion app for Bajaj motorcycles equipped with Bluetooth LCD/LED digital consoles (Pulsar NS200, NS160, N250, F250, Dominar 400/250, Rouser). It replaces the proprietary Bajaj Ride Connect app, giving riders clean Google Maps & Waze Turn-by-Turn navigation, handlebar media controls, trip tracking, and encrypted offline `.pnav` backups.

## Repository Architecture

```
app/src/main/
├── java/com/pulsarnav/app/
│   ├── ble/                # Bluetooth Low Energy lifecycle
│   │   ├── BajajBleManager.kt       # Dedicated GATT HandlerThread, FIFO/priority queue, MTU negotiation, throttled auto-scan
│   │   └── BleConnectionState.kt    # Disconnected, Scanning, Connecting, Connected, Error
│   ├── protocol/           # OTC Engineering binary packet builders
│   │   ├── OtcPacketBuilder.kt      # 48-byte TBT frames, 89/55-byte General status, Alerts, Missed Call
│   │   └── CallState.kt             # NO_CALL, INCOMING_CALL, OUTGOING_CALL, ACTIVE_CALL, END_CALL
│   ├── parser/             # Navigation notification interceptor
│   │   ├── GoogleMapsNotificationParser.kt # Distance/ETA/maneuver/street extraction + icon fallback
│   │   ├── ManeuverMatcher.kt       # Lock-free drawable template matcher for vector arrows
│   │   └── ParsedNavigationStep.kt  # Step distance, ETA, street name, roundabout exits
│   ├── model/
│   │   └── Maneuver.kt              # 22 verified console icon opcodes (66..90)
│   ├── service/            # Background & listener services
│   │   ├── BikeBleForegroundService.kt      # connectedDevice foreground service + PARTIAL_WAKE_LOCK
│   │   └── GoogleMapsNotificationListener.kt# Android NotificationListener for Maps/Waze/WhatsApp
│   ├── receiver/
│   │   └── BluetoothIgnitionReceiver.kt     # Classic BT ACL trigger -> BLE auto-connect & trip save
│   ├── data/               # Persistent storage, crypto & vehicle database
│   │   ├── AppSettings.kt           # SharedPreferences wrapper with StateFlow properties
│   │   ├── VehicleCatalog.kt        # Country specs, fuel tank sizes, reserve limits, currencies, RSA
│   │   ├── VaultCrypto.kt           # Pure-JVM PBKDF2-HMAC-SHA256 + AES-256-GCM .pnav crypto
│   │   └── DataVaultManager.kt      # Encrypted .pnav / legacy .pnav.json export & QR sync
│   ├── location/
│   │   └── ParkingManager.kt        # Last parked GPS location + walking directions
│   ├── maintenance/        # Maintenance, service records & fuel ledger
│   │   ├── FuelEconomyCalculator.kt # Pure-Kotlin full-tank & partial-fill chained mileage math
│   │   ├── FuelLogManager.kt        # Fuel consumption, mileage calculation (km/L), cost tracking
│   │   └── MaintenanceManager.kt    # Chain lube (500 km) & periodic service alert calculations
│   ├── media/              # Handlebar media controls
│   │   └── MediaControlManager.kt   # AudioManager KeyEvent dispatch (Play/Pause, Next, Prev)
│   ├── telemetry/
│   │   └── PhoneTelemetryManager.kt # Battery, cellular signal & heartbeat sync
│   ├── telephony/          # Caller ID & call management
│   │   └── CallManager.kt           # TelecomManager answer/hangup + background contact lookup
│   ├── tracking/           # GPS Ride Tracker
│   │   └── RideTracker.kt           # Speed, distance, duration, serialized JSON persistence
│   └── ui/                 # Activity and Obsidian/Pearl UI
│       ├── CockpitThemePalette.kt   # Light & Dark palette definitions (Wine Red, AMOLED, Stealth, Amber, Mint, Blue, Custom)
│       └── MainActivity.kt          # Single-activity architecture with 4 main navigation tabs
└── res/
    ├── layout/             # Obsidian/Adaptive UI cards and bottom sheets
    └── values/             # Design tokens and color palettes
```

## Binary Bluetooth LE Protocol (OTC Engineering)

The motorcycle cluster uses the OTC Engineering BLE chipset. Communication uses custom 128-bit UUIDs:

- **Service UUID**: `0010676e-6972-6565-6e69-676e4543544f`
- **TBT Characteristic UUID**: `0110676e-6972-6565-6e69-676e4543544f` (Write without response)
- **General Telemetry Characteristic UUID**: `0210676e-6972-6565-6e69-676e4543544f` (Heartbeat & Call State)
- **Missed Call Characteristic UUID**: `0310676e-6972-6565-6e69-676e4543544f` (Missed call packet)
- **Alerts Characteristic UUID**: `0410676e-6972-6565-6e69-676e4543544f` (WhatsApp/SMS alerts)
- **Handlebar Buttons UUID**: `0a10676e-6972-6565-6e69-676e4543544f` (Notify/Indicate + Read fallback)

### 1. MTU Negotiation & Serialization
- Every BLE GATT operation (descriptor CCCD writes, characteristic writes, polling reads) must serialize on a single operation callback (`onCharacteristicWrite` / `onDescriptorWrite`) with timeout protection on the dedicated `BajajBleGatt` handler thread.
- Upon service discovery, request MTU `256` (`gatt.requestMtu(256)`). The 48-byte TBT frame and 89-byte General Status frame exceed default BLE 23-byte ATT payload. A 2.5s fallback timer proceeds if the cluster does not emit `onMtuChanged`.

### 2. Turn-By-Turn (TBT) Packet Structure (48 bytes)
Built in `OtcPacketBuilder.buildTbtFrame()` / `buildTbtStopFrame()`:
- `Byte 0`: Flags — `bit 0` = type (`1` active, `0` stop), `bit 4` = step distance unit (`1` = meters, `0` = km), `bit 7` = ETA PM flag (`1` = PM, `0` = AM)
- `Byte 1`: Maneuver Opcode (`66..90`, `Maneuver.kt`; `+32` blink bit for turns `< 100m` during active updates)
- `Byte 2..5`: Step distance (`fLow, fHigh, iLow, iHigh` little-endian hundredths + integer; in meters `< 999.5m`, in km `>= 999.5m`)
- `Byte 6`: ETA minute (`0..59`)
- `Byte 7`: ETA hour (`1..12` in bits `0..3`, `0` allowed on stop frame) | `(roundaboutExit << 4)` in bits `4..7`
- `Byte 8..11`: Total remaining trip distance (`fLow, fHigh, iLow, iHigh`)
- `Byte 12`: `bit 0` = total distance unit (`1` = m, `0` = km) | `bits 2..3` = GPS active (`1` when `type != 0`, `0` on stop)
- `Byte 13`: `takeMeHome` ack (`0`)
- `Byte 14`: Street length `N` (`0..31`)
- `Byte 15..45`: Street / instruction text ASCII (NFD-transliterated, truncated `<= 31` bytes with trailing `.`)
- `Byte 46`: Reserved (`0`)
- `Byte 47`: Checksum = `sum(bytes[0..46]) & 0xFF`
- Stop frame (`buildTbtStopFrame()`): 48 zero bytes (`type = 0`, valid checksum `0`).

### 3. General Telemetry Packet Structure (89 bytes V2 / 55 bytes Legacy)
Built in `OtcPacketBuilder.buildGeneralPacketV2()` / `buildGeneralPacketLegacy()` and streamed every `5000ms` (when telemetry sync enabled or during active call):
- Phone battery mapped to V2 coarse bars (`0..3` at `>=25/50/75%`, or `0..4` legacy)
- Signal strength (`0..7` bars in `byte[2]`)
- Media/ring volume (`0..15` | `0xC0` in `byte[0]`)
- Call state opcode (`NO_CALL = 0`, `INCOMING = 1`, `OUTGOING = 2`, `ACTIVE = 3`, `END = 4`)
- Caller ID string (up to `32` ASCII characters in V2 `bytes[20..51]`)
- Byte `54`: Atomic thread-safe rolling heartbeat counter (`0..255`)
- Byte `88`: Checksum = `sum(bytes[0..87]) & 0xFF`

### 4. Handlebar Controls & Media Interception
Subscribes via CCCD on `0a10...` (and falls back to `700ms` polling when notifications aren't confirmed and the GATT queue is idle):
- Single short press: Play/Pause (`KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE`)
- Long press: Next track (`KeyEvent.KEYCODE_MEDIA_NEXT`)
- Short press + hold (`<= 1000ms` after release): Previous track (`KeyEvent.KEYCODE_MEDIA_PREVIOUS`)
- Incoming call: Accept (`telecomManager.acceptRingingCall()`) / Reject (`telecomManager.endCall()`)

## BLE Auto-Connect Loop Pattern

To prevent battery drain and respect Android's 5-scans-per-30s OS scan throttle:
1. **Active Scan Window**: 10 seconds in `SCAN_MODE_LOW_LATENCY` filtered by `OTC_SERVICE_UUID` or `savedAddress`.
2. **Pause Interval**: 15 seconds between scan windows, tracked in a 30s sliding window (`MAX_SCANS_PER_WINDOW = 4`).
3. **Reactive Reconnection**:
   - `BluetoothAdapter.ACTION_STATE_CHANGED` / `BluetoothIgnitionReceiver` (`ACL_CONNECTED`): triggers `startAutoConnect(force = true)`.
   - Unexpected Disconnect: Retry scheduled after 4-second backoff.
   - Manual Disconnect: Sets `userRequestedDisconnect = true` until user taps Scan or turns Bluetooth OFF/ON.

## UI & Design System

The app supports adaptive **Light** (`Pearl`) and **Dark** (`Obsidian` & `AMOLED`) palettes managed in `CockpitThemePalette.kt`:
- **Obsidian Dark surfaces**: Background `#131215`, Surface `#1C1B20`, Card `#222128`, Border `#32303B` (`AMOLED`: `#000000` bg, `#0B0B0E` card).
- **Light surfaces**: Background `#F4F5F8`, Surface/Card `#FFFFFF`, Border `#E2E8F0`.
- **Accent Themes**: `Wine Red` (`#9E1B32` / `#C92A45`), `AMOLED`, `Stealth Mono`, `Neon Amber`, `Hyper Mint`, `Liquid Blue`, and `Custom RGB`.
- **Top Bar Chips**: Hardware readiness status chips (`BT OFF`, `GPS OFF` on `< API 31`, `PERMS NEEDED`).

## Contribution & Verification Checklist

Before submitting PRs or distributing APKs:
1. **Compile, Lint, Test**: Run `./gradlew assembleRelease testDebugUnitTest lintDebug` and ensure 0 errors.
2. **Protocol Check**: Run `node research/scripts/test_protocol_and_parser.js`.
3. **Security Integrity**:
   - Never commit production release keystores or passwords (`keystore.properties` / `RELEASE_*` env vars).
   - Keep `android:exported="false"` on `BikeBleForegroundService` and implicit `RECEIVER_NOT_EXPORTED` on runtime receivers.
   - Keep `.pnav` backups encrypted with `VaultCrypto` (PBKDF2-HMAC-SHA256 + AES-256-GCM).
