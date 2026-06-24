# GroupTrack — Next Session Handoff (resume 2026-06-22)
*Written 2026-06-21 EOD. Pairs with `GroupTrack_V25_LivingChecklist_OpenItems_2026-06-21.md`.*

## ⚠️ READ FIRST — the process rule today re-earned
**Believe the device + Fred's lived observation over the code.** Today's entire grind was a stale `pending_area.json` silently forcing the trail-source screen into the wrong path — not lost code, not a wrong panel. We chased decompiles and git forensics for hours; the actual cause was a leftover device file. When Fred says "this worked and now it doesn't," hunt the SPECIFIC differing thing (device state, a file, one line) before theorizing. And **commit working features same-day** — the whole multi-hour "PROCEED TO UPDATE lost feature" hunt traced back to uncommitted work that got reverted.

## WHERE WE ARE
HEAD = **`4c3541c4f`**.
Two commits landed today:
- **`791ed3b45`** — V2.5 import/carto code: Patch M (carto update-in-place on reprocess), Patch O (imported sources selectable), Patch Q1–3 (explicit launch-mode A/B), Patch R (carto_code AS CartoCode alias). 4 files.
- **`4c3541c4f`** — docs: rebuilt V2.5 manual + release notes, committed as app assets (the "?" button loads them) + dated `docs/` copies. 4 files.

## WHAT GOT FIXED TODAY (all committed, device-tested)
1. **Trail-source screen opened on the WRONG step.** A stale `pending_area.json` (dated June 1, abandoned area-draw) made the screen auto-jump to B2_SUGGESTED ("Suggested Sources", the by-area path) on every launch, where imported sources are blocked. **Fix:** explicit `TrailImporter.LaunchMode {SELECT_SOURCE, BY_AREA}` — the Trails menu launch leaves it SELECT_SOURCE (opens A1 "Select Source", never reads the JSON); the by-area launch sets BY_AREA (reads the JSON). Stale file can no longer hijack the menu path. Enum is extensible for a future 3rd method. (Patch Q1–3.)
2. **Imported sources weren't selectable.** `SourceSelectCard` drew a dead ✅ badge *instead of* the RadioButton for imported sources. **Fix (Patch O):** RadioButton always shown; ✅ becomes a status badge next to it → imported sources stay selectable → VALIDATE → reprocess.
3. **Carto not updating on reprocess.** **Fix (Patch M):** `updateExistingFeature` writes carto to both spatial `trails` and extension `trail_properties` on reprocess (CARTO_ONLY default; ALL adds name/uses/etc.; never geometry). IMPORTDIFF log confirmed it runs and writes. NOTE: most spatial carto was ALREADY correct (the IMPORTDIFF BEFORE already showed `carto=3 - Paved Shared Use`) — the update is a safe no-op where data is already right.
4. **Detail-panel carto** displays correctly (committed earlier 6/20).

## ⛔ ONE OPEN BUG — popup carto shows "Unknown" (deferred to 06-22)
Tapping a trail LINE on the map → popup still says "Unknown" carto, even though:
- The DB has the carto (IMPORTDIFF proved it).
- The **detail panel** reads it correctly.
- Patch R aliased `carto_code AS CartoCode` in `queryTrailsByViewport` (SpatialDbManager ~269) and `buildTrailGeoJson` (~325) reads `s("CartoCode")` — that path now matches.

So the query→GeoJSON side is fixed, but the **HTML popup still shows Unknown**. The remaining gap is in the **map HTML's popup JavaScript** — it reads a different property key than what the GeoJSON emits, OR the popup template's carto lookup doesn't match the emitted property name. **This is a contained HTML-side fix, not a rebuild.** Tomorrow: trace the GeoJSON property name `buildTrailGeoJson` emits → what the popup-builder JS in `convoy_map.html` / `grouptrack_map.html` reads (popup builder ~406-415). Make them match. Both HTMLs (CRLF).

