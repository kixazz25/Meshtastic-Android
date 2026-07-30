# GroupTrack — Living Master Checklist
**2026-07-28 EOD** · branch `feature/convoy-event-ride` · baseline commit `3fd526517`

> Living Master carries **In-Progress + Open only**. Completed work graduates out
> into its own titled doc in AllDocs (see `COMPLETED_TASK_osm_import_c1_c2_2026-07-28.html`).

---

# ⭐ IN-PROGRESS — C3 IMPORT (release 2.6e)

## Where the OSM pipeline stands tonight

| Component | State |
|---|---|
| **C1 ACQUIRE** | ✅ SHIPPED + verified on device. Committed `3fd526517`. |
| **C2 REDUCE** | ✅ SHIPPED + verified against an independent reference. Committed `3fd526517`. |
| **C3a** trails import worker | ✅ Built green. **Not wired — nothing calls it.** |
| **C3c** points import (same worker) | ✅ Built green, confirmed 14m20s. **Not wired, not committed.** |
| **C3b** panel wiring | ⛔ **NOT WRITTEN — this is the next build.** |
| **C4 CLEANUP** | ⛔ Stub. Design settled (below). |

**Nothing has yet written a single row into the production spatial DB.**
Everything to date is read-only or writes only to the skinny.

---

# 🔴 C3b — THE NEXT BUILD (full specification)

Everything below was settled in conversation 07-28 and is not re-litigated.
This section exists so C3b can be written without re-deriving any of it.

## The handoff mechanism — why JSON and not Compose state

Row 3 offers **WHOLE STATE** or **SELECT AREA**. Area requires the planning map,
which means the OSM panel must go away and come back.

**Fred's design (07-28):** row 3 simply *closes the panel*. The map's bbox
submission *relaunches* it. There is **no flag in MVS**, no `AreaDrawPurpose`
enum, and no navigation change.

> ⭐ **Why this works:** R1 — stage is derived from disk, never stored. The panel
> can be destroyed and recreated and lands in exactly the same place. That rule
> was written for crash recovery; this is an unplanned payoff.

> ⚠ **Why a flag was rejected:** the `routeMode` leak. There were two back paths
> and only one was gated — the on-screen BACK bypassed the `BackHandler`
> entirely. A `osmAwaitingBbox` flag in MVS has the identical shape, and every
> exit path would have to clear it. Closing the panel means there is nothing to
> leak.

## The ledger record — uniform, both scopes

```json
"pending_import": {
  "scope": "state",
  "chosen_at": "2026-07-28T22:41:09",
  "map_area":      { "s": 36.99, "w": -114.05, "n": 42.00, "e": -109.04 },
  "selected_area": { "s": 36.99, "w": -114.05, "n": 42.00, "e": -109.04 }
}
```

- **`map_area`** — ALWAYS the state extent. Positions the planning map. 5% margin
  so the box occupies ~95% of the view.
- **`selected_area`** — what actually gets imported.

| scope | map_area | selected_area |
|---|---|---|
| state | state extent | state extent (identical, written immediately) |
| area | state extent | `null` until the draw fills it |

**THE PANEL'S RULE IS ONE LINE: on open, if `selected_area` is present, launch.**

Both paths converge there. The map never has to ask where to open — the answer
is in the record regardless of scope. The importer never has to know which
scope it is.

> ⭐ **Whole state is not a separate code path.** The extent is derived from the
> trails themselves, so every trail overlaps it *by definition*. A full-extent
> area import returns exactly the rows a `SELECT *` would — identically, not
> approximately. One function, exercised two ways. **Testing whole state IS
> testing area.**

> ⚠ **This bends R1 knowingly.** R1 said the ledger never decides what runs next.
> "User chose area and hasn't drawn yet" is not derivable from any disk artifact,
> so it genuinely must be stored. The STAGE stays IMPORT throughout — this is a
> sub-state inside it, not a competing stage.

## The area cycle, step by step

