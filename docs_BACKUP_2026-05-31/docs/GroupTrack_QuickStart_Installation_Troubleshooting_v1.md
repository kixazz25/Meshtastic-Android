# GroupTrack — Quick Start, Installation & Troubleshooting
## Tester and Developer Reference
## Updated May 9, 2026

---

## QUICK START

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
- [ ] Map tiles downloaded for ride area (check blue overlay on Planning Map)
- [ ] Trail data downloaded for ride area
- [ ] GPS lock acquired (position shows on map)

---

## INSTALLATION

### Installing GroupTrack APK

**From Play Store (tester):**
- Accept tester invitation link
- Download from Play Store
- Package: com.grouptrack.android

**From local APK (developer/tester):**
```bash
# Android 1 — V2.4 tester device
adb -s 8624SBCEDF00001789 install -r -d app/build/outputs/apk/google/debug/app-google-universal-debug.apk

# Android 2 — V3.0 dev device
adb -s 24039703201775 install -r -d app/build/outputs/apk/google/debug/app-google-universal-debug.apk
```

**⚠️ INSTALL RULE: Always use `-r -d` flags.**
Never omit `-r -d` — doing so wipes tile cache, app data, and config.

**⚠️ Play Store vs Local APK: Different signing keys.**
Must uninstall one before installing the other. Cannot have both.

### Post-Install Steps

1. Launch GroupTrack
2. Grant location permission when prompted
3. If duplicate launcher icon appears: `adb shell pm clear com.android.launcher3`
4. Open Planning Map and verify tile loading
5. Connect radio — should auto-discover via Bluetooth

### Radio Setup

1. Power on T1000-E radio
2. **Verify firmware is 2.6.11 or earlier** — DO NOT UPDATE
3. Open GroupTrack → radio should appear in device list
4. Select radio to pair
5. Verify connection: position icon, signal strength visible

### Device Compatibility

| Device | Status | Notes |
|--------|--------|-------|
| Android 1 (8624SBCEDF00001789) | ✅ Validated | V2.4 tester device |
| Android 2 (24039703201775) | ✅ Validated | V3.0 dev device (no GPS) |
| P10_T (P10V07162601000362) | ✅ Validated | 3GB RAM, Android 16 |
| Tab8NEU / budget tablets | ❌ Not compatible | Cannot hold BLE to T1000-E. Supervision timeout 0x0008 after 8-10 seconds. Hardware issue, not app bug. |

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
- **Cause:** Old cached tiles (downloaded with hybrid URL) showing baked-in labels plus new overlay labels.
- **Fix:** Clear tile cache for affected area and re-download with current tile source URL.

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

**Symptom: ANR (App Not Responding) on launch**
- **Cause:** Blocking operation on main thread.
- **Fix:** Force close and reopen. If persistent, report with logcat output.

**Symptom: Duplicate launcher icons**
- **Fix:** Run `adb shell pm clear com.android.launcher3` to clear launcher cache.

**Symptom: App data lost after update**
- **Cause:** Installed without `-r -d` flags, or switched between Play Store and local APK.
- **Fix:** Always install with `adb install -r -d`. Uninstall opposite signing key version before installing.
- **Recovery:** User spatial data in /sdcard/Documents/GroupTrack/data/ survives uninstall. Tile cache in /sdcard/Documents/GroupTrack/maps/tiles/ survives uninstall. Only app-internal data is lost.

### Radio Firmware Reference

| Firmware | Status | Notes |
|----------|--------|-------|
| 2.6.11 | ✅ APPROVED | Maximum recommended version for T1000-E |
| 2.6.12+ | ❌ DO NOT USE | Destabilizes BLE and radio configuration |
| < 2.6.11 | ✅ OK | Earlier versions work but may lack features |

### Emergency Recovery

If everything is broken:
1. Uninstall GroupTrack
2. Flash T1000-E radio to firmware 2.6.11
3. Reinstall GroupTrack APK with `adb install -r -d`
4. Launch app — spatial data in Documents/GroupTrack/ should still be there
5. Reconnect radio
6. Re-download tiles if cache was lost

---

## DEVELOPER NOTES

### Build Commands
```bash
# V2.4 debug build
./gradlew copyApkToDownloads 2>&1 | grep -E "^e:|BUILD|APK:|FAILED"

# V2.4 release APK
./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease

# V2.4 release AAB (Play Store)
./gradlew bundleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease
```

### Log Viewing
```bash
# Live convoy logs
adb -s 8624SBCEDF00001789 logcat | grep -E "ConvoyEngine|ConvoyApply|ConvoyMap|ConvoyViewModel"

# Pull debug log
adb -s 8624SBCEDF00001789 shell run-as com.geeksville.mesh.google.debug cat files/convoy_debug.log
```

### Asset Pull Rule
Use `run-as cat` redirect, never `adb pull` for internal storage:
```bash
adb -s [serial] shell run-as [pkg] cat files/[file] > app/src/main/assets/[file]
```

---

*GroupTrack | Quick Start, Installation & Troubleshooting | Updated May 9, 2026*
*T1000-E firmware: LOCK TO 2.6.11. Radio stays in cart during rides.*
