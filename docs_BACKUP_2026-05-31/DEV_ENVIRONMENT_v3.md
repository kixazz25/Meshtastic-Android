# ANDROID DEVELOPMENT ENVIRONMENT — FRED'S LAPTOP
# Version: v3 — Updated April 4, 2026
# LAPTOP: LAPTOP-LAJBGU5N / Windows 11 / Git Bash

---

## PROJECT

| Item | Value |
|---|---|
| Project root | ~/Meshtastic-Android |
| Branch | feature/convoy-event-ride |
| Build gradle | app/build.gradle.kts |
| ConvoyConfig | app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt |
| V3 flag | ConvoyConfig.kt: V3_FEATURES_ENABLED = false (flip to true for V3 build) |

---

## BUILD COMMANDS

### V2.4 Release — Tester Build (V3 features hidden)

Confirm flag is OFF before building:
```bash
grep "V3_FEATURES_ENABLED" app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt
# Must show: V3_FEATURES_ENABLED = false
```

Build and copy to Downloads:
```bash
./gradlew copyApkToDownloads 2>&1 | grep -E "^e:|BUILD|APK:|FAILED"
```

Install to Android 1 (tester device):
```bash
adb -s 8624SBCEDF00001789 install -r -d app/build/outputs/apk/google/debug/app-google-universal-debug.apk
```

Output: `GroupTrack_v2.4_YYYYMMDD_HHMM.apk` in Downloads
- No Services icon visible
- Safe for all testers

---

### V3.0 Release — Dev Build (V3 features enabled)

Flip flag ON:
```bash
sed -i 's/V3_FEATURES_ENABLED = false/V3_FEATURES_ENABLED = true/' app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt
```

Build and copy to Downloads:
```bash
./gradlew copyApkToDownloads 2>&1 | grep -E "^e:|BUILD|APK:|FAILED"
```

Install to Android 2 (dev device):
```bash
adb -s 24039703201775 install -r -d app/build/outputs/apk/google/debug/app-google-universal-debug.apk
```

Flip flag back OFF after build:
```bash
sed -i 's/V3_FEATURES_ENABLED = true/V3_FEATURES_ENABLED = false/' app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt
```

Output: `GroupTrack_v3.0_YYYYMMDD_HHMM.apk` in Downloads
- Services icon visible
- Dev testing only — not for general testers

---

### Both Releases — Full Session Build

Run in sequence:
```bash
# Step 1 — V2.4 build
grep "V3_FEATURES_ENABLED" app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt
./gradlew copyApkToDownloads 2>&1 | grep -E "^e:|BUILD|APK:|FAILED"
adb -s 8624SBCEDF00001789 install -r -d app/build/outputs/apk/google/debug/app-google-universal-debug.apk

# Step 2 — V3.0 build
sed -i 's/V3_FEATURES_ENABLED = false/V3_FEATURES_ENABLED = true/' app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt
./gradlew copyApkToDownloads 2>&1 | grep -E "^e:|BUILD|APK:|FAILED"
adb -s 24039703201775 install -r -d app/build/outputs/apk/google/debug/app-google-universal-debug.apk

# Step 3 — Restore flag to OFF
sed -i 's/V3_FEATURES_ENABLED = true/V3_FEATURES_ENABLED = false/' app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt

# Step 4 — Confirm flag is restored
grep "V3_FEATURES_ENABLED" app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt
# Must show: V3_FEATURES_ENABLED = false
```

---

## APK OUTPUT

| Item | Path |
|---|---|
| Universal debug APK | `app/build/outputs/apk/google/debug/app-google-universal-debug.apk` |
| V2.4 stamped APK | `Downloads/GroupTrack_v2.4_YYYYMMDD_HHMM.apk` |
| V3.0 stamped APK | `Downloads/GroupTrack_v3.0_YYYYMMDD_HHMM.apk` |
| Release notes | `Downloads/GroupTrack_v2.4_YYYYMMDD_HHMM_ReleaseNotes.txt` |

