# GroupTrack — Session Handoff
**2026-07-28** · branch `feature/convoy-event-ride` · **commit `3fd526517`**

Internal doc — resume context, deliberately detailed rather than brief.

---

## What shipped today

**OSM trail import: C1 ACQUIRE + C2 REDUCE, complete, verified, committed.**
11 files changed, 2,912 insertions. `3fd526517` is the rollback point.

Plus, built but **not committed and not wired**: C3a (trails import worker) and
C3c (points import, same worker). Both compile green. **Nothing has written a
single row into the production spatial DB yet.**

---

## ⭐ THE VERIFICATION — the thing that makes C2 trustworthy

`geom_hash` is SHA-256 over WKT, and the WKT format is a contract: comma with
NO space, Kotlin `Double.toString()`. A hash over a string differing by one
space is a completely different hash. If the Kotlin formatted WKT even slightly
differently from `TrailImporter.coordRingToWkt`, cross-source dedup would
silently stop recognising its own rows, re-imports would duplicate instead of
update, and **nothing anywhere would report an error.**

That failure is invisible from inside the app. So the device-built skinny was
compared against one built by the validated Python:

```
device rows whose geom_hash exists in the reference: 89,448 of 89,536 (99.902%)
shared osm_id, disagreeing hash: 58
  GEOMETRY CHANGED : 58     <- real OSM edits between the two extracts
  FORMAT DIFFERS   :  0     <- would have been a port bug
```

**Zero format differences.** All 58 disagreements are real geometry changes,
several with identical point counts but different coordinates — exactly what a
mapper nudging a trail looks like. Had the WKT diverged, all 89,536 would have
mismatched, not 58.

This proves the GPKG envelope offset AND the WKT contract byte for byte.

---

## ⭐ THE DEDUP RULE — tested across all three artifact types

Fred: *"dupe hash is dupe trail, no dupe hash is a second trail. period."*
And: *"it is very important across sources that we do not duplicate geo hashes."*

Tested against real Utah + Arizona data before anything reaches production:

**Trails — the rule is exactly right, no configuration needed:**
- Utah: 89,536 rows, 89,536 distinct hashes, 89,536 distinct `osm_id`. Perfect.
- Arizona: 1 hash collision in 134,242.
- **313 trails shared across the Utah/Arizona border — ALL 313 hash-match.**
  Geofabrik ships border ways WHOLE in both extracts. Importing Arizona after
  Utah drops all 313 as duplicates. One trail each, automatically.
- Zero same-geometry-different-id.

> ⭐ That also retires a worry: `geometryChanged` will NOT be polluted by border
> fragments, because there are none. It measures real edits only.

**Points — thinner, still clean:** 3,674 places + 4,048 natural, every one a
distinct `osm_id`, 24 exact-coordinate collisions between them.

**539 same-name pairs within 500 m (6.98%)** — but that number OVERSTATES it:
- `twin peaks — natural/peak × natural/peak, 206 m` — **two summits.** Not a
  duplicate at all.
- `desert mound — places/locality × natural/peak, 485 m` — the peak, and the
  place named after it.
- `westbrook — locality × locality, 185 m` — a genuine duplicate.

`locality` is 2,846 of 3,674 places (77%) — OSM's noisiest class, sorted last by
the `fclass` label priority, so most never render together at normal zoom.

> ⭐ **It is a RENDERING number, not a data one.** OSM says these are separate
> objects and the rule reproduces OSM faithfully. Collapse by
> name-within-distance AT DRAW TIME, where it is reversible — never at ingest.

---

## ⛔ THE DAY'S BIGGEST WASTE — and the lesson

**Hours were lost to `adb` commands pointed at a package that does not exist.**

The Kotlin package is `com.geeksville.mesh` (Meshtastic fork lineage). The
**installed application id is `com.grouptrack.android`** — renamed for Play.
Source package and application id are independent. I conflated them and never
checked.

