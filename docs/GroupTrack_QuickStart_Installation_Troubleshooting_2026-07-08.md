# GroupTrack — Quick Start, Installation & Troubleshooting
## Beta Tester Reference
## Updated July 8, 2026 (V2.6)

---

## QUICK START

> ⚠️ **Off-grid rule:** trails have no cell/WiFi. **Download your maps and import your trails BEFORE you ride** — the app can't fetch them in the field. Un-downloaded areas show blank on the trail.

### Before Your First Ride

1. Install GroupTrack APK on your Android device (see Installation below)
2. Power on your T1000-E Meshtastic radio
3. Open GroupTrack — radio should connect via Bluetooth automatically
4. Verify connection: radio icon shows connected in the app

### ⚠️ CRITICAL — Two Radio Restrictions

**RESTRICTION 1 — T1000-E Firmware: DO NOT update beyond 2.6.11**

The T1000-E Meshtastic radio firmware MUST stay at version 2.6.11 or earlier. Updating to any firmware version beyond 2.6.11 will make the radio unstable for Bluetooth connectivity and radio configuration. This is a known issue with newer Meshtastic firmware on the T1000-E hardware.

- Check current firmware: Meshtastic app → Radio Config → Device Info
- If firmware is 2.6.11 or earlier: DO NOT UPDATE
- If firmware was accidentally updated beyond 2.6.11: flash back to 2.6.11 using Meshtastic Flasher tool
- This restriction applies to ALL T1000-E radios used with GroupTrack

**RESTRICTION 2 — Keep Radio Within Bluetooth Range During Rides**

When riding, ALWAYS keep your radio in your cart/vehicle within 50 feet of your phone. Do NOT carry the radio away from your phone (or vice versa) beyond Bluetooth effective range.

- Bluetooth effective range: approximately 50 feet (15 meters)
- If radio exceeds BLE range while still "connected," the connection enters supervision timeout
- Symptoms: app freezes/pauses, position updates stop, UI becomes unresponsive
- The BLE connection does not cleanly disconnect — it hangs in limbo waiting for the radio to reappear
- Recovery: force close GroupTrack, move radio back within range, reopen app
- Prevention: leave the radio mounted in your cart. Keep your phone in the cart. They travel together.

### Pre-Ride Checklist

- [ ] Phone charged
- [ ] Radio charged and firmware at 2.6.11 or earlier
- [ ] Radio mounted in cart (stays with phone during ride)
- [ ] GroupTrack installed and opens without errors
- [ ] Radio connects via Bluetooth (solid connection icon)
- [ ] **Map tiles downloaded for the ENTIRE ride area** (REQUIRED off-grid — check the blue overlay covers it on Planning Map)
- [ ] **Trail data / trailheads imported for ride area** (also needed before you lose connectivity)
- [ ] GPS lock acquired (position shows on map)

---

## INSTALLATION

### Installing GroupTrack APK

**From Play Store (tester):**
- Accept tester invitation link
- Download from Play Store
- Package: com.geeksville.mesh


### Post-Install Steps

1. Launch GroupTrack
2. Grant location permission when prompted
3. Open Planning Map and verify tile loading

### First-Time Setup (do these in order)

Before your first ride, complete these three setup steps. **For detailed, screen-by-screen walkthroughs with screenshots, open the in-app manual — tap the "?" icon** anywhere in GroupTrack. The manual is cookbook-style (how you get there → what you do → where it leads); the steps below are the short version.

**1. Settings → choose your map sources.**
Open Settings and select the map sources you want available. The defaults are the three Esri sources (SAT satellite imagery, TOPO, and TOPO+). Leave the defaults unless you have a reason to change them — these are what the download and map screens use. *(Manual: "?" → Settings / Map Sources.)*

**2. Import trails and trailheads, and DOWNLOAD MAPS for your ride area.**
Load the trail data for your riding area so trails and trailheads show on the map. Then, **while on WiFi/cell, download the map tiles for the area you'll ride** — use the Planning Map download and confirm the blue overlay covers your whole ride area. *(Manual: "?" → Import Artifacts / Trail Sources, and Offline Maps & Downloads.)* If trails don't appear, see "Trail Display Issues" in Troubleshooting.

> ⚠️ **Downloading maps before you ride is REQUIRED for off-grid use.** On the trail you are off the grid — no cell, no WiFi — so the app cannot fetch map tiles in the field. Any area you did not download ahead of time will show blank/grey on the trail. Download every area you plan to ride **before you leave connectivity**, and verify the blue overlay covers it. This applies to trails/trailheads too — import them ahead of time.

**3. Set up the radio.**
Pair and connect the T1000-E (see Radio Setup below). Do this last, after maps and trails are in place. *(Manual: "?" → Radio / Connection.)*

> Note for upgrading testers: if you're coming from a pre-V2.6 build, do the **V2.6 One-Time Tile Migration** (below) before step 2's tile downloads.

### ⚠️ V2.6 One-Time Tile Migration (upgrading testers)

V2.6 changes how offline map tiles are stored — from thousands of loose files to a single compact database per map type (about half the storage; satellite ~55% smaller, WebP-compressed). Old loose tiles must be cleared once and re-downloaded in the new format before maps will work.

1. **Install X-plore File Manager** (free, Play Store) — it shows a live progress count while deleting, so you always know it's working.
2. In X-plore, go to `Internal storage → Documents → GroupTrack → maps` and **delete the maps folder**. A large old cache can take a while — keep the device plugged in; the on-screen count shows it's active.
3. Back in GroupTrack, **re-download** your ride areas (by Area, or from a track/route). They save in the new format at roughly half the size.

