# GroupTrack — Living Master Checklist
**2026-07-29** · branch `feature/convoy-event-ride` · baseline commit `3fd526517`

> Living Master carries **In-Progress + Open only**. Completed work graduates out
> into its own titled doc in AllDocs (see `COMPLETED_TASK_osm_import_c1_c2_2026-07-28.html`).

---

# ⭐ IN-PROGRESS — 2.6e DOCUMENTATION (the code is done)

## Where the OSM pipeline stands tonight
**C1–C4 COMPLETE and committed.** Three commits today, deliberately split so the
worker, the panel and the wiring stay separately revertable:
`3a9d0fbf5` (worker) → `44c7781e7` (C3b panel + area round trip) → `f724d78dd`
(C3c wiring + C4 cleanup). Baseline entering the day: `3fd526517`.

⭐ **First real write to the production spatial DB, and both balance assertions
held.** Utah whole state: trails 89,536 found = 89,527 inserted + 0 aliased + 9
dropped; points 7,722 = 7,679 + 43 (places 3,674 + natural 4,048). Cleanup removed
3 files / 422 MB and kept `utah_2026-07-29T135649.json`.

⭐⭐ **`dropped = 9` reproduces the 07-27 laptop measurement exactly.** Two
independent implementations — the offline Python reference and the on-device Kotlin
dedup — agree. That is what confirms the WKT contract and `geom_hash` identity on
real data.

⛔ **The full C3/C4 design + fix detail is NOT in this document.** It graduated into
**`COMPLETED_TASK_osm_import_c3_c4_2026-07-29.html`**, which carries the working
specification **verbatim** in its appendix plus the as-implemented record. Read it
before touching the OSM panel.

## 🔴 IN-PROGRESS — manual and quickstart
- ✅ **Manual OSM section done** — *Import OSM Data* under Planning Map → G — Work
  with Artifacts, **5 screens → 8**, **18 captures** at 720px WebP q60
  (3.62 → 4.53 MB). Title stays **Import OSM Data**: Fred — *"it is a button name
  just like import trails data."* I renamed it to prose and had to revert.
- ✅ Work-with-Artifacts **recapture done** — three buttons visible, closing the
  do-not-ship caption/image mismatch.
- ✅ Two false statements corrected: *"steps 3 and 4 are not active in this build"*
  and *"you can come back and draw another area from the same download"*.
- 🔴 **NEXT (Fred, 07-30): import OSM for an AREA of Arizona and capture it.**
  New screens, none of which exist yet: row 3 with SELECTED AREA ticked · the panel
  closing and the map jumping to the state extent with the draw panel open and
  Import OSM ticked · drawing the box · the panel reopening with the confirm showing
  the **area** count · area completion recap.
- ⭐ **Capture ONCE, reuse for both scopes** (Fred): rows 1–2 are identical for
  state and area; only row 3 differs.

### ⭐⭐ THE PREDICTION TO WATCH ON THE ARIZONA RUN
Geofabrik ships border ways whole, so Arizona's extract contains the same
**313 UT/AZ border trails** already in the spatial DB from Utah. Arizona should
report a **non-trivial `dropped` count** where Utah reported 9. **If it does, that
is `geom_hash` deduplicating across two independent state downloads — the property
the 50-state plan depends on, and never yet tested.**
⚠ Arizona is a fresh ~610 MB download; Utah's working files are gone.

## 🔴 IN-PROGRESS — the QUICKSTART (Fred 07-29, for 07-30)
Insert the OSM capture alongside the state-trail (UGRC) capture in the
before-you-use-the-app setup instructions.
- Baseline is **`GroupTrack_QuickStart_Installation_Troubleshooting_2026-07-08.md`**
  — the July 8 (V2.6) one, NOT `..._v1.md` (May 9). The newer carries the tile
  migration, the rewritten Device Compatibility section, and First-Time Setup as an
  ordered three-step flow.
