# GroupTrack — OSM Trail Import: Component Contracts

**2026-07-28 · INTERNAL · design spec, nothing built · Release 2.6e**

Four independent components dropped into one gate container. Each is specified by
**the state it enters at, the work it performs, and the state it leaves** — so each
can be written and tested alone, then wired in without renegotiating anything.

Prerequisite reading: `GroupTrack_handoff_2026-07-27.html` (the Geofabrik pivot,
the two-tier staging decision, the WKT/hash byte-exactness requirement).

---

## 0 · THE CONTAINER

### Working directory — one per state, app-private

```
getExternalFilesDir(null)/osm/<slug>/
    ledger.json                    created by C1, deleted by C4
    <slug>-latest-free.gpkg.zip    RECOVERY POINT 1
    <slug>.gpkg                    transient — exists ONLY inside C2
    osm_trails_<slug>.db           RECOVERY POINT 2
```

App-private on purpose. Not `Downloads`: a public folder is mutable by other apps
and by the user, and a browser-delivered file arrives as `content://`, which
`SQLiteDatabase.openDatabase()` cannot open.

### The rules

| | Rule |
|---|---|
| **R1** | Stage is **derived from disk**, never stored. No stage counter, no status field. |
| **R2** | Only **two paths** are ever consulted — the zip and the skinny DB. The `.gpkg` is never a recovery point. |
| **R3** | **Existence must mean COMPLETE.** In-flight work uses `.part` / `.tmp` and is renamed atomically on success. Derivation only looks for final names. |
| **R4** | The ledger **never gates**. It records outcomes. When ledger and disk disagree, **disk wins**. |
| **R5** | Derivation runs at **moments** — panel open, job completion, return from background. No file watcher. |
| **R6** | Derivation is **per-slug**. Utah and Arizona can sit at different stages simultaneously. |
| **R7** | On ACQUIRE entry, sweep debris only: `*.part`, `*.tmp`, `*.gpkg`. Never blind-delete the directory. |

### Derivation

```kotlin
fun stageOf(slug: String): Stage {
    val dir    = File(getExternalFilesDir(null), "osm/$slug")
    val zip    = File(dir, "$slug-latest-free.gpkg.zip")
    val skinny = File(dir, "osm_trails_$slug.db")
    return when {
        skinny.exists() && verifySkinny(skinny) -> Stage.IMPORT
        zip.exists()    && verifyZip(zip)       -> Stage.REDUCE
        else                                    -> Stage.ACQUIRE
    }
}
```

### Verification — existence proves a rename, not good bytes

| Artifact | Check |
|---|---|
| **zip** | size == `ledger.download.expected_bytes` **AND** `ZipFile` opens and lists an entry (central directory only — fast; catches truncation) |
| **skinny** | `SELECT COUNT(*) FROM osm_trails` succeeds **AND** `subset_meta.feature_count` exists **AND** the two match **AND** `subset_meta.source_download` == `ledger.download.completed` |

⚠ The last clause closes the mismatch case: a fresh zip beside a stale skinny DB
both verify individually and are silently inconsistent.

**Failed verification deletes that artifact and drops the stage by exactly one.**
Automatic, no dialog. Bad state clears; the run does not restart.

### Button state

| On disk | Green | Also enabled |
|---|---|---|
| nothing | **1 ACQUIRE** | — |
| zip only | **2 REDUCE** | 1 (re-download — gated, destructive) |
| skinny DB | **3 IMPORT** | 4 (cleanup — destructive) |

⚠ Step 3 stays green after imports. "Finished" is the user's judgement, not the
app's. Step 4 sits enabled but not green beside it.

**Start over** — one manual button, gated, names what is lost. This is the "clear
and start again" escape; it is deliberately *not* the automatic failure behaviour.

### Gate popups

Every stage runs behind a gated confirm that names the action **and its cost**,
reusing the pattern already built for the queue panel's scoped CANCEL/CLEAR.

---

## 1 · C1 — ACQUIRE

**Fetch one state's Geofabrik extract into app storage.**

### Enters at
Neither recovery point present (or a re-download explicitly confirmed).
Directory may hold debris.

### Work performed
- Sweep debris per R7; write a fresh `ledger.json` (carrying forward any prior `imports[]`).
- Resolve slug → `https://download.geofabrik.de/north-america/us/<slug>-latest-free.gpkg.zip`
  from the shipped asset `geofabrik_states.json`.
- Free-space check **before** starting: ~1.05 GB peak needed downstream.
- Download to `<slug>-latest-free.gpkg.zip.part`, **resumable via HTTP Range**.
- On completion, verify length against `Content-Length`, then **atomic rename** to final.