Your tracks and routes are NOT affected — this clears only downloaded map *tiles*. This is one-time; future updates won't need it.

### Radio Setup

1. Power on T1000-E radio
2. **Verify firmware is 2.6.11 or earlier** — DO NOT UPDATE
3. **Program the radio to the master config default.** In GroupTrack, go to **Convoy Map → Event Ride menu → Update Radio Config**. This loads the T1000-E with the GroupTrack master configuration (the standard settings all convoy radios share) so your radio interoperates with the group. Do this before relying on the radio for a ride — a radio on different settings won't work with the convoy. *(Manual: "?" → Radio / Connection.)*
4. Open GroupTrack → radio should appear in device list
5. Select radio to pair
6. Verify connection: position icon, signal strength visible

### Device Compatibility

**Supported: Android 14 to 16 devices with a Bluetooth radio and a GPS chipset.** Both are required — Bluetooth to hold the connection to the T1000-E radio, and an onboard GPS chipset for position. A device missing either (e.g. a Wi-Fi-only tablet with no GPS) is not compatible.

| Requirement | Needed |
|-------------|--------|
| Android version | 14, 15, or 16 |
| Bluetooth | Required (BLE, to connect the T1000-E) |
| GPS chipset | Required (onboard location) |

**Known-incompatible pattern:** budget tablets that lack a proper BLE radio (or a GPS chipset) cannot hold the connection to the T1000-E — symptom is a repeating connect/disconnect every 8–10 seconds (supervision timeout reason 0x0008). This is a hardware limitation of those devices, not an app bug; use a device meeting the requirements above.

---

## TROUBLESHOOTING

### BLE Connection Issues

**Symptom: App freezes or pauses during ride**
- **Cause:** Radio moved beyond Bluetooth range (~50') while still connected. BLE supervision timeout.
- **Fix:** Force close GroupTrack. Move radio back within range of phone. Reopen app.
- **Prevention:** Keep radio mounted in cart with phone. They must stay within 50' of each other at all times during a ride.

**Symptom: Radio connects then disconnects repeatedly (8-10 second cycle)**
- **Cause:** Device hardware incompatibility (Tab8NEU, budget Android tablets). Supervision timeout reason 0x0008.
- **Fix:** Use a compatible device (see Device Compatibility table above). This is a hardware issue, not an app bug.

**Symptom: Radio won't connect at all after firmware update**
- **Cause:** T1000-E firmware updated beyond 2.6.11.
- **Fix:** Flash radio back to firmware 2.6.11 using Meshtastic Flasher tool. Do NOT use any firmware newer than 2.6.11.

**Symptom: Radio connects but position doesn't update**
- **Cause:** Radio may be out of GPS fix, or BLE connection is degraded.
- **Fix:** Check radio has clear sky view for GPS. Force close and reconnect. Verify radio firmware is 2.6.11.

### Map and Tile Issues

**Symptom: Map shows blank/grey tiles**
- **Cause:** No tile cache for this area. Online tiles failed to load (no connectivity or tile source down).
- **Fix:** Check connectivity. Download tiles for this area while on WiFi. Check blue overlay on Planning Map to confirm tile coverage.

**Symptom: Double labels on satellite map**
- **Cause:** Old cached tiles (from a hybrid URL) showing baked-in labels plus new overlay labels — typically pre-V2.6 tiles not yet migrated.
- **Fix:** Do the V2.6 one-time tile migration (Installation → V2.6 One-Time Tile Migration): delete the maps folder via X-plore and re-download. New-format tiles carry labels as a separate overlay, so the doubling goes away.

**Symptom: Map tiles load online but show blank offline**
- **Cause:** Tiles not downloaded for this area.
- **Fix:** Before going to the field, download tiles for your ride area using Planning Map download function. Verify blue overlay covers your ride area.

### Trail Display Issues

**Symptom: No trails showing on map**
- **Cause:** No trail source downloaded, or trail database not loaded.
- **Fix:** Open Planning Map → Trail Sources → select and download a source (e.g., UGRC Utah Trails).

**Symptom: Trails showing in wrong location**
- **Cause:** Coordinate system mismatch or corrupt GeoJSON source.
- **Fix:** Remove trail source and re-download/re-ingest.

### App Issues

**Symptom: App is slow or unresponsive on launch**
- **Fix:** Force close and reopen. If it keeps happening, report it to the GroupTrack team with your device model and what you were doing.

**Symptom: Missing data after an update**
- **Good news:** Your saved content is stored outside the app and survives updates and reinstalls — recorded/imported tracks and routes (in `Documents/GroupTrack/data/`) and your downloaded map tiles (in `Documents/GroupTrack/maps/`) are kept.
- **If maps look empty after a major update:** you may need the one-time tile migration — see "V2.6 One-Time Tile Migration" in Installation.

### Radio Firmware Reference

| Firmware | Status | Notes |
|----------|--------|-------|
| 2.6.11 | ✅ APPROVED | Maximum recommended version for T1000-E |
| 2.6.12+ | ❌ DO NOT USE | Destabilizes BLE and radio configuration |
| < 2.6.11 | ✅ OK | Earlier versions work but may lack features |

---

*GroupTrack | Quick Start, Installation & Troubleshooting | Updated July 8, 2026 (V2.6)*
*T1000-E firmware: LOCK TO 2.6.11. Radio stays in cart during rides.*
*V2.6 change: offline tiles migrated to per-type .mbtiles databases (~55% smaller) — upgrading testers must do the one-time tile migration (see Installation).*