- **Insertion point: `First-Time Setup` → step 2** ("Import trails and trailheads,
  and DOWNLOAD MAPS for your ride area"). OSM becomes the second half of that step.
- ⚠ **The quickstart has NO images at all** — plain markdown. Adding captures
  changes its nature: HTML like the manual, or a pointer to the manual's section.
  ⛔ Pointing conflicts with Fred's doc mandate — *"if the living doc references a
  doc I cannot see or get to, it never existed."* **Decide before editing.**
- ⚠ **Stale facts to fix in the same pass:** `Package: com.geeksville.mesh` (wrong
  since 07-26 — should be `com.grouptrack.android`, which the OLDER v1 gets right);
  and *"Planning Map → Trail Sources"* in Troubleshooting, a path that may no longer
  match the three-button Work with Artifacts panel.
- ⚠ **07-08 dropped sections v1 had** (superset rule → deliberate decision, not
  silent loss): Developer Notes, Emergency Recovery, local-APK install with the
  `-r -d` rule, and the Play-Store-vs-local signing-key warning. The audience
  changed ("Tester and Developer" → "Beta Tester") so most looks intentional — but
  **the `-r -d` rule and the signing warning now exist nowhere**, and a tester
  installing a local APK needs both.
- ⚠ Step 2's ordering advice matters more now: with OSM the honest sequence is
  download the extract on Wi-Fi → extract → import → then map tiles. And cleanup
  deletes ~420 MB afterwards, which a tester on a metered connection should know
  before starting.

---

# 🔴 OPEN QUESTIONS — C3b

> ⭐ **STATUS 07-29 (C3/C4 now shipped — full record in
> `COMPLETED_TASK_osm_import_c3_c4_2026-07-29.html`):**
> **(1) RESOLVED** — the confirm dialog shipped, naming the count and the four
> coordinates.
> **(2) REJECTED, do not re-propose** — Fred: a dry run cannot see the case that
> matters, *"an issue in a trail in this run that was simulated as added but is
> duped within db."* `resolveByGeom` and `sourceUidSeen` read **in-memory**
> HashMaps from `beginDedupSession()`, so a dry run reproduces a stale session
> faithfully. It simulates the decision, not the database's response to it.
> **(3) STILL OPEN, and now more urgent** — see below.
> **(4) RESOLVED** — the handoff was built the same day, so the stub never
> shipped.

## 🔴 STILL OPEN — delete-by-source for TRAILS
**89,527 OSM trails are now in the production spatial DB and there is still no
in-app way to remove them.** `deleteAllOsmPoints` exists (unwired) for points;
there is no trail equivalent.

⚠ Recovery today is a **laptop** operation: pull the DBs,
`DELETE ... WHERE source_id='osm'`, push back. Fine on Droid 2 (sacrificial), not
fine for a tester.

⛔ **And it cannot simply be added:** a route's snapped vertices carry `lineId`, so
deleting a trail orphans them silently. That is why bulk delete-by-source was
considered and rejected for the release. ⭐ With OSM coverage now shipping, this
needs a decision rather than a deferral — the exposure grew by 89,527 rows today.


1. **Does whole-state import need a confirmation dialog?** 89,536 trails into the
   production spatial DB with no undo — the only reversal is delete-by-source,
   which does not exist for trails. Extract was recoverable; this is not.
   Recommend: put the count in the dialog — *"Import 89,536 Utah trails into your
   map database?"*
2. **Dry-run mode before the first real write.** Walk all rows, make every
   decision, count everything, write nothing. Gives the full INSERT/ALIAS/DROP
   distribution across a whole state — which a 1,000-row cap cannot, since it
   samples whatever sorts first by rowid.
   > ⚠ A partial write is worse than it sounds: a cap landing mid-page rolls the
   > transaction back and can leave trails without properties rows, which are
   > invisible to the uid set and re-import forever. Dry run has no such edge.
3. **Delete-by-source for TRAILS.** `deleteAllOsmPoints` exists (unwired) for
   points. There is no equivalent for trails. **Should exist before the first
   real write** — otherwise a bad import means restoring the spatial DB by hand.
4. **Area stub must NOT write a pending record.** If SELECT AREA writes
   `scope:"area", selected_area:null` while the map handoff is unbuilt, the panel
   waits forever for a draw that cannot happen — and the only exit is CANCEL,
   which deletes the 630 MB extract. **The stub is a toast and nothing else.**

---

---

# 🔴 OPEN — C2 follow-ups (small, not blocking)

- **C2 does not hash points.** The rule is now uniform (identity = `geom_hash`),
  so C2 should compute one. Measured value is low — 24 exact-coordinate
  collisions across 7,722 Utah points — and C3c computes it at import time
  instead. **Rides along whenever C2 is next touched. Does not justify
  re-extracting every state.**
- **State extent in `subset_meta`.** Considered mandatory, then found not to be:
  `SELECT MIN/MAX` over 89,536 rows is sub-second. `skinnyExtent()` in C3b
  avoids forcing a re-extract of already-built states.
- ⚠ **OSM place names contain characters outside cp1252** (e.g. U+02BC in
  *Naatsisʼáán*). SQLite is UTF-8 so storage is fine, and Leaflet labels will be
  fine — but any future CSV export or `Log.i` on a name must cope. This crashed
  a PC-side analysis script today.

---

# 🔴 OPEN — carried forward (unchanged today)

- **`markComplete` refreshMode collision** — `ConvoyDownloadQueue.kt:641`.
  ONE LINE fix, displaced twice. Strong candidate for both the
  refresh-never-completes report and much of the ETA starvation.
- **Delete patch N** — banding + real progress denominator. Written, not applied.
- **"0 MB reclaimable"** — reproducible after 38,896 and 40,912 tile removals.
  Settle whether `freelist_count` is meaningful before designing any VACUUM flow.
- **SAT frozen at 15**
- **V2.7** — see the **V2.7 ACTIVITY** section below. Vector rendering is now one
  item inside a larger 2.7 scope, not the whole of it.
- **COTREX misconfiguration** — `carto_code` filled with record IDs for Colorado.
- **50-state automation** — after three states import cleanly.
- **AllDocs gaps:** `COMPLETED_TASK_download_queue_schema_and_panel_2026-07-24.html`,
  `process_state_2026-07-27.py`, `Manual_Updates_Needed_2026-07-13`.
  ⚠ Do NOT drop `DOC_MAX_AGE_DAYS` 45→14 until resolved.
  ✅ `osm_gpkg_subset_v2_2026-07-27.py` — located today in Downloads; still needs
  filing into AllDocs.
- **Security (07-27, open):** `151fffb79` removed from HEAD in `ddb48650b`.
  MySQL `info.txt` not yet verified for credentials. Files absent from HEAD and
  working tree. History scrub (filter-repo) = separate decision.

---

# 📘 MANUAL — THE OSM SECTION'S OPENING PARAGRAPH (Fred 07-29, use this wording)

> ✅ **APPLIED 07-29** — this is now the opening tip of the manual's *Import OSM
> Data* section, before the step list, in Fred's wording. Kept here because manual
> work continues (the Arizona AREA path and the quickstart), and the two warnings
> below still govern how the four rows are described.


⭐ **State this UP FRONT in the manual, before the step list.** It is the
user-facing statement of R1 (stage is derived from disk) and it is what stops
people thinking they have to finish in one sitting:

> **Importing OSM trail data is a four-step process, and the system keeps track
> of your progress. Any time you leave — for any reason — you can come back,
> select IMPORT OSM again, and you will be brought to the next step in your
> process.**

⚠ This is not a nicety. The download is ~330 MB and the extract takes minutes;
users WILL leave mid-process. Without this sentence they restart from step 1 and
re-download something they already have.

⚠ Do not describe the four rows as a wizard with a Next button — there is no
forward/back. Each row arms when its input exists and greys when it does not.

---

---

# 🟡 MANUAL — OSM WALKTHROUGH SWEEP: FULL STATE DONE 07-29, AREA PATH OPEN

✅ **Full-state path complete.** Fred's requirement (*"capture the entire OSM process from websites through complete import"*) is met for whole state: the Geofabrik site (4 shots including which link to take), the panel at each of the four steps, the scope choice, the confirm with its count, the completion recap, cleanup, and the before/after map pair. **18 captures embedded**, section went 5 screens → 8.

⭐ **Keep the `.gpkg.zip` close-up prominent.** The page also offers `.osm.pbf` and `.shp.zip`; either means waiting on a large download and then being told no extract was found.

🔴 **Outstanding:** the **AREA path** (Arizona, 07-30) · the **adoption moment** (slide 7 was absent from the 07-29 set) · the **CANCEL confirm** (deliberately not captured).

⚠ **Verify the before/after Kanab captions** — those two images rendered blank to Claude, so the captions follow Fred's filenames, not observed content.

The original capture list is retained below; tick through it for the area run.

Fred 07-28: *"we will need to capture the entire OSM process from websites through
complete import before the final manual is completed."*

⚠ **The 8 `PENDING-CAPTURE` markers in `grouptrack_manual_2.6e.html` cover only the built
half** (C1 + C2 + CANCEL). A website-to-imported-trails walkthrough needs C3 and C4 screens
that **do not exist yet**, so the sweep cannot complete until those are built. Do not attempt
it piecemeal — a half-captured walkthrough is worse than none, because the gap is invisible
to a reader following along.

## Full capture list (~20 screens)

**Off-device — the part currently missing entirely**
1. Geofabrik site as it opens from OPEN GEOFABRIK
2. Geofabrik region/state page — *which* link to take (`.gpkg.zip`, not `.osm.pbf` or `.shp.zip`)
3. Browser download in progress, and completed

**C1 ACQUIRE — built, markers in place**
4. OSM panel, generic — `IMPORT OSM TRAIL DATA`, gate button under row 1
5. "No Geofabrik extract found" dialog (tapping the gate too early)
6. Panel after adoption — `OSM TRAIL DATA - UTAH`, row 1 ticked, gate gone, row 2 green

**C2 REDUCE — built, markers in place**
7. Extract running — four per-type rows, one mid-progress with % and time left
8. Extract complete — row 2 ticked, rows 3–4 armed

**C3 IMPORT — ⛔ NOT BUILT, cannot capture yet**
9. Row 3 choice dialog — WHOLE STATE / SELECT AREA
10. Whole-state confirm showing the trail count
11. Panel closed → planning map with **Import OSM** checked in SELECT TILES / ARTIFACTS
12. Area drawn — bbox display reading `OSM: 8,412 trails in area`
13. Panel relaunched, import running with progress
14. Import complete — row 3 ticked, row 4 armed
15. ⭐ **OSM trails rendered on the planning map** — the payoff shot, and the one that proves
    the whole chain to a reader

**C4 CLEANUP — ⛔ NOT BUILT**
16. Row 4 summary showing the run totals
17. Cleanup confirm
18. Panel returned to the start

**Recaptures**
19. ⚠ **Work with Artifacts panel** — the existing image predates 2.6e and shows TWO buttons
    where the caption now says three. Already marked. **Must not ship as-is** — a tester reads
    three, sees two, files a false bug.
20. CANCEL confirm dialog (marker in place)

## Capture standards
720px wide WebP · ~110KB ceiling at q60 (satellite-heavy frames will not hit 80KB without
becoming unreadable — legibility wins) · invisible `PENDING-CAPTURE` HTML comments, **never
visible banners**.

⚠ After every structural manual edit, verify: `<details>`/`<summary>` open-close balance ·
image count · file still ends `</html>`. Baseline after the 07-29 pass: **`<details>` and `<div>` balanced (verified against the pre-edit file), 90 images (72 jpeg + 18 webp), 4.53 MB**.

⚠ This sweep folds into the **end-of-V2.6 manual pass**, alongside the install/quick-start
section and the existing 18-screen sweep — one pass, not two.

---

# 🟠 FIX 6 — PADDING DRIFT: what 07-29 established

Fred priority since 07-22 (*"annoying continually entering a shrinking panel"*).
Not fixed today, but the mechanism is now pinned and two prior descriptions of it
were wrong.

## ⭐⭐ THE RULE, STATED (Fred 07-29)
> **The 10% pad is for FIT-TO-ARTIFACT POSITIONING ONLY. It must NEVER be applied
> on a load driven by the JSON.**

Content-fit pads — framing an artifact you just selected needs breathing room.
Restore does not — the stored box is already framed, and padding it again
compounds. **The defect is not that MVS:1475 pads; it is that padding is baked
into the fit instead of being the caller's decision.**

Fred's 07-13 fix is exactly this and still stands: make it an explicit argument —
`fitToBounds(bbox, padding)`. Restore passes **0**, content-fit passes **N**. If
the function is shared with convoy, default the arg to the content-fit value so
convoy's call is unchanged — **no convoy edit, freeze respected.**

## ⭐ THE LOOP IS VISIBLE IN ONE LINE
`ConvoyMapViewerScreen.kt:1475`:
> `[FIT recenter 2026-06-20] Restore-to-artifact: bbox+10% pad -> lastViewport -> fitBounds`

It writes the **PADDED** box into `lastViewport*`. `savePlanningState()` persists
`lastViewport*`. The next restore pads **that**. Frame grows 10% per reopen.

⚠ **The drift comes from padded values being SAVED, not from the fit function.**
`fitBounds` itself takes no padding argument — which is why "fitBounds doesn't pad,
so we're safe" was wrong, and why removing the pad at the SOURCE is the fix rather
than avoiding the fit.

## ⛔ CORRECTION — "convoy has cumulative zoom-out" IS MISLEADING AS WRITTEN
Recorded against convoy since 07-12 and still in [[convoy-map-frozen]]. Fred 07-29:
**convoy always re-focuses to current GPS, so the saved frame is overwritten by
position updates before drift can accumulate.** The mechanism exists in convoy; the
SYMPTOM does not.

⭐ Two consequences:
- **FIX 6 only has to be correct on the PLANNER.** Convoy's call keeps its default.
  That is what makes the explicit-argument approach a no-convoy-edit fix.
- It explains the reporting asymmetry: convoy's self-saving `setView` is harmless
  *because GPS overwrites it*; the same behaviour on the planner is corrosive.

⚠ Anyone reading the convoy freeze note will hunt a symptom GPS hides.

## THE PLANNER HAS TWO FIT IDIOMS, AND THEY DISAGREE
| Site | Idiom |
|---|---|
| MVS:665 | `fitBounds` alone — relies on `moveend → onViewportChanged` (comment at :669 says so) |
| MVS:1475 | sets `lastViewport*` **manually**, then fits, then reports at :1440 |
| MVS:687 | `setView(lat, lon, 15)` — no report visible |

⚠ **If some callers report and others do not, the drift is not uniform** — it
depends which path last touched the map. That plausibly explains why this has been
hard to pin down. **Establish which idiom is authoritative before writing FIX 6.**

## THE PERSIST CHAIN (confirmed 07-29, three hops)
`onViewportChanged` (MVS:559) sets `lastViewport*` → `viewportSaveHandler.postDelayed(viewportSaveRunnable, 400)` (MVS:284-285) → `savePlanningState()` (MVS:259) writes `planning_panel.json`.

⚠ **`savePlanningState()` reads `lastViewport*`, and ONLY `onViewportChanged` sets
them.** So calling `savePlanningState()` directly after a programmatic move
persists the **STALE** viewport. The report is the correct hook, never a shortcut
around it.

## WHAT THE OSM PATH DOES NOW (2.6e)
Passes the **raw** extent — no pad — then reports the viewport explicitly and lets
the debounce save, then opens the draw panel. ⭐ **Removes OUR contribution to the
drift; does not fix the mechanism.** FIX 6 still owns the restore-path re-pad.

⚠ Fred accepted a single 10% shrink as bounded *"it only enters once"*. **That
holds only while entry is once-per-import** — several areas drawn in one sitting
would be several entries.

---

# 🟡 PARKED — noticed 07-29, not chased

- ⚠ **`bad_geometry: 89394` in the Utah extract record** sits suspiciously close to the
  ~89,390 rows `gis_osm_natural_free` drops for blank names. 07-28 flagged exactly this:
  *"`badGeometry` and `droppedNoName` must be counted SEPARATELY or a real decode failure
  hides inside 89,390 rows of expected filtering."* There is a comment at
  `OsmExtractWorker.kt:156` about what the field means. **If the two are summed, a genuine
  decode failure is invisible.** Not a C3b blocker.

---

## 🔴 THREE PLANNER ISSUES — surfaced 07-29 by OSM coverage, NOT chased
⭐ **All three appeared the moment coverage nearly doubled** (49,125 UGRC → 89,527
OSM trails). Nothing in the 07-29 patches touched snap, route drawing or waypoint
placement — these are **pre-existing fragilities that only became visible at real
data density.** Fred: *"tracks and trails are virtually on top of each other."*

**(1) Snap should NOT resolve against trails when drawing on a track.** Fred:
*"snap2 should not plot against trails."* `RouteManager.snap(lat, lon, trails,
tracks, 30.0)` ranks both. At OSM density several lines sit inside 30 ft and
nearest-wins flips tap to tap. ⚠ This is the hysteresis/continuity gap already
flagged in the snap-2 spec, arriving earlier than expected.
⚠ There is a **correctness** argument too, not just tuning: a snapped vertex stores
`lineId`, and the lead-cart design has followers sharing that ID. A route snapped
to a **track** carries a per-rider ID, not shared geometry.
⛔ Read `RouteManager.snap`'s ranking before proposing anything — a preference order
may already be expressible in how it is structured.

**(2) Dropping a point selects the WHOLE TRACK** — with tracks, not trails.
Candidate cause: `onProximityTap` in MVS uses `val radius = 0.002` (**~200 m**) and
queries ALL artifact types, so a tap meant as a route vertex hits a track artifact
and triggers selection. ⚠ Candidate, NOT a diagnosis.

**(3) Waypoints cannot be dropped outside route mode** — likely the same tap
consumed by proximity selection before waypoint placement gets a chance. The most
likely genuine bug of the three.

### 💡 THE DIAGNOSTIC NOT YET RUN
Try all three where OSM added little — ground UGRC already covered. Normal there +
chaotic in dense areas = tolerance/radius. Chaotic everywhere = something else
changed. **Five minutes, and it decides whether the fix is a number or a
redesign.**

### ⚠ ALSO UNEXPLAINED
`bad_geometry: 0` on the 07-29 extract against **89,394** on 07-28, with identical
`kept: 89536` and `candidate_rows: 186652`. Either the counting changed between
runs, or the earlier figure was the `droppedNoName` collision flagged that morning.

---

# 🔵 V2.7 ACTIVITY — design objectives, awaiting detailed implementation

Captured 07-29. **These are objectives with reasoning, not specifications.**
Each needs a detailed implementation pass before any code.

⚠ **Why this section exists.** The convoy tracking rewrite was assigned to 2.7
in session more than two weeks ago and **never reached AllDocs** — 07-29 was spent
reconstructing it from `GroupTrack_LeadTrackReplacement_Spec.docx`, the Lead Cart
Lock section, and MAP-06 instead of reading it. Fred 07-29: *"i am sure not doing
this resulted in dropping the convoy map redo."* **Discussions get written when
they happen, not at EOD.**

⚠ **Numbering collision, unresolved.** `GroupTrack_Product_Roadmap_V9.docx`
(2026-03-25) runs V2.4 → V2.5 → V3.0 → V4.0 and has **no V2.6 or V2.7**. Its
"V2.6" means *server/cloud processing activated behind the paygate* — what current
work calls V3.0. Separately, most "2.7" hits in AllDocs are **Meshtastic firmware**
2.7 (`ConvoyRulesEngine27.kt`), not a GroupTrack release. The roadmap doc and the
working numbering now mean different things by the same label.

---

## 1. CONVOY TRACKING REWRITE — the originating requirement

Fred 07-29: *"we froze to deal with lead track issue in 2.7 with a rewrite to deal
with the hornet's nest of convoy functionality."* May take 4 weeks. **The freeze was
a deferral with a named payload, not just caution** — which makes lead track
correctness the scope test: other work joins 2.7 only by being unavoidably in the
same tangle, not by being deferred convoy work in general.

⭐ The freeze's own precondition (07-12) — *document the layered process first,
don't patch blind* — is therefore **2.7's first deliverable**, not paperwork. The
walkthrough of Kotlin↔JS↔Leaflet↔self-saving-`setView`↔`MapStateStore` is the map
of what the tick loop touches.

### 1a. Lead track replacement
Source: `GroupTrack_LeadTrackReplacement_Spec.docx` (2026-05-31).
Today the lead track is assembled from **three parallel segment flows**
(`routeTrailSegments`, `gpsTrailSegments`, plus a lead-only filter), coloured
per-node, then drawn. Replacement collapses to **one growing polyline**: take the
locked lead's position each tick, append, `pushTrackToMap()` (net-new, 0 refs today).
The `trackLeadOnly` toggle disappears — always lead-only by construction.
⭐ Diagnosis on record: phantom lines traced to **multiple segment writers**;
collapsing to one writer removes the class of bug.

### 1b. Lead cart lock — "In Progress (Not Yet Built)"
`ConvoyEngine` re-derives lead every tick via `minByOrNull { it.convoyPosition }`.
On switchbacks at low speed it oscillates between carts; every lead change draws a
new line from the new lead's position → spaghetti on tight sections.
⚠ **Explicitly NOT GPS sampling or packet delay.** Fix sketched at
`ConvoyEngine.kt:92` via `explicitLeadId`. Each device derives and locks
independently — no mesh packet, converging after ~60s of movement.

### 1c. Snap-2 on the lead cart track
Specced at 3.9 (`ConvoyTrailSnapper.kt`: `snapPoint` / `interpolateSegment` /
`loadTrailGeometry`), **50 ft tolerance**, applied between GPS receipt and rendering.
3.10 specs **post-ride** smoothing separately — both were designed, they are not
alternatives.
⚠ **The spec's data source is STALE.** It loads `trails/{bound_hash}.geojson`
delivered from EC2. That predates the spatial DB — repoint to `SpatialDbManager`.
The algorithm is sound; the plumbing is not.
⚠ Live snap differs from route snap in kind: continuous, unattended, on moving
GPS, and **the stored `lineId` is what riders behind you navigate by**. Needs
continuity/hysteresis — once snapped to X, prefer staying on X rather than flipping
between parallel lines each tick.
⛔ **NEVER snap against vector tiles.** MVT feature IDs are not stable across tile
builds → stored `lineId`s silently stop resolving. Fatal on its own, and now
load-bearing for a shared feature rather than a private draft.

### 1d. Vector rendering / MapLibre
Scoped 07-26 (`SCOPING_v2.7_vector_rendering_2026-07-26.html`). Now **one item
inside 2.7**, not its whole scope. Sequencing unchanged: **planning map first**
with MapLibre on existing raster + existing GeoJSON, convoy last.
⚠ Convoy now carries **three stacked unknowns** — new renderer, rewritten tick
loop, snap on live GPS. That makes the planning-map-first rule more necessary, not
less: otherwise a bad snap is unattributable between three suspects.

### 1e. `spatial_artifacts.js`
Orphan; both HTMLs carry inlined divergent copies (convoy 9 `trailLayer` refs,
planner 15). **2.7 names it as the rewrite target, so 2.7 starts from stale code.**
⚠ 2.6e's places-label layer gets hand-mirrored into those same two HTMLs — which
either adds a third divergent copy for 2.7 to unpick, or gets written once in
`spatial_artifacts.js`. Decide before the label build, not after.

---

## 2. ROADS / STREET NAMES — ingest only

Fred 07-29: *"we are talking data imports to spatial only, not the usage."*
Line drawn 07-27/28 after researching planner + convoy HTML state: **places and
features are achievable in Leaflet today; street names require MapLibre.**

- **Ingest is a catalog entry.** `gis_osm_roads_free` into `osm_layers.json`;
  C1/C2/C3 are catalog-driven and continue to function.
- ⚠ **Destination is new schema.** Roads are lines. NOT `reference_points` (points).
  NOT `trails`/`trail_properties` — **snap resolves against that table, so routes
  could snap to a highway.** New table, `CREATE TABLE IF NOT EXISTS`,
  `UNIQUE(geom_hash)`, no migration, no `schema_version` bump — same pattern
  `reference_points` used.
- **Display is MapLibre**, 2.7. Text along curves; nothing about ingesting roads
  changes that.
- ⚠ **Size unmeasured.** Arizona `gis_osm_roads_free` = **1,205,574 rows** against
  134,242 trails. Road labels need line geometry for placement — potentially several
  hundred MB on a 108 MB skinny. Two counts still outstanding (non-blank `name`,
  non-blank `ref`); gpkgs are on `D:`, seconds of work.
- ⭐ **`ref` is the interesting middle option** — forest/county road numbers
  (FR 123, CR 45) are worth more for OHV than street names, a far smaller set.
- ⏰ **TIMING IS THE ONLY URGENT PART.** C4 deletes the zip. A catalog entry added
  *before* users clean up costs skinny growth; added *after*, it costs every user a
  ~600 MB re-download per state. Decoupled from the display question.

---

## 3. REPROCESS / REFRESH DRIVER

Fred 07-29: *"we have all the data in the jsons... continue unattended as all data
is known to old json to drive new process."*

**One driver, two triggers:** catalog change asks *which states lack layer X*;
a date trigger asks *which states are older than N days*. Same worklist, same replay.

- ⛔⛔ **SUPERSEDED SAME DAY — read the amendment below before using this.**
  ~~**Group by STATE, replay by AREA.** The expensive unit is the download
  (per state), the cheap unit is the import (per area). Acquire once → replay every
  area record for that state → cleanup once. **Cost scales with states, not imports** —
  which is what makes catalog expansion controllable.~~

  ⚠ **AMENDED 07-29 (C4 as shipped).** Fred reversed multi-area-per-download later the
  same day: *"no multi areas from one download. areas are downloaded to support a ride
  and we are killing downloads after import process."* Rationale: *"3 minute download vs
  cascading complexity for imports."*
  **Consequence for this driver: the cost model moves from PER STATE to PER IMPORT.**
  Grouping by state buys nothing once cleanup runs after every import — the extract is
  gone, so each replayed area re-acquires.
  ⭐ **But it also SIMPLIFIES the driver:** per-import acquire is uniform, with no
  grouping logic and no shared-resource question about whether anyone still needs the
  file. More bytes, less reasoning.
  ⭐ **And a whole-state replay may make area replay moot entirely** — if the driver is
  re-downloading regardless, one whole-state import supersedes every area record for
  that state, since dedup handles the overlap. That would let the worklist come from
  *"which states have OSM trails in the spatial DB"* rather than from history files.
  ⚠ **Re-derive the 50-state feasibility number against per-import cost** before
  committing to unattended refresh — the old figure assumed one download per state.
  💡 The **optional-retain seam** marked in C4's cleanup code is the lever if this
  proves too expensive: keeping the ZIP alone (~330 MB) makes a replay a 40-second
  re-extract instead of a 3-minute download.
- ⭐ **Replay order does not matter.** Overlapping areas hash-match and drop, same
  mechanism as the 313 UT/AZ border trails. The driver needs no geometry reasoning.
- ⚠ **A re-import is NOT enough — it is re-acquire → re-extract → re-import.** The
  skinny is built from the catalog at C2; if roads were not in the catalog then, the
  skinny has no roads table and importing again gets nothing.
- ⚠ **The HTTP fetcher is the only net-new piece.** C1 today **ADOPTS** a
  browser-downloaded file via MediaStore — nothing in the app retrieves from
  Geofabrik. Unattended multi-state needs a resumable fetcher with the same
  `.part` + atomic-rename discipline R3 requires.
- ⚠ **Cleanup-between-states is a property of the DRIVER**, not a reversal of C4's
  explicit-cleanup decision.
- **Date trigger basis:** ~0.08% of Utah's trails changed in two days (18 removed,
  58 edited). Argue the interval from that, don't pick it.
