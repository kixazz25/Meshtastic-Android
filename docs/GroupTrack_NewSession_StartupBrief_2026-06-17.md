# GroupTrack — Startup Brief / Issues Detail
**2026-06-17 EOD → resume point for next session**
**Branch:** feature/convoy-event-ride · **Committed HEAD:** `009b158aa`

On device right now (proven at `009b158aa`): maps **position** on FIT, all artifact types **populate their lists**, and **manual filter-select works**. Three items remain. Each below: **what's wrong → what we did to isolate it → the fix to implement → status.**

---

## ISSUE 1 — FIT does not RETAIN the selected artifact  *(JOB #1)*

### What's wrong
FIT an artifact (track) → the map flies to it and the JSON on disk is **correct** (`Tracks state:2`, the fitted row `checked:true`, other types `0`, Utah bbox). **But** the Work-with-Artifacts panel shows the track **unselected/off** and it **does not draw**. Manually selecting the *same* track from the filter draws and selects it perfectly. Same failure on both maps.

### What we did to isolate it
- **Read and verified the entire selection chain — it is all correct:**
  - `readMap` parses `checked` with `optBoolean("checked", false)` → matches JSON boolean `true`.
  - `checkedIdsFor` returns checked ids **only when `state == 2`**.
  - `rowsFor` builds `Row(id, "", true)` from `checkedIds`.
  - grid `isSelected = id in selectedIds` — id-based.
  - `processArtifact` SELECTED filter is `raw.filter { it[idField] in checkedIds }` — **id-based, not name** (so the blank name in Issue 2 does NOT cause this).
  - Net rule: a row draws **iff (row id ∈ checkedIds) AND (state != DS_OFF) AND (zoom ≥ minZoom).** DB query rows carry **no** checked/state field — display is decided entirely by the `checkedIds` set + the per-type state.
- **Ran a diagnostic** (CMPDIAG log at the draw compare). It logged Trails draws but produced **zero `type=Tracks` lines** → `processArtifact("Tracks")` bailed at an **early return** (the log sat after the `DS_OFF`/minZoom returns). Pinpoints it: **Tracks arrives at the draw with `state = DS_OFF`, not the JSON's `2`.**

### Root cause (confirmed)
FIT updates the **SAVED** side (JSON) but never the **LIVE** side (in-memory vars) the screen actually draws from. There are **two draw paths, both BY DESIGN — keep them:**
- **(A) `drawPersistedState`** — JSON-sourced (saved/restore + fitBounds map-move). FIT calls this.
- **(B) `ConvoyScreen` onViewportChanged path (~786-800)** — builds states/selectLists from the **in-memory live vars** (`trackState`, `trackCheckedIds`, …) so the user's live selections **survive zoom/pan.** Exists on purpose; do not remove.

On FIT: JSON says `state:2`, but in-memory `trackState` stays `OFF` (the existing reseed at ~771 convoy / ~611 planning is gated by `lastMapProcessed != "convoy"`, so it skips a same-map FIT). The next viewport event runs path B with `OFF` → `processArtifact("Tracks")` hits the `DS_OFF` early-return → never draws.

### Approaches ruled OUT (do not revisit)
- **Collapse the two paths / make path B read JSON** — WRONG. Path B must read the in-memory live vars or live (unsaved) selections are lost on every zoom/pan.
- **Ungated reseed-from-JSON after FIT** — rejected as a band-aid (it syncs two states instead of feeding the real one). Currently in the tree from a committed-but-wrong attempt; replace it with the real fix.

### The fix to implement
FIT must update the **live** in-memory selection the **same way a manual filter-select does** (manual works):
1. **Build the rows** — run the query (same one the normal flow uses).
2. **Use the JSON data to emulate the screen's row-selection** — do to the fitted row exactly what a manual tap does to the **live** selection state, driven by the JSON `checked` id (not a tap).
3. **Run the normal draw.**

FIT stops being its own draw path and **reuses the existing select-then-draw machinery**, sourcing "which row" from JSON instead of a tap.

**FIRST MOVE next session:** find the exact function / state mutation a **manual filter-select** performs on a row (what it writes into the live in-memory selection state). Make FIT perform that same mutation using the JSON-checked id, then let the normal draw run. Device-prove: FIT a track → it stays selected and draws with **no** manual re-select. Then **commit.**

### Status: OPEN — top priority.

---

## ISSUE 2 — FIT writes a BLANK name into the JSON row

### What's wrong
FIT's JSON row for the fitted artifact has `name: ""`. The **id is correct** (matches the detail panel) and the **name exists in the DB**, but FIT stores blank.

### What we did to isolate it
- Confirmed the id is right and the name is present in the spatial DB.
- Tried sourcing the name two ways inside `fit()`: from the in-bbox viewport row (`inBbox.firstOrNull{ it[idField]==artifactId }?.get("name")`) and via `getArtifactDetail`. **Still wrote `""`.**
- **Unverified:** `grep inBbox.firstOrNull ConvoyArtifactOps.kt` returned **0** — confirm which name-version actually landed in the committed file. The *why* is not yet closed.