### Leaves at
`<slug>-latest-free.gpkg.zip` present and verifiable → **stage REDUCE**.

**Failure exit:** `.part` remains, no final name → stage unchanged (ACQUIRE). Re-run resumes.

### Mechanism
WorkManager **foreground** job. Determinate progress: **MB, rate, and ETA** — not a bare percentage.

⚠ On resume the bar starts at the **resumed offset**, not zero. HTTP Range means
the first byte of the session is not the first byte of the file. A tester who
loses signal at 80% and sees 0% will kill it.

### Ledger writes
`download` — `expected_bytes`, `actual_bytes`, `resumed_from`, `started`, `completed`.

### Notes
- ⚠ California has no flat state file — only `california/norcal` and `california/socal`.
- ⚠ Geofabrik is a free service. Fifty testers pulling 314 MB–1.6 GB files is real
  bandwidth; read their usage terms before wide release. Same class of dependency
  as Overpass, which failed on load 07-27.

---

## 2 · C2 — REDUCE

**Unzip and skinny-select, as one atomic operation.**

### Enters at
Zip present and verified. No skinny DB.

### Work performed
- Unzip to `<slug>.gpkg` (`java.util.zip.ZipInputStream` — built into Android, no dependency).
- `SELECT COUNT(*)` on `gis_osm_roads_free` with the fclass filter → exact bar total.
- Filter to trail classes: `track`, `track_grade1..5`, `path`, `bridleway`.
- **Decode GPKG-binary → WKT** in the app's exact format.
- Compute `geom_hash`; compute per-row `min/max lat/lon`.
- Write `osm_trails_<slug>.db.tmp` with `ix_hash`, `ix_name`, `ix_bbox`, and `subset_meta`
  (including `source_download` stamped from `ledger.download.completed`).
- **Atomic rename** to final, **then** delete the zip and the `.gpkg`.

### Leaves at
`osm_trails_<slug>.db` present and verifiable, zip and `.gpkg` gone → **stage IMPORT**.

**Failure exit:** `.tmp` and possibly `.gpkg` remain; zip still present → stage
unchanged (REDUCE). Re-run sweeps and repeats. **No re-download.**

### Mechanism
WorkManager **foreground** job. Determinate — the count is known before the pass
starts. Update per batch (5,000 rows), not per row.

### Ledger writes
`extract` — `gpkg_bytes`, `classes`, `candidate_rows`, `kept`, `bad_geometry`,
`skinny_bytes`, `zip_deleted`, `gpkg_deleted`, `completed`.

### ⚠ This is the risk component — the only genuinely new Kotlin

**GPKG-binary → WKT**, ~60 lines: a short header (magic `GP`, version, flags,
srs_id, optional envelope) wrapping standard WKB.

- ⛔ **The envelope size is encoded in flags bits 1-3** (0/32/48/48/64 bytes). Get
  it wrong and the WKB starts at the wrong offset, producing **plausible-looking
  garbage coordinates rather than an error**.
- ⛔ **The WKT must be byte-exact** — `geom_hash` is a hash of the string:
  ```
  TrailImporter.coordRingToWkt (:545)
      comma with NO space, single space between lon and lat,
      Kotlin Double.toString() — shortest round-trip, NOT fixed precision
  SpatialDbManager.computeGeomHash (:1288)
      SHA-256(wkt utf-8), hex lowercase

  LINESTRING(-112.771625 37.069884,-112.770892 37.070113)
  ```
  In Kotlin this is native, so it is **easier on-device than it was in Python**,
  where matching it took a second attempt.
- `osm_gpkg_subset_v2_2026-07-27.py` is a **working reference to port from**.
  ⚠ It is currently missing from AllDocs — locate it before this component starts.

### ⚠ Storage
```
314 MB zip + 631 MB gpkg + ~108 MB skinny  = ~1.05 GB PEAK
after rename + deletes                     =  ~108 MB
```
The peak is unavoidable — SQLite needs random access, so the `.gpkg` must be fully
written before it can be read. Check free space at the gate, not during.

⚠ **The zip survives until the skinny rename succeeds.** That costs ~100 MB and
buys the recovery point: a skinny pass dying at 80% re-runs instead of forcing a
314 MB re-download over mobile data.

### Why a skinny pass at all
The `.gpkg` is 631 MB across ~20 layers; `gis_osm_roads_free` alone is ~700,000
rows once footways (241,360 in Utah) and residential streets are counted. Querying
it per area draw would scan that every time. One pass produces ~108 MB of
trails-only rows **with a bbox index**, after which every area query is instant —
and lets the 631 MB source be deleted immediately.

