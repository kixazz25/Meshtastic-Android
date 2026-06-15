# GroupTrack — Handoff (2026-06-15, ~1:00 AM EOD)

**Branch:** feature/convoy-event-ride · **Committed HEAD:** `168778c0a` · **Rollback anchor:** `168778c0a`
**Working tree:** [3.1] Patches 1–4 applied + built, **not committed**.

---

## WHERE WE LANDED TONIGHT
1. **bbox persistence works.** Built, installed to Droid 1 (field), proven on device: bbox saves and restores; ALL/OFF states save and restore; planning opens to its persisted last-session frame; convoy opens to GPS.
2. **A real bug surfaced underneath it** — the artifact-list **save** ([3.1c]) doesn't serialize SELECT-mode selections unless that type's list panel is open at save time. This was always true; the lossy save only became visible now that restore actually works.
3. **Nothing committed.** Patch 4 (debounced pan-save) is applied but suspect; its rollback is written and waiting.
4. No manual / release-notes edits tonight.

---

## START HERE TOMORROW

**First move — confirm the selection source (one read):**
```
cd ~/Meshtastic-Android && sed -n '1250,1280p' app/src/main/java/com/geeksville/mesh/convoy/ConvoyMapViewerScreen.kt
```
Goal: confirm `trailCheckedIds / trackCheckedIds / waypointCheckedIds / routeCheckedIds` reliably hold the selection at save time, and that `selectedArtifactIds` ↔ `*CheckedIds` are in sync while the panel is open. These per-type sets are the authoritative live selection the save should write.

**Then, in order:**
1. Decide Patch 4 — roll back the debounced full-save, or convert it to a bbox-only write. A pan must not trigger the full save. (Rollback script: `rollback_p4_debounced_viewport_save_2026-06-14_v1.py`.)
2. Rewrite `rowsFor` in **both** `savePlanningState` (MapViewer ~231) and `saveConvoyState` (ConvoyScreen ~227): build SELECTED rows from `<type>CheckedIds`, not the `activeListType`-gated `artifactList`. One `checked:true` row per selected id.
3. One build (~30 min). Install Droid 1.
4. Prove with device JSON pulls (no pan): select 2 → `state:2` + 2 `checked:true`; leave/return → 2 display; pan within frame → 2 stay; pan out of frame → drop, others no. Walk the 4 acceptance cases below.
5. Only then **commit Stage 1** — bbox persistence + the `rowsFor` fix. Named files only: `MapStateStore.kt`, `ConvoyScreen.kt`, `ConvoyMapViewerScreen.kt`. Never `git add .`. Leave the parked weekend files.

---

## THE BUG IN ONE LOOK

`rowsFor` (both save functions):
```
fun rowsFor(type) {
    if (activeListType != type) return emptyList()   // ← drops every non-open type
    return artifactList.mapNotNull { ... checked = id in selectedArtifactIds }
}
```
Only the currently-open list type gets rows. Everything else saves `rows:[]`. Device JSON (pulled, no pan) showed `"Trails":{"state":2,"rows":[]}` — SELECT with empty rows → restore finds no checked ids → render shows nothing → selection looks dropped. The selection was never wrong in memory (display + reopened SEL show it); the **save** just never captured it.

**Fix:** serialize the selection from the persistent per-type checked-id sets, independent of which panel is open.

---

## FILTER-DISPLAY RULE (the spec the fix must satisfy)
JSON filter = golden display state.
- bbox unchanged → no query; old values carry over verbatim.
- bbox changed → reconcile by id: in-both keeps JSON's yes/no (never flip an in-frame yes→no); new-in-query = no; gone-from-query (left bbox) = deleted (no stale resurrection).
- Partial overlap counts as in-bbox (long trails). The query is a bbox-intersect, so partial overlaps already come back.
- A yes only becomes no by leaving the bbox.
- The render filter `filter { id in capIds }` already does the display reconcile correctly; only the save needs fixing.

**Acceptance cases:**
1. bbox unchanged, 10 in filter / 2 selected → 2 display.
2. 2 selected, both in bbox → 2 display.
3. 15 in new bbox, 2 carried → 2 display, 13 no.
4. 1 selected leaves bbox → 1 displays.

---

## DESIGN CONTEXT (settled this session, carries into Stage 2 / [2h.1])

**Map-purpose model.** Maps are purposed by what you're doing, not where you are.
- Convoy = live, location-anchored: GPS open, proximity-based artifact finding (all-functions). No search, no fit.
- Planning = deliberate, identity-anchored: name search, fit, persisted-frame open.
- A function that helps one map hinders the other — the asymmetries are intentional.
- [2h.1] consequence: convoy gets the all-functions (no search entry); planning gets the search surface.

**Fit (Stage 2) simplification.** Once [3.1c] is fixed, Fit just writes one item as `checked:true` (+ its bbox) into the golden JSON and relaunches; the same merge displays it, with neighbors available to combine for route-building. May make the separate `fitArtifact` field redundant — confirm at Stage 2.

**Build order:** [3.1] Stage 1 (bbox done + save fix) → commit → [2h.2] Fit → [2h.1] search rehost → [3.9a] arrows.

**[3.1b] deferred:** GPS-recenter bull's-eye placeholder on planning's layer nav bar (planning no longer auto-centers on GPS).

---

## TREE / DEVICE
- Parked weekend files (leave): `M utah_trails_stgeorge.geojson`, `?? grouptrack_manual.html`, `?? grouptrack_release_notes.html`, `?? *.geojson.bak`, `D docs/.tmp.driveupload/10630`.
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease`
- Install: `adb -s <serial> install -r -d app/build/outputs/apk/google/release/app-google-release.apk`
- Droid 2 = `24039703201775` (dev) · Droid 1 = `8624SBCEDF00001789` (field, real GPS)
- JSON pull: `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell cat /sdcard/Documents/GroupTrack/state/planning_panel.json`

## LESSON FOR NEXT TIME
When the symptom is "saved state looks wrong," **pull the file first.** Tonight went long deriving merge rules and chasing the pan-save before pulling the JSON and reading `rowsFor` — which is what actually pinned it in minutes.