- ✅ **RESOLVED 07-29: `imports[]` DOES record the bbox.** `appendImport` takes
  `bboxOrNull: String?` and writes `o.put("bbox", bboxOrNull ?: JSONObject.NULL)`.
  Area imports are replayable; the driver has what it needs.
- ⚠ **BUT there are TWO bbox representations in one feature, and they may disagree.**
  The **pending** record is a JSON object keyed `s`/`w`/`n`/`e`
  (`setPendingImport`, and `OsmImportWorker:102` destructures `(s,w,n,e)` to match).
  The **history** record is a STRING from `bboxJson(s,w,n,e)` — while
  `OsmImportStage.kt:36` documents *"west,south,east,north"* as "the form the ledger
  records", which is the OPPOSITE order. **Unread — `bboxJson` body not yet inspected.**
  If they disagree, a replay driver reads one order and writes the other, producing a
  well-formed record pointing at a garbage region with nothing reporting an error.
  Same silent-failure class as the WKT contract. **Check before writing any replay code.**
- ⚠ **Dead comment, will mislead:** `OsmImportLedger:163` says *"bboxOrNull is null for
  a whole-state import; that null IS the record of scope."* The worker never does that —
  `:145` and `:246` both pass `bboxJson(s,w,n,e)` unconditionally, because `pendingBbox`
  always returns a real box. `scope` carries the distinction.
