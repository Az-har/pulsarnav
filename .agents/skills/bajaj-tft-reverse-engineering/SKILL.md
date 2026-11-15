---
name: bajaj-tft-reverse-engineering
description: Safe protocol discovery, packet analysis, and Android BLE/Wi-Fi architecture guidelines for adding support for Bajaj TFT instrument clusters (such as the Pulsar NS400Z 5-inch TFT) to PulsarNav. Use when inspecting new cluster GATT/Wi-Fi protocols, analyzing OEM companion APKs/HCI logs, adding BLE bonding or switchgear mappings, or designing Wi-Fi map mirroring without risking cluster bricking.
---

# Bajaj TFT Cluster Reverse Engineering & Porting Guide

Use this runbook when extending PulsarNav beyond monochrome OTC LCD consoles (`0010676e-...`) to newer color TFT motorcycle displays (e.g., Pulsar NS400Z TFT) that add BLE bonding, updated switchgear, digital document sync, and Wi-Fi map mirroring.

## 1. Cluster Safety & Anti-Brick Guardrails

Never guess or fuzz unknown endpoints on a TFT console (unlike segmented LCDs, TFT units run embedded OS/MCU firmware with writable flash):

1. **Passive inspection first:** Collect GATT inventories (nRF Connect), Android Bluetooth HCI snoop logs (`btsnoop_hci.log`), Wi-Fi captures, and OEM companion APK inspection before sending any packet to a live bike.
2. **Never blind-write unknown characteristics or services:** Only write to characteristics whose role and frame format are verified from official companion app code or captured traffic.
3. **Never touch OTA, DFU, bootloader, UDS/OBD, or NVM-config services:** Ignore any Nordic DFU, MCUboot, firmware update, calibration, or factory-diagnostic UUIDs.
4. **Respect document/flash quotas:** Before implementing DL/RC/Insurance transfer to the cluster, verify exact file size caps, image/PDF compression, chunk headers, and storage cleanup commands.
5. **Recoverability check:** Keep test payloads minimal, single-frame, and reversible; verify checksum rules before testing on hardware so a bad runtime packet can only be ignored or cleared via an ignition cycle.

## 2. Phase 1 — Discovery Workflow

### Step A: Capture Live Device & GATT Surface (Read-Only)
1. Pair or scan the cluster via nRF Connect (and capture Android's Bluetooth HCI Snoop Log during an official *Bajaj Ride Connect* session if available).
2. Record:
   - Exact advertised BLE device name(s), manufacturer data, and service UUIDs.
   - Whether Bluetooth Classic pairing/passkey bonding (BR/EDR / HFP / A2DP / AVRCP / SPP) happens alongside BLE.
   - All GATT Service/Characteristic/Descriptor UUIDs and properties (`READ`, `WRITE`, `WRITE_NO_RESPONSE`, `NOTIFY`, `INDICATE`), plus negotiated MTU.
   - Confirm whether the legacy OTC service (`0010676e-6972-6565-6e69-676e4543544f`) is present, extended, or replaced.

### Step B: Inspect Updated OEM APK / Bundles
Use headless inspection scripts under `research/scripts/` (e.g., `check_skus.js`, `inspect_sku_registry.js`, `inspect_protocol_selector.js`, `dump_on_services_discovered.js` patterns) against local OEM DEX/JS bundles:
- **Never commit proprietary OEM binaries** (`.apk`, `.dex`, `.bundle`, `.bundle.js`, `.smali` are gitignored in `.gitignore`).
- Identify:
  1. New vehicle SKU entries and protocol selector branches for the TFT model.
  2. BLE auth/challenge handshake, bonding requirements, heartbeat/keepalive, and checksum formulas.
  3. Updated TBT / telemetry / call / alert frame layouts and switchgear button byte opcodes (separating cluster menu buttons from call/media buttons).
  4. Wi-Fi pairing & streaming flow: how BLE exchanges Wi-Fi SSID/PSK/Wi-Fi Direct (`WifiP2pManager` / `WifiNetworkSpecifier`) peer parameters and TCP/UDP/RTSP ports.
  5. Map mirroring payload format (e.g., H.264/HEVC `VirtualDisplay` frames, MJPEG/PNG bitmaps, or vector/tile navigation packets) and how screen-off Android navigation works.

## 3. Phase 2 — Designing Dual LCD + TFT Support in PulsarNav

### 1. Protocol Abstraction
- Do **not** mix TFT-only logic into `OtcPacketBuilder` or hardcode a second monolithic path inside `BajajBleManager`:
  - Abstract cluster communication behind a transport/protocol strategy selected from discovered GATT services and `BikeModelProfile` (for example `"ns400z"` in `VehicleCatalog.kt` and `BIKE_NAME_PATTERNS` / `BleProtocolSafety` in `BajajBleManager.kt`).
  - Keep pure packet encoders/decoders stateless in `com.pulsarnav.app.protocol` so they remain 100% JVM unit-testable without Android Context.

### 2. Milestone Rollout
1. **Milestone A — Core BLE Link & Telephony/TBT:**
   - Implement BLE bonding (`createBond()` / `ACTION_BOND_STATE_CHANGED` handling) if encrypted GATT characteristics are required.
   - Sync time, battery/signal telemetry, caller ID, call accept/reject, media metadata, and non-conflicting handlebar events.
   - Arbitrate Android HFP/AVRCP vs. BLE switchgear so pressing handlebar buttons does not double-trigger actions or interfere with the TFT menu cube.
2. **Milestone B — Wi-Fi Map Mirroring:**
   - Manage `WifiNetworkSpecifier` / `WifiP2pManager` cleanly inside a dedicated foreground transport manager (`CHANGE_WIFI_STATE`, `ACCESS_WIFI_STATE`, `NEARBY_WIFI_DEVICES` with proper manifest flags).
   - Note: `GoogleMapsNotificationListener` only receives notification text and small maneuver bitmaps — it cannot mirror full Google Maps tiles while the phone screen is off. Supporting screen-off visual map mirroring requires either an embedded map/routing renderer (e.g., offline vector tiles + open routing engine or Navigation SDK) or `MediaProjection` when screen-on; gate any network/map dependencies transparently to preserve PulsarNav's offline-first posture.
3. **Milestone C — Document Storage Sync (Optional):**
   - Reuse bounded stream reading and validated formats matching exact cluster limits; never push unvalidated files.

## 4. Verification Before Hardware Testing

Every protocol change must pass pure-JVM verification before testing on a motorcycle:
1. Add unit tests under `app/src/test/java/com/pulsarnav/app/` covering packet byte layout, endianness, text truncation, checksums, and state-machine transitions.
2. Run Gradle verification:
   `./gradlew.bat --no-daemon testDebugUnitTest assembleRelease lintDebug --console=plain`
3. Verify `minifyReleaseWithR8` keeps any new protocol models/enums in `app/proguard-rules.pro`.
