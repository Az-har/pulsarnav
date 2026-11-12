---
name: pulsarnav
description: Architecture, BLE protocol, packet structures, UI design system, and contribution patterns for the PulsarNav open-source Android motorcycle companion app. Use when adding features, modifying BLE communication, enhancing navigation interception, or refactoring PulsarNav.
---

# PulsarNav Engineering Guide

PulsarNav is a privacy-first, zero-bloat, open-source Android companion app for Bajaj motorcycles equipped with Bluetooth LCD/LED digital consoles (Pulsar NS200, NS160, N250, F250, Dominar 400/250, Rouser) and OTC-based 5" TFT consoles (Pulsar NS400Z). It replaces the proprietary Bajaj Ride Connect app, giving riders clean Google Maps & Waze Turn-by-Turn navigation, handlebar media controls, trip tracking, and encrypted offline `.pnav` backups.

## Repository Architecture

```
app/src/main/
├── java/com/pulsarnav/app/
│   ├── ble/                # Bluetooth Low Energy lifecycle & safety guards
│   │   ├── BajajBleManager.kt       # Dedicated GATT HandlerThread, BleConnectionState, FIFO/priority queue, MTU & TFT bonding
│   │   └── BleProtocolSafety.kt     # MTU gates (>=114 V2/0610, >=154 0910/0b10, >=180 0510), TBT bounds, TFT bonding helpers
│   ├── protocol/           # OTC Engineering binary packet builders
│   │   └── OtcPacketBuilder.kt      # CallState, 48B TBT, 89/55B General, Alerts, Missed Call, 111B Music (0610), 177B Playlist (0510), 151B Fav/Recents (0910/0b10)
│   ├── navigation/
│   │   └── NavigationIntentLauncher.kt # Shared Google Maps / Waze launcher for in-app shortcuts, Take Me Home & NAV_V2
│   ├── parser/             # Navigation notification interceptor
│   │   ├── GoogleMapsNotificationParser.kt # ParsedNavigationStep + distance/ETA/maneuver/street extraction + icon fallback
│   │   └── ManeuverMatcher.kt       # Executor-backed drawable template matcher for vector arrows
│   ├── model/
│   │   └── Maneuver.kt              # 22 verified console icon opcodes (66..90)
│   ├── service/            # Background & listener services
│   │   ├── BikeBleForegroundService.kt      # connectedDevice|location foreground service + PARTIAL_WAKE_LOCK
│   │   └── GoogleMapsNotificationListener.kt# Android NotificationListener for Maps/Waze/WhatsApp + active media sessions
│   ├── receiver/
│   │   └── BluetoothIgnitionReceiver.kt     # Privileged-guarded Classic BT ACL trigger -> BLE auto-connect & 15s grace end-ride
│   ├── data/               # Persistent storage, crypto & vehicle database
│   │   ├── AppSettings.kt           # SharedPreferences wrapper with StateFlow properties
│   │   ├── VehicleCatalog.kt        # Country specs, fuel tank sizes, reserve limits, currencies, RSA
│   │   ├── VaultCrypto.kt           # Pure-JVM PBKDF2-HMAC-SHA256 + AES-256-GCM .pnav crypto
│   │   └── DataVaultManager.kt      # Encrypted .pnav / opt-in .pnav.json export & PNAV_SETUP / PNAV_SETUP_ENC QR sync
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
│   ├── telephony/          # Caller ID, phonebook sync, dialback & SMS auto-reject
│   │   ├── CallManager.kt             # TelecomManager answer/hangup + call state + missed-call push
│   │   └── ClusterPhonebookManager.kt # Starred contacts (0910), call log (0b10), dialback & debounced SEND_SMS
│   ├── tracking/           # GPS Ride Tracker
│   │   └── RideTracker.kt           # Speed, distance, duration, serialized JSON persistence
│   ├── wifi/               # Bajaj TFT Wi-Fi full-screen mirroring & NAV_V2 (800×480)
│   │   ├── TftWifiProtocol.kt       # Pure-Kotlin SoftAP PSK derivation, TCP 5558 codec, NAV_V2 codec, UDP 5556 chunker
│   │   ├── TftNavV2Controller.kt    # TCP 5558 NAV_V2 (0x07..0x0F): Overpass POIs, Favorites, Recents, Preview, Start Nav
│   │   ├── TftCockpitDisplayController.kt # 800×480 VirtualDisplay Presentation HUD + MediaProjection live cast
│   │   └── TftWifiMirrorManager.kt  # SoftAP Network binding (preserving cellular), TCP 5558 + UDP 5556 workers
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
- **Playlist Info Characteristic UUID**: `0510676e-6972-6565-6e69-676e4543544f` (177-byte TFT playlist page, 5 tracks/page)
- **Music Info Characteristic UUID**: `0610676e-6972-6565-6e69-676e4543544f` (111-byte TFT track/artist/album metadata + volume/playback)
- **Favorite Contacts Characteristic UUID**: `0910676e-6972-6565-6e69-676e4543544f` (151-byte top 5 starred contacts, 10-digit phone normalization + checksum)
- **Handlebar Buttons UUID**: `0a10676e-6972-6565-6e69-676e4543544f` (Notify/Indicate + Read fallback)
- **Recent Calls Characteristic UUID**: `0b10676e-6972-6565-6e69-676e4543544f` (151-byte top 5 deduplicated recent/missed call entries + checksum)

### 1. MTU Negotiation, Bonding & Serialization
- Every BLE GATT operation (descriptor CCCD writes, characteristic writes, polling reads) must serialize on a single operation callback (`onCharacteristicWrite` / `onDescriptorWrite`) with timeout protection (`WRITE_NO_RESPONSE_TIMEOUT_MS = 350L`) on the dedicated `BajajBleGatt` handler thread.
- Upon service discovery, request MTU `256` (`gatt.requestMtu(256)`). `BleProtocolSafety` enforces single-PDU limits: `MTU >= 114` for 111B `0610` Music Info and 89B V2 General Telemetry (falling back to 55B legacy when MTU is smaller), `MTU >= 154` (`FAV_RECENT_PACKET_BYTES + ATT_HEADER_BYTES`) for 151B `0910`/`0b10`, and `MTU >= 180` (`PLAYLIST_PACKET_BYTES + ATT_HEADER_BYTES`) for 177B `0510`.
- Color TFT clusters (with `0610` Music Info characteristic) require an explicit `BluetoothDevice.BOND_BONDED` link via `ACTION_BOND_STATE_CHANGED` before enabling `0a10` CCCD notifications or transmitting TBT/media/call frames.

### 2. Turn-By-Turn (TBT) Packet Structure (48 bytes)
Built in `OtcPacketBuilder.buildTbtFrame()` / `buildTbtStopFrame()`:
- `Byte 0`: Flags — `bit 0` = type (`1` active, `0` stop), `bit 4` = step distance unit (`1` = meters, `0` = km), `bit 7` = ETA PM flag (`1` = PM, `0` = AM)
- `Byte 1`: Maneuver Opcode (`66..90`, `Maneuver.kt`; `+32` blink bit for turns `< 100m` during active updates)
- `Byte 2..5`: Step distance (`fLow, fHigh, iLow, iHigh` little-endian hundredths + integer; in meters `< 999.5m`, in km `>= 999.5m`)
- `Byte 6`: ETA minute (`0..59`)
- `Byte 7`: ETA hour (`1..12` in bits `0..3`, `0` allowed on stop frame) | `(roundaboutExit << 4)` in bits `4..7`
- `Byte 8..11`: Total remaining trip distance (`fLow, fHigh, iLow, iHigh`)
- `Byte 12`: `bit 0` = total distance unit (`1` = m, `0` = km) | `bits 2..3` = GPS active (`1` when `type != 0`, `0` on stop)
- `Byte 13`: `takeMeHome` ack (`0..255` counter echoed when TFT triggers Take Me Home)
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

### 4. Handlebar Controls, Dialback, SMS Reject & Media Interception
Subscribes via CCCD on `0a10...` (and falls back to `700ms` polling when notifications aren't confirmed and the GATT queue is idle):
- Single short press / `callAccept`: Answer incoming call (`telecomManager.acceptRingingCall()`), or Play/Pause when LCD idle.
- Long press / `callReject`: Reject/End incoming, active, or outgoing call (`telecomManager.endCall()`), or Next track when LCD idle.
- Short press + hold (`<= 1000ms` after release): Previous track (`KeyEvent.KEYCODE_MEDIA_PREVIOUS`).
- **TFT Switchgear Extensions**:
  - `callRejectWithSms`: Ends ringing call + sends debounced auto-reply SMS (`SEND_SMS`, 5s debounce, configurable template) + bumps `callRejectSmsAck` on `0210`.
  - `dialSource` (`1=Favs, 2=Recents, 3=Missed`) + `dialIndex` (`0..4`) + `dialTxn`: Sticky-edge guarded over first 2–3 control frames (`dialEdgesArmed`) before calling `TelecomManager.placeCall` / `ACTION_CALL` (`CALL_PHONE`) and setting `CallState.OUTGOING_CALL`.
  - `takeMeHome`: Launches Google Maps / Waze route to saved Home via `NavigationIntentLauncher` and bumps `0110` byte `[13]` ack.
  - `missedCallGet` / `alertGet`: Re-pushes latest missed call (`0310`) or cached alert (`0410`) and bumps ACKs on `0210`.
  - `pagePlaylist` / `newPlaylistReq` / `selectPlaylistSongReq`: Streams 5-track queue/history slice (`0510`, 177B) or jumps to selected track (`skipToQueueItem` / step fallback).

### 5. TFT Full-Screen Wi-Fi Mirroring & NAV_V2 (`com.pulsarnav.app.wifi`)
For Bajaj color TFT clusters (e.g., Pulsar NS400Z):
- **SoftAP Association**: Connects via Android `WifiNetworkSpecifier` (or adopts an already-connected SoftAP Wi-Fi `Network` with gateway `192.168.4.1`) without calling `bindProcessToNetwork`, binding only mirror sockets via `network.bindSocket(...)` so cellular data routing remains functional. WPA2 passphrase can be derived automatically from the 17-char bike VIN or supplied manually.
- **Control Channel (`TCP 5558`)**: 5-byte header `[type, group, cmd, lenHi, lenLo]` (max 2048B cluster payload). Handles `SCREEN_INFO`, `SCREEN_CONTROL`, `DATE_TIME` (12h `Calendar.HOUR`), `PHONE_NAME`, `MIRRORING_STATE`, `BIKE_INFO`, `TOUCH` (`800×480`), `HANDLEBAR_INPUT` (recenter bit `0x08`), `DOC_SIZE`/`MD5`/`DATA` (e.g. album art JPEG + glovebox image previews), `STOP_NAV` (stops BLE TBT stream), and full **`NAV_V2` (`0x07..0x0F`)** interactive menus via `TftNavV2Controller`:
  - `0x07/0x08` (`GET_POI_LIST`/`DETAIL`): Overpass OSM nearby queries (routed over cellular `Network` without touching `bindProcessToNetwork`, offline search fallback if unreachable).
  - `0x09` (`GET_FAVOURITES`): Configured Home, Work & Favorite Spot (auto-geocoded if text address).
  - `0x0A/0x0B` (`GET_RECENT_TRIPS`/`DETAIL`): Merges recent NAV_V2 starts (`navV2RecentDestsJson`) and `RideTracker` trips.
  - `0x0C/0x0D/0x0E` (`ROUTE_PREVIEW`/`SET_ROUTE_PREF`/`GET_ROUTE_PREF`): Distance/ETA preview + persistent Fastest/Shortest/Eco/Avoid-Tolls & km/mi units.
  - `0x0F` (`START_NAV`): Saves destination to recents and launches Google Maps / Waze via `NavigationIntentLauncher`.
- **Video Stream (`UDP 5556`)**: `800×480` adaptive JPEG frames (`< 30,000B`, quality `25..100`) encrypted per chunk (`AES/CBC/PKCS5Padding`, zero 16B IV) with 4B length header in chunk 0 (`1006B` payload), `1008B` middle chunks, trailing 4B big-endian `CRC32`, and `3ms` inter-datagram pacing.
- **Dual Renderers**: Built-in `VirtualDisplay` + `Presentation` offline cockpit HUD (works with phone screen off) plus opt-in `MediaProjection` (`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` promoted only during live cast) for mirroring live navigation apps.

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
   - Never commit production release keystores or passwords (`keystore.properties`, `RELEASE_KEYSTORE_BASE64`, or `RELEASE_*` secrets). Configure `REQUIRE_PRIVATE_RELEASE_SIGNING=true` before private release distribution.
   - Keep `android:exported="false"` on `BikeBleForegroundService`, `android:permission="android.permission.BLUETOOTH_PRIVILEGED"` on `BluetoothIgnitionReceiver`, and `RECEIVER_NOT_EXPORTED` on runtime receivers.
   - Keep `.pnav` backups encrypted with `VaultCrypto` (PBKDF2-HMAC-SHA256 + AES-256-GCM); default QR setup codes (`PNAV_SETUP`) omit PII unless encrypted (`PNAV_SETUP_ENC`).