- **Open product question:** if re-downloading a state anyway, a single whole-state
  import supersedes every area record for it (dedup handles overlap). But a user who
  drew areas may have done so to keep the spatial DB small. Not a technical call.

---

## 4. REMOVALS AND EDITS — OPEN, NOT DECIDED

Fred 07-29: *"a state delete that only affects displayed trails."* Needs discussion.

- **`osm_id` discriminates edit from removal; `geom_hash` cannot.** An edit produces
  a new hash, so old-gone/new-present is indistinguishable from remove+add by hash
  alone. The 07-28 comparison already proved the discrimination works: 58 shared
  `osm_id` with disagreeing hash = edited. Detection must run on
  `source_unique_id`, never on hash.
- ⚠ **Only WHOLE-STATE scope can soundly retire anything.** A set difference is
  valid only across the scope refreshed — everything outside an area is absent from
  that skinny but obviously not removed. **Area refreshes can add; they cannot retire.**
- ⛔ **`geom_hash` identity carries no source**, which is what makes a UGRC trail and
  an OSM trail over the same ground **one row with two names**. A row flagged
  removed-from-OSM may be jointly owned — **deleting it destroys curated data.**
  Any delete-by-source must check for other sources aliasing that row first.
- ⚠ **Edits threaten snap more than removals do.** 58 edited vs 18 removed in two
  days (~3×). Each edit adds a **near-parallel duplicate metres from the original**,
  and nearest-wins snap then has two candidates for the same ground — the condition
  already flagged as a V3 sharing concern (two riders, same trail, different
  `lineId`). Stale removed trails just sit there; edit-duplicates **compound per
  refresh cycle**.