Every dead end today was that one error:
- `ls /sdcard/Android/data/com.geeksville.mesh/...` → "No such file"
- `rm -rf` on that path → deleted nothing
- "Arizona is gone" this morning → **wrong**; it was still there
- Theories invented to explain it: Android 11 directory restriction, secondary
  volume, MediaStore ghosts, stale FUSE view. **All wrong.**

`pm list packages | grep -i geeksville` returns **nothing**. That one command
would have ended it in the first minute. I kept deferring it in favour of
theories.

> ⭐ **LESSON, durable: verify identity before diagnosing behaviour.** When
> several unrelated commands all return "not found", suspect the path, not the
> platform.

---

## ⭐ THE STORAGE MOVE — a correction that was already documented

Fred: *"we have hit roadblocks all day with data in the app area. there is a
reason we moved data to public area."*

The OSM working directory was in `getExternalFilesDir()`. On a release build
that is unreachable: `run-as` is blocked by signing, `adb` is blocked by Android
11+, and no file manager can browse `Android/data`. **There was no inspection
route at all** — verifying an extract would have required *writing an export
feature to work around a directory choice.*

The planning state file already lives in `Documents/GroupTrack/state` **for
exactly this reason.** The decision was on record; I did not apply it.

Moved to `Documents/GroupTrack/osm/<slug>/` with one-shot migration.

⚠ **Two consequences, both accepted:** files now survive uninstall and are not
cleared by Clear Storage (right for a 630 MB extract — a reinstall should not
cost another download), which makes **CANCEL the only cleanup path**, so CANCEL
has to keep working.

---

## Other findings worth carrying

**⚠ `updateExistingFeature` does NOT update geometry.** Its own comment says
*NEVER geometry/bbox/geom_hash*. I had claimed `osm_id` as `source_unique_id`
means "re-import updates rather than duplicates" — **that is wrong.** A trail
whose geometry changed would have its new shape silently discarded.
**Fred's call: insert as a second trail.** Stronger reason than the low count —
routes snap by `lineId`, so replacing geometry orphans every saved route
touching that trail. Adding a row touches nothing.

**⚠ Two Python-to-Kotlin traps, same class:**
- `PRAGMA journal_mode=OFF` **returns a row**, so Android's `execSQL` rejects it.
  `sqlite3.execute` does not care. Cost one build.
- Kotlin's `Byte` is **signed**; every GPKG byte read must mask `0xFF` first.
  Guarded from the start. ⚠ Note the flags byte never exceeds `0x3F`, so that
  mask is defensive — the mask that genuinely matters is in `geomHash`, where
  digest bytes are half ≥ `0x80`.

**⚠ `spatial_artifacts.js` is an orphan.** File exists, nothing loads it, and
both HTMLs carry inlined divergent copies (convoy 9 refs, planner 15). The V2.7
plan names this file as its rewrite target — so 2.7 would start from stale code.
**Delete it or maintain it; current state is the only unacceptable one.**

**Filename fix:** Geofabrik serves both `-latest-` and dated `-YYMMDD-` variants.
The probe demanded "latest" and reported "extract not found" for a file sitting
in Downloads. Now accepts either; slug derivation strips the trailing
date/`latest` so `north-carolina-260728` does not become `north`.

**⚠ Progress bug root cause was NOT the throttle.** Fred's observation — "unzip
logged progress, only trails did not" — ruled that out. **WorkManager DISCARDS
progress Data on a terminal state.** `getProgress()` is empty once a worker
finishes, so the final forced publish is real but unreadable. Earlier layers
escaped because a *later* pass published while still RUNNING and carried their
completed state along. Nothing follows trails. Fixed in the panel: a SUCCEEDED
state means every layer completed, by definition.

---

## Measured numbers (replace earlier estimates)

