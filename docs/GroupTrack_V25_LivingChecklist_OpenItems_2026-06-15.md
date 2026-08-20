# GroupTrack V2.5 — Living Checklist / Open Items
**Updated:** 2026-06-15 (~1:00 AM EOD)
**Branch:** feature/convoy-event-ride · **Committed HEAD:** `168778c0a` · **Rollback anchor:** `168778c0a`

---

## TONIGHT'S OUTCOME (2026-06-15)
- ✅ **bbox persistence SOLVED** — built, installed to Droid 1 (field), device-proven. bbox saves + restores; ALL/OFF states save + restore; planning opens to persisted last-session frame; convoy opens to GPS.
- 🐞 **Uncovered a pre-existing artifact-list SAVE bug** ([3.1c]) — bbox-restore working is what made it *visible*. Selection (SELECT-mode checked items) is not serialized to JSON unless that type's list panel is open at save time. **Not a new break — the save was always lossy; restore never worked before bbox so it never showed.**
- ⛔ **NOT committed.** Working tree has Patches 1–4 applied + built. Patch 4 rollback written, not yet run.
- 📄 No user-manual or release-notes changes tonight.

---

## ⛔ [3.1] BBOX PERSISTENCE — Stage 1 (IN PROGRESS, not committed)

### Done + proven
- **Patch 1** — `MapStateStore.kt` (CRLF) schema: `BBox`, `FitArtifact` data classes; `MapSnapshot += bbox?, fitArtifact?` (defaulted); `saveMap` writes both keys; `readMap` parses both (backward-compatible). Standalone round-trip test 10/10.
- **Patch 2** — both save call sites pass `bbox = BBox(lastViewport*)` + `fitArtifact = null` (blank-on-normal-save).
- **Patch 3** — planning `onPageFinished`: if `pmSeed.bbox != null` → JS `fitBounds([s,n],[w,e])`; else GPS `setView` fallback. Convoy untouched.
- **Build:** SUCCESSFUL (30m13s). Installed Droid 1.

### Patch 4 (applied + built — under review)
- Debounced 400ms viewport-settle save on all 3 `onViewportChanged` callbacks, calling the full save.
- **Problem:** a pan-save fires the *full* save when no list panel is open → `rowsFor` returns empty → blanks rows.
- **Not the root cause** of the selection bug (bug reproduces with no pan) but it worsens it.
- **Rollback script written, not run.** Decision pending: roll back, or convert the pan-save to bbox-only.

---

## 🐞 [3.1c] ARTIFACT-LIST SAVE BUG — root cause confirmed (FIX TOMORROW)

**Symptom:** Set a type to SELECT, pick items → they display correctly and persist in-session (reopening SEL shows them). But the saved JSON shows `"state":2, "rows":[]` (SELECT with empty rows). On restore there are no checked ids → nothing displays → selection appears lost.

**Root cause (in `savePlanningState` ~231 and `saveConvoyState` ~227):**
```
fun rowsFor(type) {
    if (activeListType != type) return emptyList()   // ← only the OPEN list type gets rows
    return artifactList.mapNotNull { ... checked = id in selectedArtifactIds }
}
```
Rows are only built for the one list type whose panel is currently open. Every other type saves empty rows. So a save fired at any moment the matching panel isn't open writes empty selection.

**Why invisible until now:** the gate always existed; the JSON save was always lossy. The app runs off in-memory Compose state in-session (display + SEL reflect it), and before bbox, restore was broken anyway — so nobody relied on the JSON reloading. Making restore work (bbox) exposed that the save was never complete.

**Fix direction:** rewrite `rowsFor` (both screens, mirrored) to serialize the live selection from the persistent per-type sets — `trailCheckedIds / trackCheckedIds / waypointCheckedIds / routeCheckedIds` — not the panel-gated `artifactList`. Write a row `checked:true` for each id in `<type>CheckedIds`. SELECT then saves `state:2, rows:[picks checked:true]`.

---

## [3.1c] FILTER-DISPLAY RULE (locked — spec the fix must satisfy)
JSON filter is the **golden** display state.
- **bbox unchanged** → no query; old JSON values carry over verbatim.
- **bbox changed** → query + reconcile by id:
  1. id in new query **and** in JSON → keep JSON's yes/no (golden; never flip an in-frame yes→no).
  2. id new in query (not in JSON) → add as **no**.
  3. id in JSON but gone from query (left bbox) → **delete** (not remembered; a returning item comes back **no** — no stale resurrection).
- **Partial overlap = in-bbox** (any part of an artifact in frame keeps it eligible — matters for long trails). The query is a bbox-intersect, so partial overlaps are already returned.
- A yes only ever becomes no by leaving the bbox.
- The existing SELECTED render filter `filter { id in capIds }` already implements display rules 1–2 at render. The bug is purely that the **save** blanks the source of `capIds`. Render reconcile is correct; the save must stop destroying the yes-set.