- ⭐ **Shape worth considering: MARK, DON'T DELETE.** Routes resolve by `lineId` via
  `queryGeomByIds`, so a retired trail still draws inside every saved route that
  references it — it just stops being a snap candidate and stops rendering as a
  discovery layer. Nothing orphans, and it handles the edit case cleanly (new row in,
  old row retired). Same principle as collapsing points at draw time rather than at
  ingest: **don't destroy where it isn't reversible.**
- ⚠ **Refresh as currently designed is ADDITIVE ONLY.** `updateExistingFeature` never
  touches geometry and Fred's call was insert-as-second-trail (routes snap by
  `lineId`; replacing geometry orphans saved routes). So edited trails accumulate and
  removed trails are never removed. Not a blocker — but it is the design question the
  implementation must answer.

---

# 🟣 V3.0 — SAME-RIDE TRACK DEDUP (server-side, shape settled 07-29)

⚠ **This is a HOSTING problem and only exists once tracks are served.** Locally,
ten riders each holding their own recording of one ride is CORRECT. On the server
it is ten near-identical downloads for one piece of trail. Fred 07-29: *"when we
host tracks and serve them to users to be downloaded we need to prune dupes as
best we can."*

## ⛔ Why `geom_hash` cannot do this job
Identity by hash is exactly right for TRAILS — 89,536 rows / 89,536 hashes, and
the 313 UT/AZ border trails collapsing automatically prove it. **It is useless for
TRACKS from one ride:** every device drops GPS points at different moments, so ten
recordings of the same ride produce ten different point sets and ten different
hashes, all legitimately the same ride. What is built today is
`UNIQUE(geom_hash)` on `tracks` + `INSERT OR IGNORE` — sound for identical files,
**silent on ten riders recording together.**