---

## 3 · C3 — IMPORT

**Move trails from the skinny DB into the live spatial DB, by scope.**

### Enters at
Skinny DB present and verified. Repeatable — runs as many times as the user chooses.

### Work performed

**C3a — SELECT.** Panel offers whole state or a drawn area. Produces one value:

```kotlin
sealed interface ImportScope {
    object WholeState : ImportScope
    data class Area(val bbox: Bbox) : ImportScope
}
```

A sealed type rather than `Bbox?` — clears CODE RULE 1 outright (no null to
justify), and makes "whole state" impossible to confuse with "caller forgot to
set it". Exhaustive `when`; the compiler catches a missed branch if a third scope
ever appears.

**C3b — QUERY.** Scope decides only the WHERE clause.

```sql
-- Area: OVERLAP, not containment — a trail crossing the drawn edge is kept whole,
-- matching queryTrailsByViewport. Covered by ix_bbox.
WHERE max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?
```

**Count first, then confirm.** `SELECT COUNT(*)` with the same predicate, shown in
the gate popup. Whole state says 89,554; a drawn box says 1,204. That is the
moment the volume decision gets made by a human looking at the real number.

**C3c — INSERT.** Loop through `SpatialDbManager.insertTrail` — *not* raw INSERTs
— so `INSERT` / `DROP` / `ALIAS`, the `notNamed()` sentinel and `trail_properties`
all behave exactly as they do for the eight catalog sources. Staged rows already
carry app-exact WKT and a matching `geom_hash`, so this is close to a table copy.

### Leaves at
Rows in `trails` + `trail_properties`; one `imports[]` entry appended.
**Stage unchanged — still IMPORT.** Step 3 remains green; step 4 remains enabled.

**Failure exit:** partial insert is safe — `source_unique_id` dedup makes a re-run
UPDATE rather than duplicate.

### Mechanism
WorkManager **foreground** job (whole-state is 87,533 inserts). Bar **resets per
import** rather than accumulating.

### Ledger writes
Appends to `imports[]` — `scope`, `bbox` (null for whole state), `found`,
`inserted`, `aliased`, `dropped`, `at`.

### Field mapping

| staged | lands in | note |
|---|---|---|
| `wkt` | `trails.geometry` | already app-exact, no conversion |
| `geom_hash` | `trails.geom_hash` | already computed the same way |
| `name` | `trails.name` | blank → `notNamed()` sentinel, as every other source |
| `min/max lat/lon` | `trails` bbox cols | already computed |
| `osm_id` | `trail_properties.source_unique_id` | stable across extracts → re-import UPDATES |
| *(fixed)* | `trail_properties.source_id` | `osm_trails` |
| `fclass` | **DECISION (a)** | |
| `motorized_hint` | **DECISION (b)** | ⛔ NOT legal access |
| `ref` | — | already folded into `name` at reduce time |
| `maxspeed` | — | no column; not OHV-relevant |

### ⚠ Two decisions — default answers, reversible, both avoid a paired edit

**(a) `fclass` → leave `carto_code` BLANK.** Renders cyan "Unspecified", visually
distinct from every coded government source, honest (OSM has no carto
classification), and touches none of the three hand-synchronised places the
colour mapping lives in — `ArtifactDetailPanel.kt` (~:316, carries an explicit
PAIRED-EDIT WARNING), `convoy_map.html` (**FROZEN**) and `grouptrack_map.html`.
Colour keys off the leading digit.

**(b) `motorized_hint` → NOT mapped to `motorized_allowed`.** It is inferred from
fclass — physical capability, not legal access — and that column's entire purpose
is legal access. A wrong "Yes" there is worse than an empty field.

⚠ Note the irony to keep in view: OSM is the **only** source in the DB carrying
real `motorized_allowed` values (UGRC has 49,098 trails and not one), but that
field **does not survive the Geofabrik path** — the free roads layer has no access
tags at any tier. What is gained instead is `fclass` = `track_grade1..5`, real OHV
difficulty on every track rather than sparse tagging.

### ⛔ No delete-trail in this release
A route's snapped vertices carry `lineId`, so deleting a trail orphans them
silently. Cleanup stays a laptop operation — pull the DBs,
`DELETE ... WHERE source_id='osm_trails'`, push back. Bulk delete-by-source in-app
was considered and rejected on that dependency.

### Verification order (Droid 2 first — Droid 1 holds the real data)
1. Push a staged file, draw ONE area, import a few hundred trails.
2. Trails render; OSM visually distinguishable from UGRC.
3. **Snap-2 picks them up** — `RouteManager.snap(lat, lon, trails, tracks, 30.0)` is
   source- and renderer-independent, so this should be free. Confirm, don't assume.
