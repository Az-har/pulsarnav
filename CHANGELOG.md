# Changelog

All notable changes to the PulsarNav project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.3.18] - 2026-11-15

### Added
- **Navigation Hub Bottom Sheet & Seamless Google Maps Sharing**:
  - Modern Material 3 bottom sheet (`bottom_sheet_navigation_hub.xml`) opens automatically upon sharing a destination, coordinates, or pin from Google Maps (`Intent.ACTION_SEND` / `ACTION_VIEW`), or tapping the persistent search bar.
  - Live cellular destination resolver with indeterminate progress indicator, destination title, distance, and ETA preview.
  - 5 quick destination shortcut chips (`Home`, `Work`, `Petrol Pump`, `Hospital`, `Bajaj Service Center`) for instant one-touch routing.
  - Persistent Recent Trips history with 1-tap re-routing and automatic JSON-backed persistence (`nav_v2_recent_dests_json`).
  - Action controls: primary "Route on TFT Cluster (Live Map)", secondary "Open in Maps", and "Stop Route".
- **Active Navigation Dashboard Card**:
  - Dynamic Material card on home screen showing live next-turn maneuver, distance, ETA, live status badge (`TFT LIVE` / `REROUTING`), manual "Reroute" trigger, and "Stop Route" controls.
- **Smart Auto-Rerouting (Google Maps Parity)**:
  - Tightened off-route deviation threshold to 35m for rapid detection in dense urban streets.
  - Turn overshoot / missed turn detector in `computeRouteProgress()` triggers recalculation when riding past an intersection.
  - Fast 3.5s OSRM recalculation cooldown over cellular data.
  - Cluster visual feedback: turn card border turns amber gold (`#F59E0B`) with `"REROUTING..."` and `"Recalculating path..."`.

### Fixed
- **TFT Map Route Polyline Rendering**:
  - Eliminated flawed diagonal bounding box clipping and point skipping that broke or omitted the blue route line across screen boundaries.
  - Full-screen hardware-accelerated Skia path rendering with 16px contrast casing and 10px high-contrast core.

---

## [1.3.17] - 2026-11-14

### Removed
- **Uncalled Methods & Properties**: Cleaned 54 lines of unreferenced code across 8 files without dead comments:
  - Removed `hasInternetPath` in `CellularNetworkHttp.kt`.
  - Removed `cycleTftCastLayoutMode` in `AppSettings.kt`.
  - Removed uncalled `startLiveMapMirroring` and `startCockpitHudMirroring` in `TftWifiMirrorManager.kt`.
  - Removed uncalled `setPreviewPaused`, `startCockpitHud`, `isDaylightHighContrast`, and unreferenced `mainHandler` in `TftCockpitDisplayController.kt`.
  - Removed dead `val wasWaiting` and unreachable `else if (wasWaiting)` branch in `BajajBleManager.kt` bond receiver.
  - Removed unused constants `UDP_TX_PACING_MS`, `TYPE_PHONE_PUSH`, and `SCREEN_MODE_CLUSTER` in `TftWifiProtocol.kt`.
  - Removed unused dimension `btn_touch_target_min` in `dimens.xml`.

---

## [1.3.16] - 2026-11-14

### Added
- **TFT TBT Maneuver Opcode Gating**: Added `isTft: Boolean = false` to `OtcPacketBuilder.buildTbtFrame()`. For LCD consoles, turns $< 100\text{ m}$ add 32 (`icon += 32`) to trigger hardware segment flashing; for TFT clusters (NS400Z), opcodes 66..90 index sprite sheets directly without offset, preventing corrupted icon lookups.
- **Cluster Hardware Metadata**: Introduced `enum class ClusterHardwareType { LCD_MONOCHROME_ONLY, TFT_COLOR_ONLY, DUAL_LCD_OR_TFT }` in `VehicleCatalog.kt` to model digital cluster trims (NS400Z as `TFT_COLOR_ONLY`, NS200/N250 as `DUAL_LCD_OR_TFT`).
- **Sharp Turn Vector Arrows**: Decoupled `TURN_SHARP_LEFT` and `TURN_SHARP_RIGHT` from 90° right angles in `TftScreenCastTransform.kt`, drawing acute hairpin hooks with directional arrowheads.

---

## [1.3.15] - 2026-11-14

### Performance & Memory
- **CPU Cache Optimization**: Refactored `detectNonBlackBounds()` in `TftScreenCastTransform.kt` from column-major pixel sampling to row-major sequential scanning (`getPixels`), eliminating L1/L2 cache misses on high-resolution phone captures.
- **TCP Dynamic Buffer Accumulator**: Replaced static 8 KB socket buffer in `TftWifiMirrorManager.kt` with auto-expanding byte buffer (up to 32 KB) to prevent truncation of large NAV_V2 POI lists and multipart document responses.
- **Cellular Pin Release**: Guaranteed cleanup of `CellularNetworkHttp.releaseCellularPin()` on ride termination in `BikeBleForegroundService.onDestroy()`.
- **LCD UI Safety Guards**: Disabled TFT Wi-Fi settings, document glovebox sync, and cluster screen debugger cards when connected to monochrome LCD consoles.

