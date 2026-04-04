# ANDROID DEVELOPMENT ENVIRONMENT — FRED'S LAPTOP
# Version: v2 — Updated April 4, 2026
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

| Command | Purpose |
|---|---|
| `./gradlew copyApkToDownloads 2>&1 \| grep -E "^e:\|BUILD\|APK:\|Release"` | **PRIMARY** — builds, copies stamped APK + release notes to Downloads |
| `./gradlew assembleGoogleDebug 2>&1 \| grep -E "^e:\|BUILD"` | Build only — no copy |

Always run from: `~/Meshtastic-Android`

---

## APK OUTPUT

| Item | Path |
|---|---|
| Universal debug APK | `app/build/outputs/apk/google/debug/app-google-universal-debug.apk` |
| Stamped APK in Downloads | `GroupTrack_v2.4_YYYYMMDD_HHMM.apk` |
| Release notes in Downloads | `GroupTrack_v2.4_YYYYMMDD_HHMM_ReleaseNotes.txt` |

---

## DEVICES

| Device | Serial | Role | Gets |
|---|---|---|---|
| Android 1 | 8624SBCEDF00001789 | V2.4 tester | V2.4 build only |
| Android 2 | 24039703201775 | V3.0 dev (no GPS hardware) | V3.0 build only |

### Install commands

```bash
# Android 1 — v2.4
adb -s 8624SBCEDF00001789 install -r -d app/build/outputs/apk/google/debug/app-google-universal-debug.apk

# Android 2 — v3.0
adb -s 24039703201775 install -r -d app/build/outputs/apk/google/debug/app-google-universal-debug.apk

# Check connected devices
adb devices
```

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
# View live convoy logs
adb -s 8624SBCEDF00001789 logcat | grep -E "ConvoyEngine|ConvoyApply|ConvoyMap|ConvoyViewModel"

# Pull debug log
adb -s 8624SBCEDF00001789 shell run-as com.geeksville.mesh.google.debug cat files/convoy_debug.log
adb -s 24039703201775 shell run-as com.geeksville.mesh.google.debug cat files/convoy_debug.log

# Force stop before testing
adb -s 8624SBCEDF00001789 shell am force-stop com.geeksville.mesh.google.debug
adb -s 24039703201775 shell am force-stop com.geeksville.mesh.google.debug

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
| RDS | convoy-tracker-db.cudtjxrtdbql.us-east-1.rds.amazonaws.com |
| Database | convoy_tracker |

---

## PYTHON

```
/c/Users/kixaz/AppData/Local/Python/pythoncore-3.14-64/python.exe
```
Run as: `python3`

All patch scripts delivered as .py files to Downloads — never as one-liners.
Run as: `python3 /c/Users/kixaz/Downloads/script_name_v1.py`

---

## PATCH SCRIPT RULES

- Every script has a unique versioned filename: `fix_something_v1.py`, `fix_something_v2.py`
- Never reuse a filename — Windows adds (1)(2) copy numbers which break execution
- Scripts always run from `~/Meshtastic-Android`
- One script per complete task — no piecemeal patches
- Verify after every script before building

---

## RELEASE RULES

- Build command: `./gradlew copyApkToDownloads`
- APK filename comes from BUILD_STAMP baked at compile time — never from copy timestamp
- Tag each tester release: `git tag v2.4-YYYYMMDD -m "Tester release"`
- Commit after every confirmed working task — before moving to next
- Release notes auto-generated from git log since last tag

---

## DOC REPOSITORY RULES

- All docs committed to `docs/` folder in repo via `recommit_docs.sh`
- Run `recommit_docs.sh` at end of every session
- All living documents versioned with `_v1` `_v2` `_v3` suffix
- Older versions archived to `docs/archive/`
- Radio configs in `docs/radio_configs/`
- Session start: fetch DEV_ENVIRONMENT from GitHub raw URL or paste contents to Claude

### GitHub raw URL for session start:
```
https://raw.githubusercontent.com/[username]/Meshtastic-Android/feature/convoy-event-ride/docs/DEV_ENVIRONMENT_v2.md
```

---

## SESSION START CHECKLIST FOR CLAUDE

Paste or fetch these at session start:
1. `docs/DEV_ENVIRONMENT_v2.md` — this file
2. Current open task list doc
3. Any new docs added since last session

---

*GroupTrack | DEV_ENVIRONMENT_v2.md | April 4, 2026*
