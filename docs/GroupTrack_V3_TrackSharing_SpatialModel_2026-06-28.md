# GroupTrack V3.0 — Track Sharing & Spatial Model (Design Target)

_Last updated: 2026-06-28. Status: DESIGN TARGET (pre-execution). Source: design session 2026-06-28 (Fred), building on the 06-25 record-save / sync / import bug diagnosis._

This document defines the target model for how tracks are identified, stored, and shared — locally (device spatial DB + my_tracks files) and globally (server). It is the design that the V2.6 record-save / sync / import fixes must be **compatible with**, so it is written before code.

---

## 0. Governing principles

1. **Hash is the identity.** A track's identity is its `geom_hash` (SHA-256 of the geometry WKT), not its name and not its filename. Names are free-form human labels; the hash is the key.
2. **The DB controls every write, in all cases.** Create, import, and sync all just **identify and present** a record to the DB. The DB enforces all rules — dedup, uniqueness, aliasing, rejection — via its own schema constraints / triggers (`UNIQUE(geom_hash)` etc.). Code never decides "should this go in?"; it presents the record and the DB decides. This makes every path idempotent: re-running presents the same records; the DB keeps state consistent.
3. **Code owns:** identifying entities, computing hash / wkt / bbox / name, presenting records to the DB, and — for tracks only — naming the file `<hash>.gpx`.
4. **The DB owns:** all write enforcement, in every path.
5. **Tracks are the only file-backed entity.** Tracks = spatial DB record **+** a my_tracks file. Waypoints and routes = spatial DB records only (no file). So the `<hash>.gpx` filename / rename-to-hash rules apply to **tracks only**.

---

## 1. The three local processes

All three share one **track-create core**: _identify → present record to DB → DB enforces → (tracks) write file as `<hash>.gpx` with the human name inside `<trk><name>`._ They differ only in the front half (where the name/geometry come from) and in direction.

### CREATE (record-save)
- **Name source:** **keyed** — the user types it.
- **Steps:** points (from temp recording file) → wkt → `geom_hash` → bbox; human name → `<trk><name>`.
- **Write order:** spatial DB record FIRST, then write the file as `<hash>.gpx`.
- **Today's bug being fixed:** record-save currently calls only `finalizeTrack` (which renames the temp file to `{name}_{timestamp}.gpx`) and **never writes a spatial DB record at all**. Fix = present the record to the DB (the missing insert) and name the file by hash.

### IMPORT
- **Name source:** **derived** — read from the source GPX's `<trk><name>`.
- **Scope:** one GPX → multiple entities. Import **identifies and recreates each entity**: tracks, waypoints, routes. (E.g. 1 file → 19 tracks + 5 waypoints.) Entry point: `importGpxAllArtifacts` (ConvoyTrackOps).
  - Each `<trk>` → **track-create** (record + `<hash>.gpx` file).
  - Each `<wpt>` → waypoint record only (`insertWaypoint`), no file.
  - Each `<rte>` → route record only (`insertRoute` / `tracks` type=ROUTE), no file.
- **Write order:** spatial DB record FIRST; tracks also get the `<hash>.gpx` file.
- This is the only path proven to work today (it presents records to the DB in the shape the DB accepts).

### SYNC (tracks-only, file-driven reconcile)
- **Direction:** inverse of create/import — it starts from the **files** and asserts an invariant.
- **Invariant on exit:** every track file in my_tracks (a) has a spatial DB record, and (b) is hash-named (`<hash>.gpx`).
- **Steps, per track file:**
  1. Read file → compute `geom_hash` (and read `<trk><name>` for the human name).
  2. If no spatial DB record for that hash → **present the record to the DB** (DB enforces — inserts the genuinely-missing ones, dedups the rest).
  3. If the filename ≠ `<hash>.gpx` → **rename** to the hash.
- **Why sync is tracks-only:** only tracks are file-backed. Waypoints/routes have no files to reconcile.
- **Today's bugs being fixed:** sync used its own inline insert keyed on `file.nameWithoutExtension` with a `SELECT name` skip-gate — it no-ops and never inserts. Fix = present records through the same DB-controlled path import uses, plus the rename-to-hash normalization.
- **Bonus property:** because the DB controls writes and sync presents every file's record, **sync becomes the rebuild-from-files recovery path** — a wiped spatial DB can be fully reconstructed by syncing the files. (This is what the 06-24 tester's manual reimport was doing.)

### Filename ↔ name relationship (tracks)
- Once a file is `<hash>.gpx`, the **only on-disk place the human name lives is inside the file** (`<trk><name>`). So create writes the keyed name into `<trk><name>`; import preserves the derived name there; sync must ensure `<trk><name>` is populated before/at rename.
- **Legacy files (pre-V2.6 recorded files)** whose human name lived only in the timestamped *filename* and whose in-file `<name>` is the default: decision = **[CONFIRM — leaning (c)]** treat `<trk><name>` as authoritative going forward and not rescue old filenames (pre-AAB, ~18 testers, clear reimport recovery path). Sync renames freely.