**Acceptance cases (all must pass):**
1. bbox unchanged, 10 in filter / 2 selected → 2 display.
2. 2 selected, both in bbox → 2 display.
3. 15 in new bbox, 2 carried-forward → 2 display, 13 no.
4. 1 selected leaves bbox (6 in new bbox, 1 selected still in frame) → 1 displays.

---

## PLAN OF ATTACK — TOMORROW (in order)
1. Read `ConvoyMapViewerScreen.kt` 1250–1280; confirm `*CheckedIds` reliably hold the selection at save time, and that `selectedArtifactIds` ↔ `*CheckedIds` are in sync when the panel is open.
2. Decide Patch 4: roll back the debounced full-save, or convert it to bbox-only. A pan must not call the full save.
3. Rewrite `rowsFor` (both screens, mirrored) to build SELECTED rows from `<type>CheckedIds`.
4. One build (~30 min).
5. Device-prove with JSON pulls (no pan): select 2 → `state:2` + 2 `checked:true`; leave/return → 2 display; pan within frame → 2 stay; pan out → drop, others no. Run the 4 acceptance cases.
6. Only then **commit Stage 1** = bbox persistence + `rowsFor` fix. Named files only: `MapStateStore.kt`, `ConvoyScreen.kt`, `ConvoyMapViewerScreen.kt`. Never `git add .`. Leave parked weekend files.

> Note: these "quick" tasks are never quick. Budget accordingly.

---

## [3.1b] OPEN (deferred) — GPS-recenter button (planning)
Once planning opens to the persisted frame (not GPS), the rider needs a manual "center on current GPS" control — a bull's-eye placeholder on the planning Sat/Topo/Topo+ layer nav bar (UI + defining comment; no function yet). The real GPS-center logic already exists (the planning `postDelayed` setView block). Visible-marker discipline so the gap isn't lost. Cut from Patch 3 to stay focused on persistence.

---

## DESIGN CONTEXT CARRIED FORWARD

### Map-purpose model (settled — drives [2h.1] and Fit scoping)
Maps are purposed by **what you're doing**, not where you are.
- **Convoy** = live / location-anchored: opens to GPS, finds artifacts by **proximity** (the all-functions). No search, no fit.
- **Planning** = deliberate / identity-anchored: finds artifacts by **name** (search), has fit, opens to the persisted last-session frame.
- A function that's a feature on one map is a hindrance on the other (search on planning = feature / on convoy = hindrance; current-location on convoy = feature / on planning = hindrance). The asymmetries are intentional — one rule per function.
- **Two selection methods** share one golden-JSON merge engine: (1) **area-based** — query a bbox, choose all/none/selected (many display); (2) **artifact-based / Fit** — pick one, frame to its bbox, display it alone (others available, off). Power case: Fit to an anchor, then Select-All / individuals to layer in neighbors → build a new route.
- **[2h.1] consequence:** convoy gets the all-functions (no search entry); planning gets the search surface.

### Fit (Stage 2) simplification — holds once [3.1c] is fixed
With JSON-golden + render-reconcile-by-id working, **Fit just writes one item as `checked:true` (+ its bbox) into the golden JSON filter and relaunches.** The same merge displays exactly that one; surrounding in-frame artifacts are present (as no), available to toggle on and combine. This may make the separate `fitArtifact` field redundant — confirm at Stage 2. Stage 1's `rowsFor` fix is the foundation Fit rides.

### Build order
[3.1] Stage 1 (bbox done + [3.1c] save fix) → prove → commit → [2h.2] Fit writer → [2h.1] search-surface rehost → [3.9a] arrows.

---

## TREE STATE
- Committed HEAD `168778c0a` (detail card). Working tree: [3.1] Patches 1–4 applied + built, **not committed**. P4 rollback script written, not run.
- Parked weekend files (leave them): `M utah_trails_stgeorge.geojson`, `?? grouptrack_manual.html`, `?? grouptrack_release_notes.html`, `?? *.geojson.bak` (never git-add), `D docs/.tmp.driveupload/10630`.
- Commit only named files. Never `git add .`.

## DEVICE / BUILD QUICK-REF
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (~10–34 min)
- APK: `app/build/outputs/apk/google/release/app-google-release.apk`
- Install: `adb -s <serial> install -r -d <apk>`
- Devices: Droid 2 = `24039703201775` (dev/test) · Droid 1 = `8624SBCEDF00001789` (field, real GPS)
- JSON pull (note the path-conv guard): `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell cat /sdcard/Documents/GroupTrack/state/planning_panel.json`
- Patch scripts: single-line `python3 -c` with count==1 guard; verify by byte size; CRLF-safe anchors.
