# CONVOY — PRODUCT ROADMAP

**Last Updated:** March 14, 2026 | **Author:** Fred Kix | **Repo:** kixazz25/Meshtastic-Android

---

## Platform Overview

Convoy is a Meshtastic-based off-grid convoy coordination system built as a fork of the Meshtastic Android client. It provides real-time mesh networking for vehicle convoys, event ride organization, and field coordination without cellular dependency.

| Version | Platform | Status | Description |
|---------|----------|--------|-------------|
| **V1** | Android | **COMPLETE** | Manual Setup Group Ride Tracker |
| **V2** | Android + iOS | **IN PROGRESS** | Self-Service Setup + Event/Ride Creation |
| **V3** | Android + iOS | **PLANNED** | ATAK-Modeled Map Integration |
| **V4** | Android + iOS | **PLANNED** | KML Track History Overlay |

---

## V1 — Manual Setup Group Ride Tracker

**Status: COMPLETE**

- Android only
- Manual radio configuration required before use
- Group tracking HUD — GROUP mode and MY CART mode
- Node telemetry display
- Convoy node naming: LEAD-1 through SIERRA-20, HOTEL-10 as operator cart
- APK: ConvoyTracker-V1-debug.apk

---

## V2 — Self-Service Setup + Event/Ride Creation

**Status: IN PROGRESS | Branch: feature/convoy-event-ride**

### V2 Architecture

- startDestination = ConvoyRoutes.Convoy — map loads on launch, persists entire session
- persistentWebView in ConvoyViewModel — WebView/Leaflet map never destroyed
- ConvoySubMenu — accordion bottom sheet overlay on map
- Master config bundled as app/src/main/assets/master_config.json
- Two user types: ORGANIZER and RIDER
- Channel auto-generated: CONVOY-XXXX, PSK via SecureRandom AES-256

### V2 Completed Features (as of March 14, 2026)

- ConvoyScreen — WebView/Leaflet map as base layer
- ConvoySubMenu — accordion style, Meshtastic dark theme colors
- ConvoyEnrollmentScreen — user profile creation (ORGANIZER / RIDER)
- ConvoyEmailGateScreen — email validation before event creation
- ConvoyCreateEventScreen — event/ride creation form
- ConvoySettingsPanel — password-protected developer panel (long press CONVOY)
- ConvoyMasterCaptureScreen — reads radio config as master template, PROCEED button, error logging
- ConvoyApplyList — checklist of LoRa and Position settings to apply
- ConvoyViewModel — persistentWebView, pendingEnrollmentEmail, HUD state
- master_config.json — captured from tracker-t1000-e, fw 2.6.11.60ec05e, bundled in assets
- convoy_apply_list.json — LoRa and Position fields, bundled in assets

**HUD — Completed March 14, 2026**
- Transparent HUD overlay — no background box, text floats over map
- Red text (#FF0000) on all HUD elements
- ConvoyButtonBar — fixed 48dp bottom bar: GROUP | MY CART | HIDE | GEAR (4 equal weight buttons)
- GROUP panel — Title, Carts/Active/Lost row, Span(48sp)/Lead/Tail row
- MY CART panel — Title/callsign, Heading/Battery/Altitude row, Speed(48sp) + 2x2 gap grid
- NODE panel — cart name title + RETURN button, stats rows
- HUD anchored bottom-left above button bar
- startGroupTrack wired to REC button with LENGTH_LONG toast
- stopGroupTrack wired to END recording
- Lead Only toggle moved to Gear settings screen

### V2 Pending Features

- **PRIORITY: Fix ConvoyMasterConfig.exists() check** — master_config.json is in assets but create-ride flow fails the check
- **PRIORITY: Fix capture save paths** — both master_config.json and convoy_apply_list.json must save to same accessible location and be adb-pullable
- Split capture into 3 separate password-gated options: Capture Master Config / Edit Apply List / File Transfer
- Block ride apply/restore if master config or apply list changed but not transferred to radio
- F2 — Transfer Configuration: send ride kit via email or Bluetooth
- Wire ConvoyViewModel radio flows to ConvoyRadioManager.buildSnapshot()
- Wire ChannelViewModel.setChannels() for actual radio write on event create
- Map area selector on Create Event screen
- iOS cloud build environment (MacStadium or MacInCloud)
- Fix red font on second test device (theme override issue)
- Fix selected cart panel — node tap focus or spacing issue

### V2 Technical Decisions

- React Native and Flutter explicitly excluded
- TAK Server excluded — ATAK/iTAK used as design pattern only
- Google flavor only: assembleGoogleDebug
- Password: SHA-256 hashed, default 'convoy2024' — change before release
- Default password hash stored in ConvoySettingsPanel.kt
- Build command: ./gradlew assembleGoogleDebug ONLY — never assembleDebug
- Install command: adb -s \<serial\> install -r \<apk\>
- No commit without on-device verification

---

## V3 — ATAK-Modeled Map Integration

**Status: PLANNED**

- Android + iOS
- ATAK/iTAK-style map interface for convoy coordination
- Apply Ride Info to Radio — write channel config directly to connected radio
- Restore Prior Config Post Ride — revert radio to pre-ride settings
- Offline Maps and Tile Management
- Map area selector with KML boundary
- iOS build via MacStadium or MacInCloud

**Map Requirements (must-have):**
- Offline tile download and management
- MapLibre as production map engine (Leaflet for dev/preview only)
- Support for satellite, hybrid, topo, and road tile sources
- Compatible with ATAK/iTAK map layer conventions
- iPad-compatible layout (tablet responsive UI)
- No TAK Server dependency — map patterns only

---

## V4 — KML Track History Overlay

**Status: PLANNED**

- Android + iOS
- KML route file support
- Track history overlay on Leaflet/MapLibre map
- Route deviation alerts
- Post-ride KML export

---

## Repository and Build Reference

| Item | Value |
|------|-------|
| GitHub Repo | kixazz25/Meshtastic-Android |
| Active Branch | feature/convoy-event-ride |
| Local Path | C:\Users\kixaz\Meshtastic-Android |
| Build Command | ./gradlew assembleGoogleDebug |
| Install Command | adb -s \<serial\> install -r app/build/outputs/apk/google/debug/app-google-debug.apk |
| V1 APK | C:\Users\kixaz\Downloads\ConvoyTracker-V1-debug.apk |
| V2 APK | C:\Users\kixaz\Downloads\ConvoyTracker-V2-debug.apk |
| Primary Test Device | 8624SBCEDF00001789 |
| Secondary Test Device | L8M0104BD000538 (OS 10, BT unstable — awaiting replacement) |
| Docs Path | C:\Users\kixaz\Meshtastic-Android\docs\ |
| Assets Path | app/src/main/assets/ |
| Master Config | app/src/main/assets/master_config.json |
| Apply List | app/src/main/assets/convoy_apply_list.json |

---

## Process Rules (Non-Negotiable)

1. Write → Build → Test on device → Commit. Never skip steps.
2. One change at a time. No compound changes.
3. Never commit without on-device verification.
4. Build command: ./gradlew assembleGoogleDebug ONLY.
5. Install with -r flag always.
6. Read project reference document at start of every session before writing code.
7. Install before running clean — clean wipes the APK.
8. No rollbacks — fix forward with surgical changes.
