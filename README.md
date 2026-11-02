# PulsarNav

**Open-Source Motorcycle Companion & Navigation Bridge for Bajaj Pulsar & Dominar**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-blue.svg)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-Android%2014%20(API%2034)-green.svg)](https://developer.android.com)
[![Protocol](https://img.shields.io/badge/Protocol-Clean--Room%20Interoperability-green.svg)](#legal--trademark-disclaimer)

PulsarNav is an offline-first, zero-telemetry Android companion application designed to replace proprietary motorcycle apps. It intercepts real-time navigation directions from **Google Maps** and **Waze**, transmitting turn-by-turn maneuvers, distance countdowns, and caller ID over Bluetooth Low Energy (BLE) to the digital instrument console of your motorcycle.

> [!IMPORTANT]
> ### Legal & Trademark Disclaimer
> **PulsarNav is an independent, community-driven open-source software project.**
> * It is **NOT** affiliated with, authorized, sponsored, or endorsed by **Bajaj Auto Limited**, **OTC Engineering**, **Google LLC**, **Waze Mobile**, or any of their affiliates.
> * *"Bajaj"*, *"Pulsar"*, *"Dominar"*, *"Rouser"*, and related brand names, logos, and vehicle models are registered trademarks of Bajaj Auto Limited.
> * *"Google"*, *"Google Maps"*, and *"Android Auto"* are registered trademarks of Google LLC.
> * *"Waze"* is a registered trademark of Waze Mobile / Google LLC.
> * All trademark references in this project are strictly nominative fair use to describe software compatibility and interoperability under Section 52(1)(ab) of the Indian Copyright Act, Section 1201(f) of the U.S. DMCA, and EU Directive 2009/24/EC.
> * This repository contains **NO** proprietary source code, decompiled binaries, or copyright-infringing assets from OEM applications. All protocols were implemented independently from clean-room Bluetooth telematics packet analysis.

---

## Supported Motorcycles

Compatible with Bajaj and Rouser motorcycles equipped with the **OTC Engineering Bluetooth Connectivity Cluster**:

* **Bajaj Pulsar NS200** (2024+ Digital Bluetooth Console)
* **Bajaj Pulsar N250 / F250**
* **Bajaj Pulsar NS160 / NS125**
* **Bajaj Dominar 400 / Dominar 250**
* **Bajaj Rouser NS200 / N250** (Latin America)

---

## Architecture & How It Works

```mermaid
flowchart TD
    subgraph NavigationSources["Navigation Sources"]
        GM["Google Maps (Two-Wheeler Mode)"]
        WZ["Waze Navigation"]
        AA["Open Headunit / Android Auto"]
    end

    subgraph PulsarNavApp["PulsarNav Companion (Android)"]
        NL["GoogleMapsNotificationListener\n(Intercepts TBT & WhatsApp Alerts)"]
        CM["CallManager & MediaControlManager\n(Caller ID & Handlebar Interception)"]
        PB["OtcPacketBuilder\n(48-Byte Telematics Frame)"]
        BM["BajajBleManager\n(GATT Keep-Alive & Characteristic Writes)"]
        DV["DataVault & Local Storage\n(Fuel, Maintenance, Documents, ODO)"]
    end

    subgraph Motorcycle["Motorcycle Hardware"]
        CL["Digital LCD/TFT Instrument Cluster\n(Turn Arrows, Distance, Caller ID)"]
        HB["Handlebar Control Switch\n(Accept/Reject Call, Media Controls)"]
    end

    NavigationSources -->|Android Notification Stream| NL
    NL -->|Parsed Maneuvers & Distances| PB
    CM -->|Incoming Call State & Contacts| PB
    HB -->|Characteristic Notification (0x0A)| CM
    PB -->|48-Byte OTC Packet| BM
    BM -->|BLE GATT Writes (0x01 / 0x02 / 0x03)| CL
```

1. **Dual Navigation Engine**: Intercepts ongoing turn instructions from Google Maps (`com.google.android.apps.maps`) or Waze (`com.waze`).
2. **Real-Time Parser**: Extracts maneuver types, distances (`m`, `km`, `ft`, `mi`), street names, and arrival times.
3. **48-Byte OTC Telematics Encoder**: Formats navigation, heartbeat, and caller data into the exact OTC Engineering telematics frame.
4. **Bluetooth GATT Link**: Writes packets to the bike cluster with adaptive pacing and error recovery.
5. **Handlebar Controls**: Listens to handlebar button events via characteristic `0x0A` to answer/reject calls or control media playback.

---

## Reverse-Engineered Protocol Specification

### Bluetooth GATT Service & Characteristics

* **Service UUID**: `0010676e-6972-6565-6e69-676e4543544f` (`otcEngineering`)
* **TBT Navigation Characteristic**: `0110676e-6972-6565-6e69-676e4543544f` (Write without response / Write with response)
* **General Status / Heartbeat Characteristic**: `0210676e-6972-6565-6e69-676e4543544f` (Write without response)
* **Missed Call & Alerts Characteristic**: `0310676e-6972-6565-6e69-676e4543544f` (Write without response)
* **Handlebar Button Characteristic**: `0a10676e-6972-6565-6e69-676e4543544f` (Notify)

### 48-Byte TBT Packet Structure

| Byte Offset | Field | Description |
| :--- | :--- | :--- |
| `0x00` | Header | Fixed packet sequence identifier (`0x01`) |
| `0x01` – `0x06` | Reserved | Padding (`0x00`) |
| `0x07` | **Maneuver Opcode** | Turn arrow icon opcode (see opcode table below) |
| `0x08` – `0x0B` | **Distance** | 32-bit Big-Endian unsigned integer in meters |
| `0x0C` – `0x0D` | ETA | 16-bit Big-Endian unsigned integer in minutes |
| `0x0E` | Street Length | Length $N$ of sanitized street name ($\le 31$) |
| `0x0F` – `0x0F + N` | Street Name | ASCII string (accents and special characters stripped) |
| `0x0F + N` – `0x2E` | Padding | Zero padding up to byte 46 |
| `0x2F` (`Byte 47`) | **Checksum** | $\sum_{i=0}^{46} \text{byte}[i] \pmod{256}$ |

### Maneuver Opcodes

| Opcode (Dec) | Opcode (Hex) | ASCII | Maneuver Name |
| :--- | :--- | :--- | :--- |
| `73` | `0x49` | `'I'` | Turn Left |
| `74` | `0x4A` | `'J'` | Turn Right |
| `67` | `0x43` | `'C'` | Turn Slight Left |
| `68` | `0x44` | `'D'` | Turn Slight Right |
| `69` | `0x45` | `'E'` | Turn Sharp Left |
| `70` | `0x46` | `'F'` | Turn Sharp Right |
| `71` | `0x47` | `'G'` | Continue Straight |
| `72` | `0x48` | `'H'` | Destination Reached |
| `75` | `0x4B` | `'K'` | Ramp Left |
| `76` | `0x4C` | `'L'` | Ramp Right |
| `78` | `0x4E` | `'N'` | Roundabout Right |
| `79` | `0x4F` | `'O'` | U-Turn Left |
| `80` | `0x50` | `'P'` | U-Turn Right |
| `81` | `0x51` | `'Q'` | Fork Left |
| `82` | `0x52` | `'R'` | Fork Right |
| `85` | `0x55` | `'U'` | Roundabout Left |
| `86` | `0x56` | `'V'` | Merge |
| `88` | `0x58` | `'X'` | Keep Right |
| `90` | `0x5A` | `'Z'` | Keep Left |
| `66` | `0x42` | `'B'` | Off-route / Rerouting |

---

## Application Features

### 1. Navigation & Headunit Integration
* **Navigation Provider Choice**: Select either **Google Maps** or **Waze** as your default navigation app.
* **Open Headunit & Android Auto Quiet Mode**: If using a dedicated secondary display (e.g. Open Headunit / Headunit Reloaded for Android Auto), enable the modular feature toggle **"Skip Cluster Nav with Headunit"** to silence cluster turn arrows and keep the bike console distraction-free.
* **1-Tap Quick Destinations**: Configurable 1-tap navigation buttons for Home, Work, and Nearest Petrol Station, automatically routing through your chosen navigation app.
* **Two-Wheeler Google Maps Routing (`mode=l`)**: Routes via motorcycle-friendly paths and accurate two-wheeler ETAs.

### 2. Vehicle Catalog & Multi-Country Localization
* **Custom Vehicle Profiles**: Onboarding and account setup allows selecting your specific motorcycle model (Pulsar NS200, N250, F250, NS160, NS125, Dominar 400, Dominar 250, Rouser).
* **International Localization**: Tailored for India, Colombia, Mexico, Peru, Argentina, Philippines, Egypt, Nepal, Bangladesh, and Turkey.
* **Model-Specific Tire Pressures & Tank Specs**: Shows exact solo and pillion tire pressures (PSI), tank capacity (e.g., 12.0L for NS200), and reserve trigger thresholds.
* **Localized Emergency Services & Currency**: Emergency SOS assistance numbers (112, 123, 911, etc.) and currency symbols (₹, $, ₱, S/., रु, ৳, ₺) adapt automatically to the selected country.

### 3. Visual Hardware & Permission Status
* **Top Status Bar**: Provides live visual feedback on connectivity, Bluetooth state, and GPS status.
* **Permissions Pill**: Displays a **"PERMS NEEDED"** visual badge when required permissions are missing, allowing 1-tap permission granting without digging through OS settings.

### 4. Maintenance, Service & Issues Checklist
* **Flexible Periodic Service Tracking**: Custom first service (e.g. 500 km for new bikes) and recurring intervals. Target ODO can be edited at any time to accommodate early or missed services.
* **Chain Clean & Lube Countdown**: 500 km O-ring chain maintenance reminder with 1-tap logging.
* **Bike Issues & Repair Checklist**: Built-in todo tracker for mechanical symptoms, pending repairs, and service fixes with category labels and resolution toggles.

### 5. Fuel Economy & Localized Pricing
* **Refueling Logger**: Logs odometer, liters pumped, and total cost with automatic mileage calculation ($\text{km/l}$).
* **Editable Fuel Price**: Prominent, 1-tap editable fuel price card supporting localized rates and currencies.
* **Estimated Range (DTE)**: Dynamic Distance-To-Empty calculation based on current tank level and historical efficiency.

### 6. Digital Glovebox
* **Document Vault**: Store vehicle Registration Certificate (RC), Comprehensive Insurance, and Pollution (PUC) details.
* **Private Storage & Native Viewer**: Documents are copied to private internal storage (`context.filesDir/documents/`) with built-in PDF and image viewing via secure `FileProvider`.

### 7. Data Vault & Migration
* **Full Metrics Backup (`.pnav` / `.pnav.json`)**: Complete export and restore of trip histories, refueling logs, maintenance records, and bike configuration.
* **Self-Healing Type Safety**: Resilient JSON parsing and SharedPreferences type-coercion prevents backup corruption across versions.
* **QR Code Phone Migration**: Display or scan an encrypted QR code to transfer settings and bike profiles instantly between phones.
* **Import Protection**: Confirmation dialog guards against unintended state overwrite when opening external backup files.

### 8. Display Themes & Glove-Friendly Ergonomics
* **Dark & Light Modes**: Full Obsidian Dark Mode and daytime crisp Light Mode.
* **6 Accent Themes**: Wine Red, AMOLED Pure Black, Stealth Mono, Neon Amber, Hyper Mint, and Liquid Blue.
* **Glove Mode Target Design**: Touch targets $\ge 54\text{ dp}$ with tactile haptic feedback engineered for motorcycle riding gloves.

### 9. Telephony & Handlebar Controls
* **Caller ID Transmission**: Resolves contact names and phone numbers, displaying incoming call alerts on the instrument cluster.
* **Handlebar Button Interception**:
  * *Incoming Call*: Single click (<750ms) to accept call; Long press (≥750ms) to reject call via `TelecomManager`.
  * *Active Call*: Long press (≥750ms) to end call.
  * *Riding (No Call)*: Single click for Play/Pause, Long press for Next Track.
* **WhatsApp Alerts**: Displays sender names on cluster when new messages arrive.

---

## Security & Privacy (OWASP MASVS Hardened)

* **100% Offline-First**: Zero remote HTTP servers, zero WebViews, zero external tracking or analytics SDKs.
* **Least-Privilege FileProvider**: Exposed paths are strictly confined to `documents/` and `vault/` subdirectories (`path="."` removed).
* **Local Data Protection**: `android:allowBackup="false"` prevents unauthorized physical extraction of private medical and vehicle data via ADB.
* **Android 14+ FGS Compliance**: Declares `foregroundServiceType="connectedDevice|location"`, dynamically combining `TYPE_LOCATION` only when location permission is active.
* **ProGuard / R8 Protected**: Code shrinking and obfuscation rules configured for production release bundles.

---

## Project Structure

```text
pulsarnav/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # Permissions, receivers, FGS & intent filters
│   │   ├── java/com/pulsarnav/app/
│   │   │   ├── ble/                     # BajajBleManager (GATT lifecycle & queues)
│   │   │   ├── data/                    # AppSettings, DataVaultManager, VehicleCatalog
│   │   │   ├── location/                # ParkingManager (auto-park GPS fix)
│   │   │   ├── maintenance/             # FuelLogManager, MaintenanceManager
│   │   │   ├── media/                   # MediaControlManager (handlebar playback)
│   │   │   ├── model/                   # Maneuver models & enums
│   │   │   ├── parser/                  # GoogleMapsNotificationParser, ManeuverMatcher
│   │   │   ├── protocol/                # OtcPacketBuilder (48-byte telematics frames)
│   │   │   ├── receiver/                # BluetoothIgnitionReceiver (ACL event auto-start)
│   │   │   ├── service/                 # GoogleMapsNotificationListener, BikeBleForegroundService
│   │   │   ├── telephony/               # CallManager (Caller ID & TelecomManager actions)
│   │   │   ├── tracking/                # RideTracker (GPS speed, distance & trip logging)
│   │   │   └── ui/                      # MainActivity (Cockpit, Fuel, Support, Account)
│   │   └── res/                         # Vector icons, themes, layouts & file paths
│   ├── build.gradle.kts                 # Compile & Target SDK 34, dependencies
│   └── proguard-rules.pro               # ProGuard/R8 class preservation rules
└── README.md
```

---

## Building from Source

### Prerequisites
* JDK 17+ (Microsoft OpenJDK 17 or Eclipse Temurin)
* Android SDK Platform 34 & Build-Tools 34.0.0
* Android Studio Hedgehog or newer (optional for GUI builds)

### Command-Line Build (APK)
```bash
# Clone the repository
git clone https://github.com/nikhilmaity00/pulsarnav.git
cd pulsarnav

# Build release APK
./gradlew assembleRelease
```
The compiled APK will be located at:
`app/build/outputs/apk/release/app-release.apk`

### Command-Line Build (Google Play AAB Bundle)
```bash
./gradlew bundleRelease
```
The compiled bundle will be located at:
`app/build/outputs/bundle/release/app-release.aab`

---

## Installation & First-Time Setup

1. **Install APK**: Install `app-release.apk` onto your Android phone.
2. **First-Run Setup**:
   * Select your motorcycle model (e.g. `Pulsar NS200`) and country (`India`).
   * Choose your preferred navigation app (`Waze` or `Google Maps`).
3. **Grant Permissions**:
   * Tap the **PERMS NEEDED** badge or notification setup card to grant Bluetooth, Location, and Notification Listener access.
4. **Pair with Motorcycle**:
   * Turn on your bike's ignition key.
   * Tap **Scan** in PulsarNav. Select your cluster when discovered.
   * The connection status dot turns green.
5. **Start Navigation**:
   * Tap **Start Ride with Waze** (or Google Maps) or use quick navigation buttons.
   * Put your phone in your pocket or handlebar mount. Turn instructions and distance countdowns will appear on your motorcycle's instrument cluster in real-time.

---

## License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for complete details.