4. Search behaves with a large unnamed population (`Not Named` is excluded by design).
5. **Viewport paint speed** — the one thing that genuinely changes at volume.
   Measure before and after.
6. **Re-import the same area** → expect already-imported, not new. Proves
   `source_unique_id` dedup and that re-import is safe.

### ⚠ Whole-state import reverses a decision — knowingly
Two-tier staging was chosen specifically so the state never lands wholesale:
```
live spatial DB today:   49,125   (UGRC + NPS)
+ Utah OSM whole state:  87,533
                       = ~137,000 trails
```
That is the ~3× viewport bbox-scan working set the staging design avoided. It may
be fine — step 5 above is how you find out. Suggested hedge: keep whole-state in
the UI, make it the **non-default**, and put the count in the gate popup.

---

## 4 · C4 — CLEANUP

**Reclaim space and close out the run.**

### Enters at
Skinny DB present. User has decided they are finished importing.

### Work performed
- Gated confirm naming exactly what is removed and how much space returns.
- Append one line to a **permanent** `osm_import_history.json`.
- Delete `osm_trails_<slug>.db`, then `ledger.json`, then the directory.

### Leaves at
Directory gone → **stage ACQUIRE**. Imported trails remain in the live spatial DB.

### Mechanism
Plain coroutine — **not** a worker. File deletes finish in seconds and do not
report progress. **Indeterminate spinner; a fake percentage is not honest.**

### Ledger writes
`cleanup` — `deleted[]`, `bytes_reclaimed`, `at` — written immediately before the
ledger itself is removed.

### ⚠ The transient ledger deletes the record of the run
Both can be true: the per-state ledger is transient exactly as specified, and at
cleanup it appends one durable line elsewhere.

```json
{ "state":"utah", "extracted":89554, "imports":2, "trails_added":88731,
  "completed":"2026-07-28T10:02:11" }
```

Kilobytes, and it is what tells you six weeks from now which states a tester
actually loaded. **Open decision — confirm before C4 is written.**

⚠ C4 is also the **re-run escape**: declining it keeps the skinny DB, so more
areas can be imported later without re-downloading anything.

---

## BUILD ORDER

**Container → C3 → C2 → C1 → C4**

**Container first, because it is testable with nothing built.** Derivation reads
files; `adb push` a dummy zip and watch step 2 go green, delete it and push a
dummy skinny DB and watch step 3 go green, push garbage and watch verification
drop it back a stage. The whole state machine gets proven with `touch` and
`adb push` — no download, no decoder, no import.

**C3 next, because it defines the contract C2 must satisfy.** Building the reduce
pass first means guessing at the schema; building the importer first gives the
reduce pass a proven target — exact columns, exact WKT, exact hash. And C3 is the
only component testable **today**, against the three already-staged files on `D:`
(Utah 87,533 / AZ 10,714 / NV 8,632) via `adb push`.

**C2 next** — the only genuinely new Kotlin and the real risk. **C1 after** —
well understood; the tile downloads proved resumable foreground work. **C4 last**
— trivial, and cannot be tested until something exists to delete.

---

## OPEN — carried in the component that owns them

| | Decision | Default | Blocks |
|---|---|---|---|
| C3 | `fclass` → `carto_code` | **blank** | nothing |
| C3 | `motorized_hint` → `motorized_allowed` | **no** | nothing |
| C3 | whole-state default on/off | **off** | nothing |
| C4 | permanent history line | **yes** | C4 only |
| — | do C1/C2/C3 ride the existing download queue, or stand alone? | **stand alone** | C1 |

The queue question is the only real trade: riding the queue gets progress UI,
cancellation and persistence free, but its schema and panel are tile-shaped
(z/x/y, tile counts, corridor derivation) and a 314 MB file download is not that.
Standing alone duplicates worker-dispatch plumbing that already exists.

---

## ⚠ MISSING FROM ALLDOCS — locate before the components that need them

| File | Needed by |
|---|---|
| `osm_gpkg_subset_v2_2026-07-27.py` | **C2** — the GPKG decoder reference to port |
| `process_state_2026-07-27.py` | C2 — test-data generation |
| `COMPLETED_TASK_download_queue_schema_and_panel_2026-07-24.html` | the queue-vs-standalone decision |

All are referenced by docs that *are* in AllDocs but have no section of their own.
⚠ This also means the 07-27 note to drop `DOC_MAX_AGE_DAYS` from 45 to ~14 is not
yet safe — the backlog run demonstrably did not capture everything.