---

## DEVICES

| Device | Serial | Role | Gets |
|---|---|---|---|
| Android 1 | 8624SBCEDF00001789 | V2.4 tester | V2.4 build only — flag OFF |
| Android 2 | 24039703201775 | V3.0 dev (no GPS hardware) | V3.0 build only — flag ON |

---

## ADB PATH

```
/c/Users/kixaz/AppData/Local/Android/Sdk/platform-tools/adb.exe
```

Add to PATH permanently — add to ~/.bashrc:
```bash
export PATH=$PATH:/c/Users/kixaz/AppData/Local/Android/Sdk/platform-tools
```
Then: `source ~/.bashrc`

---

## COMMON ADB COMMANDS

```bash
# Check connected devices
adb devices

# Force stop before testing
adb -s 8624SBCEDF00001789 shell am force-stop com.geeksville.mesh.google.debug
adb -s 24039703201775 shell am force-stop com.geeksville.mesh.google.debug

# View live convoy logs
adb -s 8624SBCEDF00001789 logcat | grep -E "ConvoyEngine|ConvoyApply|ConvoyMap|ConvoyViewModel"

# Pull debug log
adb -s 8624SBCEDF00001789 shell run-as com.geeksville.mesh.google.debug cat files/convoy_debug.log
adb -s 24039703201775 shell run-as com.geeksville.mesh.google.debug cat files/convoy_debug.log

# Push file to device
adb push /c/Users/kixaz/Downloads/somefile.json /sdcard/Download/

# Pull file from device
adb pull /sdcard/Download/somefile.json /c/Users/kixaz/Downloads/
```

---

## AWS / BACKEND

| Item | Value |
|---|---|
| EC2 | 34.224.89.217 |
| SSH | `ssh -i ~/.ssh/convoy-api-key-2.pem ec2-user@34.224.89.217` |
| API | https://grouptrack.org/convoy_api.php |
| RDS endpoint | convoy-tracker-db.cudtjxrtdbql.us-east-1.rds.amazonaws.com |
| Database | convoy_tracker |

---

## PYTHON

```
/c/Users/kixaz/AppData/Local/Python/pythoncore-3.14-64/python.exe
```
Run as: `python3`

---

## PATCH SCRIPT RULES

- Every script has a unique versioned filename: `fix_something_v1.py`, `fix_something_v2.py`
- Never reuse a filename — Windows adds (1)(2) copy numbers which break execution
- Scripts always run from `~/Meshtastic-Android`
- Run as: `python3 /c/Users/kixaz/Downloads/script_name_v1.py`
- One script per complete task — no piecemeal patches
- Verify with grep/sed after every script before building

---

## RELEASE RULES

- Default state: `V3_FEATURES_ENABLED = false` — always restore after V3 build
- Build command: `./gradlew copyApkToDownloads`
- APK filename stamp comes from BUILD_STAMP baked at compile time — never from copy time
- Tag each tester release: `git tag v2.4-YYYYMMDD -m "Tester release"`
- Commit after every confirmed working task before moving to next
- Never commit with V3_FEATURES_ENABLED = true

---

## DOC REPOSITORY RULES

- All docs committed to `docs/` folder via `recommit_docs.sh` at end of every session
- All living documents versioned: `_v1` `_v2` `_v3` suffix
- Older versions archived to `docs/archive/`
- Radio configs in `docs/radio_configs/`

### GitHub raw URL for session start:
```
https://raw.githubusercontent.com/[username]/Meshtastic-Android/feature/convoy-event-ride/docs/DEV_ENVIRONMENT_v3.md
```

---

## SESSION START CHECKLIST FOR CLAUDE

Upload or paste at session start:
1. `docs/DEV_ENVIRONMENT_v3.md` — this file
2. Current open task list
3. Any new docs since last session

---

*GroupTrack | DEV_ENVIRONMENT_v3.md | April 4, 2026*