```
1. Row 3 tapped
   -> dialog: WHOLE STATE / SELECT AREA / cancel

2a. WHOLE STATE
   -> extent = OsmImportStage.skinnyExtent(ctx, slug)
        SELECT MIN(min_lat), MIN(min_lon), MAX(max_lat), MAX(max_lon)
        FROM osm_trails            (sub-second over 89,536 rows)
   -> setPendingImport(scope="state", map_area=extent, selected_area=extent)
   -> OsmImportWorker.enqueue()
   -> panel STAYS OPEN, progress rows appear under row 3

2b. SELECT AREA
   -> setPendingImport(scope="area", map_area=extent, selected_area=null)
   -> showOsmPanel = false                 (panel closes; MVS is underneath)
   -> open the download panel with "Import OSM" CHECKED
   -> map framed to map_area with 5% margin

3. User draws + submits
   -> ConvoyMapViewerScreen bbox handler sees osmChecked
   -> slug from OsmImportStage.statesInFlight().firstOrNull()
   -> OsmImportLedger.setPendingBbox(ctx, slug, s, w, n, e)
   -> showOsmPanel = true                  (panel relaunches)

4. Panel opens, sees selected_area present
   -> OsmImportWorker.enqueue()            (KEEP makes a double-enqueue a no-op)
   -> progress rows under row 3

5. Import completes
   -> appendImport() writes the record AND removes pending_import
   -> row 3 ticks, row 4 arms
```

> ⚠ **Clearing must be airtight.** The trigger is "selected_area present" and the
> panel re-derives on every refresh. A pending record that outlives its import
> re-enqueues on every panel open. `appendImport` removes it; `enqueueUniqueWork`
> with `KEEP` makes a duplicate enqueue harmless in the meantime — but the
> removal is the actual fix, not the KEEP.

> ⚠ **Row 3 must SHOW the pending state.** `WAITING FOR AREA — draw one, or
> cancel`, with a visible cancel. An invisible mode is precisely the `routeMode`
> failure. If the user picks area, gets distracted, and returns tomorrow, the
> panel must explain itself.

## The panel's own checkbox row (ConvoyDownloadPanel.kt)

The panel already carries intent in checkbox state — **this is why
`AreaDrawPurpose` is unnecessary**:

```
Download Tiles    blue    tilesChecked
Import Trails     green   trailsChecked
Remove Tiles      red     removeTilesChecked
Import OSM        orange  osmChecked          <- NEW ROW
```

`Draw Area` is enabled when any is checked (`ConvoyDownloadPanel.kt` ~:243).
The caller dispatches on whichever ones are. **The checkboxes are the routing.**

New row needs:
- `osmChecked: Boolean` + `onOsmCheckedChange` — same shape as the other three
- added to the `enabled =` condition on Draw Area
- ⚠ **`osmAvailable: Boolean`** — disabled when no skinny exists. Same mechanism
  the V2.6 stub rows already use.
- bbox display line mirroring `"Trails: $trailSourceCount sources found"`:
  **`"OSM: 8,412 trails in area"`** — indexed count off `ix_osm_trails_bbox`,
  instant, appears the moment the box is drawn.

> ⭐ **The count IS the confirmation.** The user sees exactly what they are
> committing to before submitting, so a confirm dialog adds nothing. (Whole-state
> is the exception — see Open Questions.)

> ⚠ **Checkboxes are not mutually exclusive.** Tiles + OSM both checked = one
> draw does both. Arguably a feature. But dispatch must handle combinations, and
> **OSM must never become a `QueueEntry`** — see the yardstick warning below.

## ⛔ OSM MUST NOT ENTER THE DOWNLOAD QUEUE

Share the **drawer**. Never the queue.

`DownloadQueueManager.submitDownload` is not involved. The yardstick averages
tiles-per-minute; deletes were excluded from it precisely because a different
unit poisons `avgMinutesPer100` and every later download estimate collapses
toward zero. **An OSM import measured in ROWS does exactly the same thing.**

