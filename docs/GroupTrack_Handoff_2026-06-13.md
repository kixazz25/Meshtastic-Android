# GroupTrack — Handoff / End-of-day recap — 2026-06-13

_Note-to-future-Claude. Read this + `GroupTrack_V25_LivingChecklist_CONSOLIDATED_2026-06-13.md` first next session. Project runs out of Fred's machine (`~/Meshtastic-Android`, branch `feature/convoy-event-ride`, package `com.grouptrack.android`, RELEASE builds). The container does NOT have the repo — Claude gives commands, Fred runs them one at a time and pastes results. CURRENT doc baseline = this 2026-06-13 set. Build on Fred's UPLOAD, not the Drive connector (which lags ~a week)._

## ⚡ START HERE — TOMORROW'S BUILD SEQUENCE (Fred locked, in this order)

This is a BUILD day. Finish the design while the app + intent are fresh. Three items, in order:

1. **[2h.1] FINISH SEARCH-FUNCTIONAL ISOLATION — the search function is NOT usable as it stands; this is the priority.** Add a SEARCH launch button to the Work-with-Artifacts panel; tapping it COLLAPSES that panel and LAUNCHES an independent small-footprint search surface. Result list → tap → detail overlay; close → back to list (list intact). The "FIT → exit search + center artifact on map" behavior is the seam where [2h.2] plugs in — build the surface + flow now; that one exit-behavior lands when FIT is real. **Mostly a REHOST, not new logic:** the SearchBlock + ResultsList + detail card + onResultClick already exist and committed (`168778c0a`); move them into the collapsible independent surface + wire the launch button. (Checklist 2h.1, spec locked.)
2. **[3.9a] ARROW DENSITY — the simple fix.** Decorator percent-repeat → PIXEL-repeat (`repeat:'12%'` → `repeat:<px>`, e.g. 80) so arrows show at riding zoom on 60–70mi trails. Bundle with [3.9] redraw-timing (`map.fire('moveend')` in showTracks). Two CRLF map HTMLs (differ in script-tag style — eyeball before build) + vendored leaflet.polylineDecorator.js. One arrow pass, one build. (Checklist 3.9a; design doc GroupTrack_TrackArrowDensity_3.9_followup_2026-06-12.md.)
3. **[2h.2] ARTIFACT DISPLAY CENTERING ON MAP — FIT, BOTH surfaces (convoy + planner).** Replace the FIT placeholder: wire the existing `onFit` callback (currently logs only) → map JS `fitBounds([[min_lat,min_lon],[max_lat,max_lon]])` on the 4 bbox values the detail card already shows. **Two-surface wire — pass onFit from BOTH `ConvoyScreen` (convoy map) AND `ConvoyMapViewerScreen` (planner map)**, same as the detail card landed on both; bbox-direct path is identical on each. Self-contained, does NOT touch [3.1] persistence. Completes 2h.1's "FIT exits + centers." Real function = `ConvoyArtifactOps.fit(type,id)` (ConvoyArtifactOps.kt:19, currently 0 callers/stub). (Checklist 2h.2.)