### The fix to implement
**Read `fit()` as committed** to see which name-source is in place and what key it reads. `queryTracksByViewport` returns the name under key **`"name"`** and the id under **`"track_id"`** (plural-table id column, not a generic `idField`) — a key-name mismatch is the likely culprit. Source the name from the matched query row by the correct keys, or from `getArtifactDetail(type, id)`.

### Status: OPEN — cosmetic (display match is id-based), fix alongside FIT.

---

## ISSUE 3 — (process lesson, not a code bug) Diagnostic build broke all draw

### What happened
A debug-log diagnostic in `SpatialDisplayManager.kt` produced a build where **nothing drew**. Reverted with `git checkout HEAD -- SpatialDisplayManager.kt`, which briefly looked like it wiped a day's work.

### What we did to isolate / recover
- `git diff fe8a0849a -- SpatialDisplayManager.kt` proved the **only** uncommitted delta was the **11-line fitBounds block** — the whole-day shared-fn work was already committed in `fe8a0849a`. Nothing real lost.
- Restored fitBounds, rebuilt, FIT positions again, **committed** the working state as `009b158aa`.

### Lessons (standing rules)
- Before reverting an uncommitted file, **run `git diff <last-commit> -- <file>`** to know the scope.
- **Don't stack debug-log builds**; reason from ONE clean diagnostic. A broken diagnostic costs a 20-40 min cycle + leaves the device on the broken apk.
- **Commit working states promptly.**

### Status: CLOSED (recovered + committed).

---

## RECOMMENDED PLAN — TOMORROW (locked order)

1. **COMPLETE FIT (Issues 1 + 2).**
   a. Read `fit()` + the manual-select path; identify the live-selection mutation a row tap performs.
   b. Implement: FIT builds rows → emulates that mutation from the JSON-checked id → runs the normal draw (replace the band-aid reseed).
   c. Fix the blank name (correct query keys / `getArtifactDetail`).
   d. Build, device-prove (FIT → stays selected + draws, no manual re-select; name present), **commit.**

2. **MAGNIFYING-GLASS UNIVERSAL SEARCH FAB** *(spec: `GroupTrack_Search_Consolidation_Handoff_2026-06-16.md`)*
   One draggable magnifying-glass FAB on **both** maps replacing the **three** current searches. Remove the three launch points, keep the engines: planning area search (ConvoyMapViewerScreen ~365-405, Geocoder→setView); convoy location search (ConvoyScreen `locationSearchQuery/Results/Error` @267-269); artifact search (`onSearch`→`searchByName` `4f7abbbb7`). FAB = selector (Area/Trail/Track/Route/Waypoint) + text box. Area→setView; Artifact→`searchByName`→results→**ArtifactDetailPanel**→FIT (now that FIT retains). Build + device-prove + commit.

3. **ARROWS → PIXEL-BASED ON TRACKS** *([3.9a])*
   Arrow decorations → **pixel-based spacing + neon green**, on tracks. Two CRLF HTMLs (`convoy_map.html` + `grouptrack_map.html`, they drift) + `polylineDecorator`. "Should be quick." Build + device-prove + commit.

4. **(then)** EOD docs (release-notes + manual HTML, V2.5 entries); map-tap→detail; planning discussion.

---

## METHOD / GUARDRAILS (apply throughout)
- NO "complete" without on-device proof. Fix fails → revert (git-diff first). MEASURE (logcat/JSON/DB pull), don't guess.
- The two state paths (live in-memory vs saved JSON) are **by design** — don't collapse them; new actions use the **existing live-selection mechanism.** Holistic fixes, not per-map band-aids.
- Own behavior in the SHARED component; callers pass DATA not BEHAVIOR.
- Walk ONE command at a time (Fred runs each, pastes). Standalone python patch, count==1 guard, unique dated filenames, runtime CRLF/LF detect, dry-run before shipping.
- Don't mix a fix + a feature in one build; don't stack diagnostic builds.
- Commit only named files; never `git add .`. Parked files stay parked.
- LINE ENDINGS: .kt mixed CRLF/LF even within a file — verify raw bytes, never sed/awk/cat-piped.

## DEVICE / BUILD
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (~11-42 min)
- APK: `app/build/outputs/apk/google/release/app-google-release.apk`
- Install: `adb -s 8624SBCEDF00001789 install -r -d <apk>` (Droid 1 field/GPS; Droid 2 = `24039703201775` dev)
- JSON: `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell cat /sdcard/Documents/GroupTrack/state/convoy_panel.json`
- Logcat dump (non-blocking): `adb -s 8624SBCEDF00001789 logcat -d -s ArtifactOps ConvoyMap | tail -20`
- Revert one file: `git checkout <hash> -- <file>` (git diff FIRST). NO sqlite3 on device.