| | |
|---|---|
| Utah zip → gpkg | 630 MB uncompressed |
| Arizona zip → gpkg | 610 MB → 1,214 MB (2.09×) |
| **C2 on device, end to end** | **~40 s** (unzip 14 s, trails 23 s, natural 1.2 s) |
| C2 on desktop (Arizona trails) | 60.7 s |
| Utah skinny | 108 MB · 89,536 trails · 3,674 places · 4,048 natural |
| Arizona | 134,242 trails |
| Free-space gate (Utah) | needed 788 MB, had 33 GB |

> ⚠ **My 8–20 minute device estimate was wrong by ~20×** — and that error is what
> sized the 30-second progress tick, which is why nothing rendered.

> ⚠ **Per-state variance is large** (Utah 89,536 vs Arizona 134,242 — 50% apart
> between neighbours). Size gates and ETAs must be derived per state, never
> scaled from one sample.

> ⚠ **~0.08% of Utah's trails changed in two days** (18 removed + 58 edited).
> That is the natural refresh rate of the data.

---

## Next session — start here

1. **Consider committing C3a + C3c** as a checkpoint. They compile against the real
   `SpatialDbManager` API — the risky part proven — and committing keeps tomorrow's panel
   work separable from tonight's worker work if something needs reverting.
2. **Write C3b** — the Living Master has the complete specification; nothing
   should need re-deriving.
3. **Before the first real write:** dry-run mode, and delete-by-source for trails.
4. Then Utah whole-state import, area stubbed.

**Do NOT touch the production spatial DB until C3b + dry run are in place.**

---

## Doc set produced this session

| File | Notes |
|---|---|
| `GroupTrack_Living_Master_2026-07-28.md` | In-Progress + Open. Carries the full C3b specification. |
| `GroupTrack_Handoff_2026-07-28.md` | This file. |
| `COMPLETED_TASK_osm_import_c1_c2_2026-07-28.html` | Per-task permanent record for the committed work. |
| `grouptrack_release_notes_2.6e.html` | 2.6e current, 2.6d demoted to "Previously", **2.6c moved into the collapsed block**. |
| `grouptrack_manual_2.6e.html` | New "Import OSM Data" subsection (5 screens) under Planning Map → G — Work with Artifacts. |

⚠ **C3c build confirmed green (14m20s) after the first doc pass** — the Living Master's
"pending confirmation" note has been corrected.

### Manual — screenshots still needed
The new section carries **7 `PENDING-CAPTURE` comments** (invisible, per house convention —
never visible banners). Captures required, 720px WebP, ~110KB ceiling at q60:

1. WORK WITH ARTIFACTS panel showing all three buttons (IMPORT OSM DATA is new, planner only)
2. OSM panel generic — `IMPORT OSM TRAIL DATA`, gate button under row 1
3. OSM panel adopted — `OSM TRAIL DATA - UTAH`, row 1 ticked, gate gone, row 2 green
4. Extract running — the four per-type rows, one mid-progress
5. Extract complete — row 2 ticked, rows 3–4 armed
6. CANCEL confirm dialog
7. "No Geofabrik extract found" dialog
8. Geofabrik download page as it opens from the button

⚠ The manual work was deliberately held for the **V2.6 close**, bundled with the
install/quick-start section and the 18-screen sweep. These OSM screens will want
re-checking at that pass rather than being done twice by accident.

### Version note


⚠ **2.6d shipped 07-24/25** (commit `703625fb8`, versionCode 29320744). Today's OSM work is
therefore **2.6e**, and the release notes are labelled accordingly — say the word if you want
it folded into 2.6d instead; it is a one-word change.

The manual is versioned in Downloads as `grouptrack_manual_2.6e.html` and copies into assets
under the canonical unchanged name:
`cp ~/Downloads/grouptrack_manual_2.6e.html app/src/main/assets/grouptrack_manual.html`

**The prior 2.6d manual was used as the baseline** and edited in place — 3,611,797 → 3,618,279
bytes, `<details>`/`<summary>` balance verified, image count unchanged at 72, file still
closes with `</html>`.