**FIT note (resolved — don't re-open):** FIT was always on the list as **2h.2**, committed as a future item. Fred's original ask was simply to sequence it after the search-isolation work — that's what this section does. An earlier claim this session that "FIT isn't on the checklist" was wrong (grepped a stale 06-11 file). FIT is item 2h.2; proceed. Today also added source anchors to 2h.2 + a new **2h.3** (ConvoyArtifactOps sibling-audit) + a completeness caveat on the checklist — those are side notes, NOT tomorrow's work.

## WHAT COMMITTED 06-13 (context — for reference, all device-tested)
- **[2h] SEARCH layer** — committed `4f7abbbb7` (search-by-name all 4 types, dropdown + Enter, device-proven).
- **[2h] DETAIL CARD** — built + device-tested 100% correct, committed **`3cdf118da`** (detail card) + **`168778c0a`** (properties-merge + technical accordion). Live detail surface = `ArtifactListPanel.kt` (hosted ConvoyScreen:1651 + ConvoyMapViewerScreen:1221); orphan `ConvoyArtifactDetailPanel.kt` deleted. **[11.2] now DONE.** Spatial row controls everything except aliases (data-DB read; handle = `extensionDb`, NOT `dataDb`).
- Detail card known follow-up = the footprint issue (card + artifacts panel cover ~2/3 of map) → tracked as **2h.1 SEARCH-SURFACE RESTRUCTURE** (spec locked, OPEN — tomorrow's lead task). Its one FIT-dependent seam ("FIT exits + centers") lands when 2h.2 is wired; the rest of 2h.1 is independent and built first.
- Verified clean: **Jordan River Trail = 314 segments**, 314 distinct geom_hashes, 0 dupes — identity model correct. Surfaced **11.1a** (raise SEL/Edit list cap 200→400 stopgap; real fix = [11.1] paging).

## AFTER the three (pre-AAB gates + side notes)
- Pre-AAB gates still open: **[8.7]** About/Attribution, **[8.9]** first-launch release-notes gate. **Release HALTED until Fred says ship.**
- **Completeness check (side note, NOT a blocker):** the checklist's "no-drop" claim was never verified against its 8 source docs; a **⚠ COMPLETENESS — NOT YET VERIFIED** section was added 06-13 listing what to diff (06-06 v3, master, V30_StubInventory, backlog, state-of-play docs) + the **2h.3** ConvoyArtifactOps sibling-audit. Do this when convenient — it does not gate the build sequence above.

## TREE STATE
- HEAD = **`4f7abbbb7`** ([2h] search layer) on top of `03c9e1a56` → `bcd5f8e31` → C-1 → [3.9]+[3.8].
- Working tree carries **cram1 + cram2 applied-uncommitted** (SpatialDbManager.kt, ArtifactListPanel.kt) — these are the in-flight detail card; they build green only after pieces 3+4.
- Plus PARKED weekend state — leave it: `M utah_trails_stgeorge.geojson`, `?? grouptrack_manual.html`, `?? grouptrack_release_notes.html`, `?? utah_trails_stgeorge.geojson.bak` (NEVER git-add the `.bak`), `D docs/.tmp.driveupload/10630`. **Commit only explicitly-named files, never `git add .`.**

## TERMINAL / WORKFLOW NOTES (carried)
- Line endings: `SpatialDbManager.kt` + `ConvoyArtifactsPanel.kt` = **LF**; `ConvoyScreen.kt` + `ConvoyMapViewerScreen.kt` + `ArtifactListPanel.kt` + map HTML = **CRLF**; manifest LF. `cat -A` / check before patching.
- Single-line `python3 -c` anchors with match-count guard (write only if count==1 else ABORT). Git-Bash mangles `!!`; verify by byte size, not `git diff`.
- **Confirm live field/handle names before writing patch code** (caught `extensionDb`-not-`dataDb` this way).
- Build ~10–34 min: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease`; APK at `app/build/outputs/apk/google/release/app-google-release.apk`; install `adb -s 24039703201775 install -r -d <apk>` (Droid 2 = dev/test `24039703201775`, Droid 1 = field `8624SBCEDF00001789`). Laptop `curl` fails TLS — Claude-generated files via the file panel → Fred downloads → `cp` into place. Remote /sdcard adb needs `MSYS_NO_PATHCONV=1`.

## DOC HYGIENE
- This EOD set (handoff 06-12, checklist CONSOLIDATED 06-12, manual 06-12, release notes 06-12) produced as full updated files from the 06-11 baseline. **Checklist/manual/release-notes changes tonight are STATE-ONLY** — they reflect that search shipped + the detail card is in flight; they do NOT yet mark [2h]/[11.2] DONE (the card isn't built). Mark those complete next session after the cram builds + device-tests.
- Decision Log still owes dated append blocks for 06-07, 06-10, 06-11, 06-12 sessions — batch item [9.6].
- EOD memory restructure (split the 32KB grouptrack-v25.md into topic files + detail docs) still pending — do it in an EOD pass, not mid-build.