Recorded in AllDocs as *"same-ride cross-device matching = fuzzy method TBD (V3.0
design block; **exact hash insufficient**)"* — the main unsolved piece of the
sharing model.

## ⭐ THE SHAPE (settled 07-29 — narrower and more buildable than "solve matching")
- **COLLAPSE FOR PRESENTATION, DO NOT DELETE.** The sharing model already has the
  mechanism: **canonical track per hash, `hash + date → alias`.** One canonical
  geometry is surfaced; the others are retained as aliases. **Nobody loses their own
  ride, and the collapse stays reversible if the matching is wrong.** Same principle
  as collapsing points at draw time rather than at ingest.
- **SERVER-SIDE, not on the phone.** A fuzzy metric across a growing corpus is
  exactly the work a device should not do — and the server sees all ten recordings
  while each device sees one.
- **SCOPE IS BOUNDED, which makes it tractable.** Same-ride candidates share a bbox
  and a date window, so the expensive comparison only ever runs against a handful of
  rows, never the corpus.
- **GOAL IS "PRUNE AS BEST WE CAN", not perfect matching.** Stated deliberately.

## ⭐⭐ SNAP-2 SHRINKS THE PROBLEM BEFORE MATCHING STARTS
Already on record: *"snapped tracks converge toward identical geometry, making
same-ride recordings more hash-matchable. Revisit once snap-2 lands."*