## ORDER FOR TOMORROW
1. Session start: recommit + re-pull fresh files if needed.
2. **Help doc up + begin SCREEN CAPTURE pass** (Fred's stated plan). Claude generates the capture-companion: embedded `mkdir` + per-`[screenshot to be added]`-slot `adb exec-out screencap -p > /sdcard/.../NNN_name.png` commands in nav order (from navigation_xref.txt); Claude owns slot filenames. Fred navigates each screen, runs each command, zips + uploads the PNGs; Claude inserts into the slots (+PIL annotate if needed). Manual now has ~22 V2.5 cards (3.0 chapters removed) — fewer slots than the old 41.
3. Popup carto "Unknown" — HTML popup-JS fix (contained; see above).
4. After captures in + popup fixed: re-commit docs with images → **cut the 2.5 AAB (banked fallback)**.
5. Lead-cart [2.1] rebuild LAST, after AAB banked.

## DOCS STATUS (committed today)
- **Manual** `app/src/main/assets/grouptrack_manual.html` — rebuilt from pristine cookbook, edit-in-place lineage, **Chapters 1–3 (account/dashboard/ride mgmt) REMOVED** (V2.5 only). Folds in: icon-nav WWA, universal search, the ONE detail panel + Carto Type, carto-in-popup, the 8-code CartoCode legend, FIT, "?" help, neon arrows, snap-2 routes, viewport persistence, Trail-Sources Select-Source/By-Area reprocess + recap. `[screenshot to be added]` slots preserved.
- **Release notes** `app/src/main/assets/grouptrack_release_notes.html` — rebuilt in **what changed → what it means to you → benefit** format, brief, each entry links into the manual. Install-as-update warning preserved verbatim at TOP (protects ~18 tester DBs).
- Dated copies in `docs/grouptrack_manual_WORKING_2026-06-21.html` + `..._release_notes_WORKING_2026-06-21.html`.
- The two ship as app assets (the "?" button's two options open them); cross-links use bare filenames so they resolve in-app.

## CARRIED ITEMS (full detail in the Living Checklist — do NOT re-derive)
- **Lead-cart tracking REBUILD [2.1]** — one lead / one track / snap-2 100yd / per-cart per-second GPS replacement. MUST-SHIP; attempt LAST after AAB banked. Authority: `GroupTrack_LeadTrackReplacement_Spec.docx` + `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`.
- **Track survey on STOP [7.5]** — V2.5 collect-now; schema finalized (extension db, enjoyment 1-5 + ride_again); feeds upload_queue.
- **Queue panel [3.3]** — backend done; live-data panel UI wiring.
- **Tile concurrency** — V2.5 interim settable (default 4, max 6); 2.6 = batch + AWS.
- **"PROCEED TO UPDATE" mode + inline recap** — the fuller 6/13 flow (button + mode picker + live running recap during reprocess). Basic reprocess-selection now works (Patch O); the richer mode/recap UI is a re-author target if wanted. NOT a blocker — the importer already emits ImportProgress. Banked unless Fred wants it for 2.5.

## BUILD QUICK-REF
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (warm ~12-23 min; cold ~38-60). If incremental changes don't appear (cached classes, "975 up-to-date"), add `--rerun-tasks` to force recompile.
- **GREP-CONFIRM a patch is on disk before building** (`grep -n "<marker>" <file>`).
- Install: `adb -s 8624SBCEDF00001789 install -r -d app/build/outputs/apk/google/release/app-google-release.apk` (Droid 1).
- Device paths in Git Bash need `MSYS_NO_PATHCONV=1` prefix (e.g. `adb shell ls /sdcard/...`).
- Patch files: unique versioned names (`_v2`, `_v3`) so the browser doesn't serve a stale download. Windows path INSIDE the script must be `C:/Users/...` not `/c/Users/...` (Python on Windows).
- Line endings mixed: TrailImporter.kt / ConvoyTrailSourceScreen.kt / SpatialDbManager.kt = LF; ConvoyMapViewerScreen.kt + both HTMLs = CRLF. Patches detect at runtime + count==1 guard.

## TREE — parked (never `git add .`)
`.bak_*` files, `utah_trails_stgeorge.geojson`(+.bak), `grouptrack_spatial.db` (117MB), `docs/.tmp.driveupload/`. Tree-cleanup pending.