---

## [1.3.14] - 2026-11-14

### Visual Ergonomics & Safety
- **Asymmetrical Motorcycle Viewport**: Bound screen cast safe zone to `(34, 66, 448, 320)` to ensure maps are completely clear of the physical speedometer and tachometer shroud (`x >= 450, y >= 322`).
- **Roundabout Exit Indicators**: Added numeric exit badges inside vector display rings (`roundaboutExit`) for complex roundabouts.
- **Companion Arrow Vector Badge**: Rendered crisp anti-aliased vector arrow badges on the turn preview card.

---

## [1.3.13] - 2026-11-14

### Visual Ergonomics & Glanceability
- **High-Contrast Daylight Palette**: Replaced low-contrast cyan (`#38BDF8`, 2.15:1) with Deep Cerulean (`#0284C7`, 7.8:1) compliant with ISO 15008 sunlight legibility standards.
- **Cockpit Typography Upgrade**: Enlarged distance countdowns to 40px bold, street names to 20px, and ETA to 16px.
- **Glove-Friendly Touch Hitboxes**: Enlarged TFT zoom buttons to 66×54px with 16px safety margins to prevent riding glove mis-taps.
- **Mode-Aware Polylines**: Scaled route polyline thickness (16px casing / 10px core on daylight tiles).
- **Vehicle Cursor Centering**: Positioned vehicle anchor at centroid `(530, 175)` to prevent polyline overlap with the tachometer shroud on right turns.

---

## [1.3.12] - 2026-11-14

### Streaming & Network Stability
- **Nanosecond UDP Packet Pacing**: Enforced high-resolution nanosecond pacing (`UDP_PACKET_PACE_NANOS = 550_000L`) to prevent buffer overrun on the cluster's embedded ESP32/lwIP socket ring.
- **Non-Blocking Frame Sender Pool**: Introduced a 4-slot ring buffer pool for JPEG datagram chunking to eliminate frame tearing.
- **Lock-Proof Screen Continuity**: Retained continuous 800×480 framebuffer rendering even when phone display goes to sleep.

---

## [1.3.6] – [1.3.11] - 2026-11-13

### Added
- **Universal LCD & TFT Auto-Escalation**: Dynamic hardware GATT auto-detection across dual-variant Pulsar models (NS200, N250, Dominar 400). Tests legacy LCD packets first and auto-escalates to modern TFT protocol upon receiving GATT auth rejections.
- **Manual Console Type Override**: Option in Settings to lock console type to Auto, Monochrome LCD, or Color TFT.
- **Dual-Mode Mirroring**: One-click switching between headless offscreen Live Map (slippy tiles + OSRM routing) and real-time Google Maps screen casting (`MediaProjection`).
- **55-Byte Legacy Telemetry Checksum**: Fixed missing checksum in 55-byte LCD telemetry frames (byte 54), preventing strict LCD console disconnects.
- **TBT Keepalive Optimization**: Extended stationary keepalive interval to 5000ms while retaining 500ms active turn countdown updates.

---

## [1.2.0] – [1.2.9] - 2026-11-12

### Added
- **Bajaj Pulsar NS400Z 5-inch TFT Support**: Reverse-engineered OTC Engineering Wi-Fi mirroring protocol (`TCP 5558` control, `UDP 5556` JPEG video frames, WPA2 SoftAP derivation).
- **NAV_V2 Interactive Cluster Menu (0x07..0x0F)**: Support for cluster-driven POI searches (Overpass API routed over cellular data), Favorites, Recent Trips, and Take Me Home shortcuts.
- **Phonebook & Call Log Sync**: Syncs top 5 Starred Contacts (`0910`) and Recent Calls (`0b10`) to cluster for handlebar dialback.
- **Handlebar Media Controls**: Handlebar track skip, play/pause (`0610`), and playlist track browsing (`0510`).
- **PulsarNav Labs TFT Debugger**: Built-in 800×480 virtual cluster canvas with coordinate touch simulation and protocol packet log inspector.

---

## [1.0.0] – [1.1.0] - 2026-11-10

### Initial Release
- **Turn-by-Turn Interceptor**: Intercepts Google Maps and Waze notifications and formats them into 48-byte OTC Bluetooth Low Energy packets.
- **Offline First**: Privacy-first, zero-cloud architecture running entirely on-device.
- **Maintenance & Fuel Logs**: Chain lubrication reminders (500 km), periodic service tracker, and refuel economy logging.
- **Digital Glovebox**: Encrypted local storage for RC, insurance, and PUC documents.
- **Encrypted Backup & Restore**: AES-256-GCM `.pnav` vault files and QR code device migration.