---

## 2. Server sharing model (V3.0)

When local tracks replicate to the global/server DB, the spatial model collapses multi-source recordings of the same ride into one shared track plus aliases.

### Two layers: LOCAL preferred name vs UNIVERSAL first-in

- **Local:** a user MAY call a track by their **own preferred name**. The local DB holds the user's preferred name (and may hold richer alias info). This is the user's private view and is flexible.
- **Universal (server):** names are decided by **first-in**, deterministically:
  - **Canonical track name = first-in for the hash.** The first source ever to submit that geometry sets the universal track name. Everyone else's recording of the same geometry resolves to that one track.
  - **Alias = first-in for the `(hash, date)`.** For each distinct date, the first source to submit that hash on that date defines the alias and its human-readable name.
  - **Everything else is OMITTED.** A later submission of an already-claimed hash (canonical set) or an already-claimed `(hash, date)` (alias set) is **dropped** — not stored, no duplicate. First-in claims the slot; the rest are omitted.
- The local preferred name does **not** override the universal. On share/replicate, the universal stays first-in-for-hash; the user's name is their local view, and may *become* an alias only if they are first-in for that `(hash, date)` — otherwise it is omitted.

### Aliases (how multiple sources attach) — first-in, per date
- **Server alias = first-in for `(hash, date)` → one alias, with a human-readable name.**
- **One alias per hash per date** (the first-in for that date claims it):
  - Same hash, **same date**, later submission → omitted (slot already claimed).
  - Same hash, **different date** → eligible to claim that date's alias slot (if first-in for it).
- **The date that keys an alias:** **[CONFIRM — leaning track/ride CREATION date, not upload/processed date]** so the same ride re-uploaded later does not claim a new alias slot.
- **Local can hold richer/other aliases**, but the **server canonicalizes** to `(hash, date) → alias (human name)`, first-in wins, rest omitted.

### Enforcement (consistent with §0.2 — the DB controls writes)
- Code just **presents** a track/alias to the DB. The DB decides first-in vs omit: canonical hash already set → omit (or treat as alias-candidate for its date); `(hash, date)` alias already set → omit. First-in claims; the rest are dropped by the DB's rules. No first-in logic in application code.

### Group-ride collapse (the purpose)
- N riders each record the same group ride; each phone saves its own track.
- If their geometries hash-match, they become **one server track** (first-in name wins) **+ aliases** (each other rider's human name, deduped to one-per-date).
- The **date qualifier** is what stops a single rider's same-day re-saves from spawning multiple aliases — this is the *correct* job of the timestamp that was previously (incorrectly) appended to filenames for crude uniqueness. The timestamp's real purpose was always alias-dedup scoping, not filename uniqueness; the hash now does uniqueness, and the date does alias-dedup.

### Open design problem — same-ride matching tolerance (flagged, not yet solved)
- `geom_hash` is **exact** (SHA-256 of WKT). Two different phones recording one ride will NOT produce identical geometry (GPS jitter, point counts) → they will NOT hash-match.
- **Exact hash** correctly dedups the **same file** re-processed (re-import / re-sync / same device) — this is what the local model needs and what V2.6 delivers.
- **Cross-device "same ride" collapse** needs **fuzzy/spatial matching** (bbox overlap + a distance metric, or a rounded/simplified-geometry hash), which exact hashing alone cannot provide. This is a **server-side design block for V3.0**, separate from the local V2.6 fix.
- Note: the lead-cart **snap-2** work may help here — snapped tracks converge toward identical geometry, making same-ride recordings more hash-matchable. Worth revisiting once snap-2 lands.

---

## 3. Scope split

- **V2.6 (local, buildable now):** the three processes above (create / import / sync) with hash-named track files, DB-first writes, DB-controlled enforcement. Solves: recorded tracks not appearing, sync no-op, filename collisions, timestamp-in-name. Exact-hash dedup only.
- **V3.0 (server, design block):** the sharing model above — canonical-track-per-hash, `hash + date → alias`, group-ride collapse — plus the **same-ride fuzzy-matching** problem, which is the main unsolved piece.

---

## 4. [CONFIRM] list (resolve before/at execution)

1. **Alias date** = track/ride **creation** date (leaning) vs upload/processed date.
2. ~~Canonical name = first source globally~~ — **RESOLVED:** universal = **first-in for hash** (canonical name) + **first-in for `(hash, date)`** (alias); everything else **omitted**. Local users keep their own preferred name as a private view that does not override the universal.
3. **Legacy filename-only names** = do not rescue; treat `<trk><name>` as authoritative, sync renames freely (leaning (c)).
4. **Same-ride cross-device matching** = fuzzy method TBD (V3.0 design block; exact hash insufficient).
