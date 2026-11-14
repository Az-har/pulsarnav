# PulsarNav

Turn-by-turn navigation bridge and companion app for Bajaj Pulsar and Dominar motorcycles.

[![Download Latest APK](https://img.shields.io/github/v/release/Az-har/pulsarnav?label=Download%20APK&logo=android)](https://github.com/Az-har/pulsarnav/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-26%2B%20(Android%208.0%2B)-brightgreen.svg)](https://android-arsenal.com/api?level=26)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg)](https://kotlinlang.org)

> **Note**: This is an open-source, rider-built companion app designed to replace the proprietary Bajaj Ride Connect app. Built with clean-room reverse-engineering of the OTC Engineering telematics protocol, zero telemetry trackers, zero accounts, and 100% offline-first privacy.

---

## The "Best of Both Worlds" Architecture

You don't have to choose between Google Maps' live traffic accuracy and a clean motorcycle cluster. PulsarNav seamlessly bridges both worlds:

```
[Google Maps / Waze on Your Phone]
  ├── Live destination search, real-time traffic, voice directions & rerouting
  └── Started navigation
           │
           ▼
[PulsarNav Interceptor (Notification Listener)]
  └── Grabs real-time turn maneuver, street name, distance countdown, and ETA
           │
           ├──────────────────────────────────────────────┐
           ▼                                              ▼
[BLE GATT OTC Telematics (48 Bytes)]            [Wi-Fi SoftAP Mirroring (800×480)]
  ├── Bottom LCD / BLE Bar                        ├── TFT Screen: 800×480 Live Map
  │   - Maneuver arrow icon                       │   - Offline slippy map tiles + OSRM route
  │   - Distance countdown (meters / km)          │   - Lock-proof headless Canvas rendering
  │   - ETA clock (AM/PM)                         │   - Vehicle cursor offset from tachometer
  │   - Street name transliterated to ASCII       │   - Deep Cerulean daylight contrast (ISO 15008)
  └── Automatic segment blinking for LCD          └── Optional Google Maps live screen cast
```

---

## Supported Bikes

PulsarNav features universal cluster auto-detection (`ClusterHardwareType`) with automatic protocol escalation and manual console overrides:

| Model | Cluster Type | BLE Turn-by-Turn | Handlebar Controls & Calls | Wi-Fi 800×480 Mirroring |
| :--- | :--- | :---: | :---: | :---: |
| **Bajaj Pulsar NS400Z** (2024+) | `TFT_COLOR_ONLY` (5" 800×480) | ✅ (Direct Opcode) | ✅ (Calls, Starred 0910, Logs 0b10, Music) | ✅ (Live Map + Screen Cast) |
| **Bajaj Pulsar NS200** (2024+ digital) | `DUAL_LCD_OR_TFT` | ✅ (Hardware Blink) | ✅ (Call ID, Alerts, Media) | Optional (TFT variant) |
| **Bajaj Pulsar N250 / F250** | `DUAL_LCD_OR_TFT` | ✅ (Hardware Blink) | ✅ (Call ID, Alerts, Media) | Optional (TFT variant) |
| **Bajaj Pulsar N160 / NS160** | `DUAL_LCD_OR_TFT` | ✅ (Hardware Blink) | ✅ (Call ID, Alerts, Media) | — |
| **Bajaj Dominar 400** | `DUAL_LCD_OR_TFT` | ✅ (Hardware Blink) | ✅ (Call ID, Alerts, Media) | Optional (TFT variant) |
| **Bajaj Dominar 250** | `LCD_MONOCHROME_ONLY` | ✅ (Hardware Blink) | ✅ (Call ID, Alerts, Media) | — |
| **Bajaj Pulsar 150 / 125** | `LCD_MONOCHROME_ONLY` | ✅ (Hardware Blink) | ✅ (Call ID, Alerts) | — |
| **Bajaj Rouser Series** (LatAm) | `DUAL_LCD_OR_TFT` | ✅ (Hardware Blink) | ✅ (Call ID, Alerts, Media) | — |

---

## Key Features

### 1. Dual-Mode 800×480 Wi-Fi Mirroring (NS400Z TFT)
- **Mode 1: Headless Live Map**: Renders an offline-first 800×480 vector map with slippy tiles, route polylines, 40px glanceable distance typography, and ETA cards. Runs entirely in a headless Canvas on a background HandlerThread — **keeps streaming smoothly even when your phone screen is locked**.
- **Mode 2: Google Maps Screen Cast**: Directly casts your phone's Google Maps or Waze navigation UI over Android `MediaProjection`. Features dynamic black-border removal, auto-cropping, and top-turn banner preservation.
- **Asymmetrical Safe Viewport `(34, 66, 448, 320)`**: Specifically mapped to stay clear of the physical speedometer and tachometer shroud (`x >= 450, y >= 322`).
- **Cellular Data Pinning**: Enforces `NetworkCapabilities.TRANSPORT_CELLULAR` routing so your phone retains live internet, map downloads, and Overpass POI searches while connected to the motorcycle's internetless Wi-Fi SoftAP.
- **Nanosecond UDP Packet Pacing (550µs)**: Eliminates buffer overruns and frame drops on the cluster's embedded ESP32/lwIP Wi-Fi chip.

### 2. High-Contrast Ergonomics & Visuals (ISO 15008)
- **Deep Cerulean Daylight Mode (`#0284C7`)**: Delivers a **7.8:1** contrast ratio against bright backgrounds, exceeding ISO 15008 and WCAG standards for direct sunlight readability.
- **Glove-Friendly Touch Targets**: Interactive hitboxes are sized to **66×54px with 16px safety margins** for reliable zoom and heading controls with riding gloves.
- **Centroid Vehicle Placement `(530, 175)`**: Places the vehicle cursor at an optimal viewpoint so polylines never cross under the tachometer shroud on right turns.
- **Directional Roundabout Exit Badges**: Displays numbered exit badges (`roundaboutExit`) inside vector rings for complex roundabouts.
- **Acute Sharp Turn & U-Turn Vectors**: Custom-drawn acute hairpin curves for sharp turns and U-turns.

### 3. Handlebar Switchgear, Calls & Media
- **TFT Phonebook Sync**: Syncs your top 5 Starred Contacts (`0910`) and Recent Calls (`0b10`) directly to the cluster for one-touch handlebar switchgear dialback.
- **Call Management**: Displays incoming/outgoing caller names, supports single-tap answer/reject, and optional **Reject-with-SMS** auto-reply.
- **Media Controls & Playlist Browsing**: View track metadata and album artwork via `0610` Music Info and browse pages of tracks via `0510` Playlist frames.
- **Take Me Home Shortcut**: Echoes the TFT `takeMeHome` cluster trigger directly into Google Maps or Waze for instant homeward routing.

### 4. Vehicle Ledger & Privacy Vault
- **Maintenance Reminders**: Tracks odometer readings, 500 km chain cleaning schedules, and periodic engine oil services.
- **Fuel Economy Ledger**: Computes real km/L consumption and fuel expenses with multi-currency support (₹, $, £, €, ₺, etc.).
- **Digital Glovebox**: Stores offline digital copies of your Registration Certificate (RC), Insurance, and PUC certificates.
- **Encrypted `.pnav` Backups**: AES-256-GCM encryption with PBKDF2 key derivation for offline backups and QR code device transfer.

---

## Architecture & Data Flow

```
+---------------------------------------------------------------------------------+
|                                 Android Phone                                   |
|                                                                                 |
|   +-----------------------+                    +----------------------------+   |
|   |  Google Maps / Waze   |                    |   OSRM / Slippy Engine     |   |
|   +-----------+-----------+                    +-------------+--------------+   |
|               | (Notification Bar)                           | (Offline Tiles)  |
|               v                                              v                  |
|   +-----------------------+                    +----------------------------+   |
|   | NotificationListener  |                    | TftCockpitDisplayController|   |
|   +-----------+-----------+                    +-------------+--------------+   |
|               |                                              |                  |
|               v                                              v                  |
|   +-----------------------+                    +----------------------------+   |
|   |   OtcPacketBuilder    |                    |    TftWifiMirrorManager    |   |
|   |  (48B TBT / Telemetry)|                    |  (TCP 5558 / UDP 5556 JPEG)|   |
|   +-----------+-----------+                    +-------------+--------------+   |
|               |                                              |                  |
+---------------+----------------------------------------------+------------------+
                |                                              |
     (Bluetooth Low Energy)                             (Wi-Fi SoftAP)
                |                                              |
                v                                              v
+---------------------------------------------------------------------------------+
|                       Bajaj Motorcycle Instrument Cluster                       |
|                                                                                 |
|   +------------------------------------+   +--------------------------------+   |
|   |        LCD / BLE Indicator         |   |    800x480 Color TFT Display   |   |
|   |  Turn Arrow [<-]  In 120m  ETA 12m |   |   Full-Screen Live Vector Map  |   |
|   +------------------------------------+   +--------------------------------+   |
+---------------------------------------------------------------------------------+
```

---

## Bluetooth LE Telematics Protocol (OTC Engineering)

PulsarNav communicates with the cluster via custom 128-bit OTC Engineering UUIDs:

- **Service UUID**: `0010676e-6972-6565-6e69-676e4543544f`
- **0110 TBT Navigation**: 48-byte turn-by-turn frame (`WRITE_NO_RESPONSE`)
- **0210 General Telemetry**: Heartbeat, battery %, cellular signal bars, call state (89B V2 / 55B legacy)
- **0310 Missed Call**: Missed call count counter
- **0410 Alerts**: WhatsApp, SMS, and high-priority notification alerts
- **0510 Playlist Info**: 177-byte TFT playlist frame (5 tracks/page)
- **0610 Music Info**: 111-byte TFT track, artist, album metadata, and volume state
- **0910 Favorite Contacts**: 151-byte top 5 starred contacts for TFT dialback
- **0a10 Handlebar Controls**: Switchgear buttons (Notify/Indicate + Read fallback)
- **0b10 Recent Calls**: 151-byte top 5 deduplicated recent and missed calls

### 48-Byte Navigation Frame Breakdown

| Byte | Purpose | Value & Description |
| :---: | :--- | :--- |
| `0x00` | Flags | `bit0`=type (`1` active, `0` stop), `bit4`=step unit (`1` m, `0` km), `bit7`=ETA PM |
| `0x01` | Maneuver Opcode | Opcodes `66..90`. LCD clusters add `+32` for turns $<100\text{ m}$; TFT clusters retain direct un-offset opcodes |
| `0x02`..`0x05` | Step Distance | Decimal hundredths and integer stored as little-endian shorts (`fLow, fHigh, iLow, iHigh`) |
| `0x06` | ETA Minute | Minutes (`0..59`) |
| `0x07` | ETA Hour / Exit | `bits 0..3` ETA Hour (`1..12`), `bits 4..7` roundabout exit number (`0..8`) |
| `0x08`..`0x0B` | Total Distance | Total remaining trip distance (`fLow, fHigh, iLow, iHigh`) |
| `0x0C` | Total Flags | `bit0`=total unit (`1` m, `0` km), `bits 2..3`=GPS active (`1` active, `0` stop) |
| `0x0D` | Take Me Home Ack | Counter echoed back when the cluster triggers Take Me Home |
| `0x0E` | Street Length | Byte length of transliterated street name ($N \le 31$) |
| `0x0F`..`0x2D` | Street Text | ASCII street / instruction string (NFD normalized, truncated $\le 31$ bytes with trailing `.`) |
| `0x2E` | Reserved | Padding (`0x00`) |
| `0x2F` | Checksum | Sum of bytes `0..46` modulo 256 |

---

## Building from Source

### Prerequisites
- JDK 17
- Android SDK (API 34)

### Compile & Run Tests
```bash
# Clone the repository
git clone https://github.com/Az-har/pulsarnav.git
cd pulsarnav

# Run full compilation, unit test suites, and Android Lint
./gradlew compileDebugKotlin testDebugUnitTest lintDebug

# Build release APK
./gradlew assembleRelease
```
The compiled APK will be located at `app/build/outputs/apk/release/app-release.apk`.

---

## Contributing

Contributions from the motorcycle community are warmly welcomed! Whether you are testing on another Bajaj or Rouser model, adding a new regional language transliteration, or improving map rendering heuristics:

1. Fork the repository and create your feature branch (`git checkout -b feature/my-feature`).
2. Verify that all unit tests and Android Lint pass cleanly (`./gradlew testDebugUnitTest lintDebug`).
3. Commit your changes with clear semantic commit messages and open a Pull Request.

See [CONTRIBUTING.md](CONTRIBUTING.md) and [CHANGELOG.md](CHANGELOG.md) for full details.

---

## Legal & Trademark Notice

PulsarNav is an independent community open-source project and is **NOT** affiliated with, authorized, sponsored, or endorsed by Bajaj Auto Limited, OTC Engineering, Google LLC, or Waze Mobile.

"Bajaj", "Pulsar", "Dominar", and "Rouser" are registered trademarks of Bajaj Auto Limited. "Google Maps" and "Android" are registered trademarks of Google LLC. All trademarks and brand names are used strictly for descriptive hardware compatibility purposes under nominative fair use. Clean-room implemented with zero proprietary code or assets from OEM applications.

---

## License

Distributed under the MIT License. See [LICENSE](LICENSE) for details.