Same UI, separate pipeline.

## Tally assertion (add in C3b — 3 lines)

Fred: *"processed should equal added, rejected, and dupes identified."*

```kotlin
val tallied = inserted + aliased + dropped + geomChanged + errors
if (tallied != found) Log.e(TAG, "TALLY MISMATCH: found=$found accounted=$tallied")
```

Same for points: `pointsFound == pointsInserted + pointsMoved + pointsDropped`.

> ⚠ Reading the C3a loop back, every path *appears* to increment exactly one
> counter — but "appears complete" is how the `deleteOriginal` assumption went
> wrong this morning. The assertion turns a silent accounting hole into a log
> line on the first run.

Record the result: **`"balanced": true/false` per type in the import record**, so
the audit is in the file rather than a logcat buffer that is gone by morning.

## Per-layer point counters (amend in C3b)

Points are currently one bucket. If `natural` silently stops importing, the
total still looks plausible. Split by layer — `places_found`, `natural_found` —
generated from the catalog, so enabling `pois` adds its keys automatically.

## Row 4 summary

Row 4 detail reads the state's own `imports[]`:

```
CLEANUP    3 imports · 12,847 added · 402 aliased · 1,633 dupes · 58 changed
```

> ⚠ **Sum WITHIN one state's ledger only.** Do NOT sum across history files —
> see the History section.

---

# 🔴 C4 CLEANUP — settled, not written

## The ledger is RENAMED, not copied

```
osm/utah/ledger.json  ->  osm/history/utah_2026-07-28_224109.json
```

- **Rename replaces the delete.** `finalizeAndDelete` currently removes the
  ledger; now the rename IS the removal. If the rename fails, the ledger stays
  put — cleanup cannot destroy the record even by accident, because deletion is
  not a separate step that could succeed on its own.
- **Nothing is serialized twice**, so the archived record is byte-identical to
  what the import wrote.
- ⚠ **`history/` sits OUTSIDE `osm/<slug>/`** — CANCEL and cleanup both remove
  `osm/<slug>/` wholesale and would take the history with it. (This is also why
  `discardState` is deliberately non-recursive.)
- ⚠ **Never pruned.** A few KB each. Chosen, not overlooked.

## ⭐ C4 NO LONGER AUTO-ROLLS AFTER IMPORT

**This reverses an earlier decision and the reason matters.** C3→C4 auto-roll was
settled when whole-state was the only scope. With area scope it is wrong:
import Moab, cleanup fires, skinny deleted — a second area costs a 630 MB
re-download, defeating the entire point of area scope.

**Cleanup is explicit.** Row 4 arms after import; the user decides when they are
done with the state. Whole-state import may *suggest* it.

## History is per-run, never aggregated

Fred 07-28: *"the history is just that, results of one run at one moment in
time."*

- **History = what a run did.** One file, one run, read on its own.
  **No cross-run arithmetic anywhere.**
- **The database = what exists now.** "How many OSM trails do I have" is
  `COUNT(*) FROM trail_properties WHERE source_id='osm'` — asked of the DB,
  never derived from history.

> ⚠ Summing runs produces a number that *looks* like inventory and is not. A
> re-imported area contributes inserts and dupes that add up to nothing
> meaningful. Better no total than a misleading one.

A history browser (list by state + timestamp, open one run, show its counters
and whether they balanced) is a later, cheap feature — the filenames already
carry both filter keys.

---

# 🔴 OPEN QUESTIONS — C3b

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
- **V2.7 vector rendering / MapLibre** — scoped only, nothing decided.
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

# 🔴 OPEN — MANUAL: full OSM walkthrough capture sweep

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
image count · file still ends `</html>`. Current baseline: **115/115, 72 images**.

⚠ This sweep folds into the **end-of-V2.6 manual pass**, alongside the install/quick-start
section and the existing 18-screen sweep — one pass, not two.

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
