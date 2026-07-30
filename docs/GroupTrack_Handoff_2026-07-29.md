# GroupTrack — EOD Handoff
_2026-07-29 · branch `feature/convoy-event-ride` · HEAD `f724d78dd`_

Internal doc — resume context for Claude, not user-facing. More detail is better
than less.

---

## HEADLINE

**OSM trail import is COMPLETE end to end. The first real write to the production
spatial database landed today and both balance assertions held.**

```
Utah, whole state
  trails   found 89,536 = inserted 89,527 + aliased 0 + dropped 9   ✓
  points   found  7,722 = inserted  7,679 + dropped 43              ✓
           (places 3,674 + natural 4,048 = 7,722)                   ✓
cleanup    3 files, 422 MB removed; record kept as utah_2026-07-29T135649.json
```

⭐⭐ **`dropped = 9` reproduces the 07-27 laptop measurement exactly** ("Utah 9
hash matches out of 89,554"). Two independent implementations — the offline Python
reference and the on-device Kotlin dedup — arrived at the same answer. That is
what confirms the WKT contract and `geom_hash` identity on real data, and it is
the strongest evidence C1–C3 are correct.

⭐ Coverage is now 89,527 OSM trails against UGRC's 49,125. Fred, on seeing the
map: *"trail content is impressive."* Every added line is snappable, so route
drawing reaches ground that previously fell through to `freeVertex`.

---

## COMMITS TODAY (three, in order)

| Hash | What |
|---|---|
| `3a9d0fbf5` | C3a+C3c: OSM import worker (trails + points), `pending_import` ledger API. 2 files, 688 insertions. |
| `44c7781e7` | C3b: row 3 scope selection + area-draw round trip, gated. 4 files, 405 insertions. |
| `f724d78dd` | C3c+C4: import wired to the spatial DB, cleanup archives and sweeps. 2 files, 357 insertions. |

Baseline entering the day: `3fd526517` (C1+C2, shipped 07-28).

⭐ **Split deliberately three ways** so the worker, the panel, and the wiring stay
separately revertable — the wiring commit is the first change that can write rows,
so there is a clean marker immediately before it.

Working tree clean. Not pushed (local only).

---

## ⚠ PROCESS FAILURE ON THE FIRST EOD ATTEMPT — corrected

My first pass **stripped 16 KB from the Living Master and then wrote the
completed-task doc from my own account of the session.** The dropped text was
deleted, not moved: the full C3b specification, the area-cycle steps, the tally
assertion, the per-layer counters and the row-4 summary would have been lost. That
violates the superset rule.

Worse, I dropped **OPEN QUESTIONS — C3b** wholesale, and **item 3 (delete-by-source
for TRAILS) is still open** — and now matters more, not less, because 89,527 OSM
rows are in the production DB and the only reversal is a laptop DELETE.

**Corrected approach, and the one to use every time:**
- Only genuinely-completed sections graduate out, and they move **verbatim** into
  the completed-task doc's appendix — no paraphrase, no summary.
- A line-accounting check runs before writing: every moved line must be present in
  the appendix. (This pass: **390 moved lines, 0 missing.**)
- Sections with any still-open item **stay** in the Living Master, annotated per
  item rather than removed.
- Superseded text is **struck through with an amendment beneath it**, never
  deleted — see the V2.7 reprocess-driver bullet.

## WHERE THE DETAIL LIVES

⛔ **Full design + fix detail for C3/C4 is NOT in the Living Master.** Per the
one-doc-per-completed-task rule it graduated into:

**`COMPLETED_TASK_osm_import_c3_c4_2026-07-29.html`**

Read that before touching the OSM panel. It holds the as-built mechanism, the
five coordinate orderings, every fix and its root cause, the reversals, and the
rejected alternatives.

---

## SEVEN BUILDS, AND WHAT EACH COST

| # | Result | Note |
|---|---|---|
| 1 | FAILED | `ImportScope` name collision — 17 min wasted |
| 2 | green | C3b gated, verified: pending record, extent, counts |
| 3 | green | area round trip; map position did not persist |
| 4 | green | fit + viewport report + panel-open ordering — position held |
| 5 | green | C3c wiring + C4 gated |
| 6 | green | ungate both — first real import ran here |
| 7 | green | confirm-loop guard + completion latch + progress parse |
| 8 | green | cleanup OK closes the panel |

⚠ Build times 12–26 minutes. **Two builds were spent on my errors** (the name
collision, and the gate blocking the area path).

---

## THINGS THAT WENT WRONG, AND THE PATTERN

⭐⭐ **"The file already knows" — twice in one day.**
1. A `sealed interface ImportScope { WholeState; Area(...) }` already existed at
   `OsmImportStage.kt:28`. My enum redeclared it → build 1 failed. Renamed to
   `Row3Choice`.
2. Hours later, `TrailImporter` turned out to already have
   `writePendingArea` / `readPendingArea` / `clearPendingArea`. The OSM handoff is
   the **second instance of a shipped mechanism**, not a new invention. Found only
   because Fred told me to use the navigation xref.

⚠ **Half an idiom is a bug.** `MVS:1436` pairs `fitBounds` with an explicit
viewport report at `:1440`. I copied the fit and not the report, so the map moved
and the position never persisted. **They are one idiom, not two options.**

⚠ **An intention in a comment is not an implementation.** `OsmImportLedger:222`
claimed whole state writes its bbox from `subset_meta`. `subset_meta` has no
extent — seven keys, none a bbox. The extent now comes from the trails themselves
via `trailExtent()`, which is the better source anyway: a stored value can drift
from the rows, this cannot.

⚠ **I guessed a payload shape instead of reading it.** The progress overlay showed
nothing for the whole run because I collected every `Int` in
`progress.keyValueMap`. The worker publishes **one JSON string** under
`osm_extract_progress`, in the shape C2 already established — and it was in the
log the entire time.

⚠ **I argued a settled point too long.** Fred stated the ledger's purpose plainly
("recovery during the process, no purpose once complete"); I kept re-deriving the
rename design he had already specified. The code was right; the discussion was
noise.

---

## REVERSALS AND DECISIONS TAKEN TODAY

⛔ **NO multi-area from one download.** Fred: *"no multi areas from one download.
areas are downloaded to support a ride and we are killing downloads after import
process."* Rationale: *"3 minute download vs cascading complexity for imports."*

This reverses the 07-28 decision that kept the extract alive for a second area.
The trade was weighed: carrying extract lifetime across imports means a driver
that reasons about which areas share a download, and cleanup that has to know
whether anyone still needs the file. That complexity compounds; the download does
not. The state machine collapses to acquire → extract → import → cleanup, once
through, nothing surviving between runs.

⚠ **Consequence — and this is a V2.7 planning change, amended in the Living Master
today:** the reprocess-driver cost model moves from *per state* to *per import*.
The "group by state, replay all area records from one download" bullet is now
struck through in the V2.7 section with the amendment beneath it.
⭐ It also **simplifies** the driver — per-import acquire is uniform, no grouping
logic, no shared-resource question. And a whole-state replay may make area replay
moot entirely, since dedup handles overlap and the worklist could come from *"which
states have OSM trails in the spatial DB"* rather than from history files.
⚠ **Re-derive the 50-state feasibility number against per-import cost** before
committing to unattended refresh.

💡 **Optional-retain seam is marked in the code** (Fred: *"if we have any issue we
can make removing the downloaded file optional"*). Keeping the ZIP alone
(~330 MB) makes a re-import a 40-second re-extract instead of a 3-minute
download — the middle option if the trade ever looks wrong in the field.

⛔ **Dry-run mode: proposed and REJECTED. Do not re-propose.** Fred's objection is
decisive — a dry run cannot see the case that matters: *"an issue in a trail in
this run that was simulated as added but is duped within db."* `resolveByGeom` and
`sourceUidSeen` read **in-memory** HashMaps from `beginDedupSession()`, so a dry
run reproduces a stale session faithfully. **It simulates the decision, not the
database's response to it.** The real run catches it via `UNIQUE(geom_hash)` and
the balance assertion, and recovery is a laptop
`DELETE ... WHERE source_id='osm'`.

⭐ **Cleanup archives by RENAME, before the sweep.** `discardState` deletes
`ledger.json` along with the data files, so a rename afterwards would have nothing
to rename. `history/` sits outside `osm/<slug>/`. **If the rename fails the sweep
does not run** — losing the run record to keep a cleanup on schedule is the wrong
trade.

---

## DOCUMENTATION STATE

✅ **Manual** — *Import OSM Data* under Planning Map → G — Work with Artifacts:
**5 screens → 8**, **18 captures embedded** at 720px WebP q60 (all under the
115 KB ceiling; 3.62 → 4.53 MB, tag balance verified against the pre-edit file).

✅ **Work with Artifacts recapture done** — three buttons now visible, closing the
caption/image mismatch that was flagged do-not-ship.

✅ **Two false statements corrected** in the manual: "steps 3 and 4 are not active
in this build", and "you can come back and draw another area from the same
download".

✅ **Fred's framing paragraph** opens the section, before the step list — the
user-facing statement of R1, and what stops people restarting from step 1 and
re-downloading 330 MB.

⚠ Section title stays **Import OSM Data** — Fred: *"it is a button name just like
import trails data."* I renamed it to prose and had to revert.

✅ **Release notes** — 2.6e section dated 07-29; "Still to come / steps 3 and 4 are
not active" replaced with the shipped Import and Cleanup work; the two multi-area
claims removed.

🔴 **Still missing from the manual:** the adoption moment (panel right after
"CLICK HERE WHEN DOWNLOAD HAS COMPLETED" succeeds — slide 7 was absent from the
set) and the CANCEL confirm (deliberately not captured).

⚠ **Verify the before/after Kanab captions** — those two images rendered blank to
Claude, so their captions follow Fred's filenames rather than observed content.

---

## TOMORROW (Fred's plan)

**1. Import OSM for an AREA of Arizona, and capture that process.**
New screens the area path produces, none of which exist yet: row 3 with SELECTED
AREA ticked · the panel closing and the map jumping to the state extent with the
draw panel open and Import OSM ticked · drawing the box · the panel reopening with
the confirm showing the **area** count · area completion recap.

⭐⭐ **THE PREDICTION TO WATCH.** Geofabrik ships border ways whole, so Arizona's
extract contains the same **313 UT/AZ border trails** already in the spatial DB
from Utah. Arizona should report a **non-trivial `dropped` count** where Utah
reported 9. **If it does, that is `geom_hash` deduplicating across two independent
state downloads — the property the 50-state plan depends on, and never yet
tested.**

⚠ Arizona is a fresh ~610 MB download; Utah's working files are gone.

**2. The QUICKSTART.** Insert the OSM capture alongside the state-trail (UGRC)
capture in the before-you-use-the-app setup instructions.

- Baseline is **`GroupTrack_QuickStart_Installation_Troubleshooting_2026-07-08.md`**
  — the July 8 (V2.6) one, NOT `..._v1.md` (May 9).
- Insertion point: **`First-Time Setup` → step 2**.
- ⚠ **It has no images at all** — plain markdown. Adding captures changes its
  nature: HTML like the manual, or a pointer to the manual's section. ⛔ Pointing
  conflicts with Fred's doc mandate. **Decide before editing.**
- ⚠ Stale: `Package: com.geeksville.mesh` (wrong since 07-26 — the *older* v1 has
  it right), and *"Planning Map → Trail Sources"* in Troubleshooting.
- ⚠ 07-08 dropped v1's Developer Notes, Emergency Recovery, the local-APK `-r -d`
  install rule and the signing-key warning. Mostly intentional (audience changed)
  — but **the `-r -d` rule and signing warning now exist nowhere**, and a tester
  installing a local APK needs both.

**3. Fresh xrefs** after today's commits — line numbers in this session's notes
have drifted.

---

## PARKED — three planner issues, all surfaced by the new coverage

⭐ **All three appeared the moment coverage nearly doubled.** Nothing in today's
patches touched snap, route drawing or waypoint placement — these are pre-existing
fragilities that only became visible at real data density. Fred: *"tracks and
trails are virtually on top of each other."*

**(1) Snap should NOT resolve against trails when drawing on a track.** Fred:
*"snap2 should not plot against trails."* `RouteManager.snap(lat, lon, trails,
tracks, 30.0)` ranks both; at OSM density several lines sit inside 30 ft and
nearest-wins flips tap to tap. ⚠ This is the hysteresis/continuity gap already in
the snap-2 spec, arriving early. ⚠ There is also a correctness argument: a snapped
vertex stores `lineId`, and the lead-cart design has followers sharing that ID — a
route snapped to a **track** carries a per-rider ID, not shared geometry.
⛔ Read `RouteManager.snap`'s ranking before proposing anything.

**(2) Dropping a point selects the WHOLE TRACK** (tracks, not trails). Candidate:
`onProximityTap` uses `val radius = 0.002` (~200 m) and queries all artifact
types. ⚠ Candidate, not a diagnosis.

**(3) Waypoints cannot be dropped outside route mode** — likely the same tap
consumed by proximity selection. The most likely genuine bug of the three.

💡 **The diagnostic not yet run:** try all three where OSM added little. Normal
there + chaotic in dense areas = tolerance/radius. Chaotic everywhere = something
else changed. Five minutes, and it decides whether the fix is a number or a
redesign.

---

## FIX 6 — what today established (still not fixed)

⭐⭐ **The rule, from Fred:** *"the 1475 line version should only be used to the fit
function from tracks. it should never be used on load based on json."* →
**content-fit pads; restore does not.** The defect is not that MVS:1475 pads — it
is that padding is baked into the fit instead of being the caller's decision,
which is exactly the 07-13 explicit-argument fix.

⭐ **The loop is visible in one line.** `MVS:1475` — *"bbox+10% pad → lastViewport
→ fitBounds"*. It writes the **padded** box into `lastViewport*`;
`savePlanningState()` persists it; the next restore pads that. ⚠ **The drift comes
from padded values being SAVED, not from the fit function.**

⛔ **CORRECTION — "convoy has cumulative zoom-out" is misleading as written.** Fred:
convoy always re-focuses to current GPS, so the saved frame is overwritten before
drift accumulates. **The mechanism exists in convoy; the symptom does not.**
⭐ Consequence: **FIX 6 only has to be correct on the planner**, which is what
makes the explicit-argument approach a genuine no-convoy-edit fix.

⚠ **The planner has three fit idioms and they disagree:** `MVS:665` fitBounds
alone relying on `moveend` · `MVS:1475` sets `lastViewport*` manually then fits
then reports · `MVS:687` `setView` with no visible report. If some report and some
do not, the drift is not uniform. ⛔ Establish which is authoritative before
writing FIX 6.

⭐ **The persist chain (three hops):** `onViewportChanged` (MVS:559) sets
`lastViewport*` → `postDelayed(viewportSaveRunnable, 400)` (MVS:284-285) →
`savePlanningState()` (MVS:259) writes `planning_panel.json`.
⚠⚠ **`savePlanningState()` reads `lastViewport*`, and only `onViewportChanged`
sets them** — so calling it directly after a programmatic move persists the
**stale** viewport. The report is the correct hook, never a shortcut around it.

---

## ALSO UNEXPLAINED

⚠ `bad_geometry: 0` on the 07-29 extract against **89,394** on 07-28, with
identical `kept: 89536` and `candidate_rows: 186652`. Either the counting changed
between runs, or the earlier figure was the `droppedNoName` collision flagged that
morning.

---

## DOC SET PRODUCED TONIGHT

1. `COMPLETED_TASK_osm_import_c3_c4_2026-07-29.html` — the graduated task record
2. `GroupTrack_Living_Master_2026-07-29.md` — In-Progress + Open only, C3/C4 stripped
3. `GroupTrack_Handoff_2026-07-29.md` — this file
4. `grouptrack_manual_2_6e.html` — 8-screen OSM section, 18 captures
5. `grouptrack_release_notes_2_6e.html` — Import + Cleanup announced

⚠ Manual and release notes are **app assets** — copy into
`app/src/main/assets/` under the canonical names:
```
cp ~/Downloads/grouptrack_manual_2_6e.html        app/src/main/assets/grouptrack_manual.html
cp ~/Downloads/grouptrack_release_notes_2_6e.html app/src/main/assets/grouptrack_release_notes.html
```