⭐ **The thread runs across three releases:** 2.6e OSM import extends trail
coverage → snap reaches ground it previously could not → 2.7 lead-cart snap-2
makes recorded tracks converge on shared trail geometry instead of each rider's GPS
scatter → a chunk of V3.0's fuzzy-matching problem becomes **hash-matchable
outright**, and the fuzzy metric handles the residue rather than the whole job.
**This is why snap-2's value is not only route drawing.**

## Candidate metrics (none chosen)
- **bbox overlap + a distance metric** (Hausdorff / Fréchet) — on record.
- **rounded / simplified-geometry hash** — on record.
- **start/stop coordinates + time window** (Fred 07-29) — not previously on the
  list, and **cheaper than either of the above**. ⚠ Needs tolerance on both axes:
  riders start recording at different moments (parking lot vs trailhead), and anyone
  who turns back early has a genuinely different endpoint while having ridden the
  same ride. Still misses partial overlaps.

## Related, already settled
- **The date that keys an alias = track/ride CREATION date**, not upload/processed
  date, so the same ride re-uploaded later does not claim a new alias slot.
  *(marked "leaning; CONFIRM" in the source doc — still unconfirmed.)*
- ⚠ **Two riders on the same ground can store different `lineId`s** (snap takes
  whichever is nearest). Fred 07-29: for DRAWING it does not matter — the line lands
  on the trail either way. **It matters only when two routes are COMPARED**, which is
  this same V3.0 problem seen from the route side. Fix at compare time by geometry
  proximity, never by making snap pick differently.

---

# ⛔ FROZEN / DO NOT TOUCH

- **Convoy map** — frozen 07-12. The freeze is about a **LAYER, not a FILE**:
  the Kotlin↔JS↔Leaflet↔self-saving-`setView`↔`MapStateStore` tangle.
- ⚠ **`spatial_artifacts.js` is an ORPHAN.** Discovered 07-28: the file exists in
  assets, **nothing loads it**, and both `convoy_map.html` (9 `trailLayer` refs)
  and `grouptrack_map.html` (15) carry **inlined divergent copies**. The V2.7 plan
  names this file as the thing it rewrites — so 2.7 would start from stale code.
  **Decide: delete it, or keep it updated as canonical.** Current state is the
  only unacceptable one. **Do not unify the copies now** — that work gets deleted
  by the MapLibre swap.
