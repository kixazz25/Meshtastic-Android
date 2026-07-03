# GroupTrack Manual — New Sections (2026-06-30 draft)

**Status:** DRAFT for folding into `grouptrack_manual.html` (drill-down edition) once screen captures are taken.
**Covers:** the V2.6 track services work committed in `d719fbc95` — import live feed, 4-way import recap, track aliases (display + swap/rename), alias-aware search.
**Screen captures:** each `[SCREENSHOT NEEDED: …]` marks a frame Fred captures when ready. Cross-referenced to the existing drill-down structure (Convoy Map / Planning Map launch points → options → single screen).

These sections were drafted against the reference/xref docs (`field_crossref_raw`, `navigation_xref`, `where_used_raw`, `function_universe_raw`, `GroupTrack_AllDocs`). Navigation paths below mirror how the existing manual reaches each screen.

---

## 1 — Importing tracks (live feed + recap)

**Where:** Convoy Map › Import Artifacts screen (the in-app importer). File-tap import is disabled — import from within GroupTrack.

GroupTrack imports a GPX file by splitting it into individual tracks and adding each one to your library. As of this build, the import screen shows you exactly what happens to every track, in real time, and gives a clear summary at the end — nothing happens silently.

### 1.1 — Starting an import
Open the Import Artifacts screen, choose your GPX file, and start the import.

`[SCREENSHOT NEEDED: Import Artifacts screen with a file selected, before starting]`

### 1.2 — The live feed
While the import runs, a scrolling feed shows one line per track as it is processed. Each line tells you the outcome for that track:

- **INSERT** — a new track GroupTrack hasn't seen before; it's added to your library and saved as a hash-named file.
- **ALIAS** — the same route as a track you already have, but under a different name; GroupTrack keeps your existing track and records the new name as an alias (an alternate name) for it.
- **DUPLICATE** — the same route under a name you already have; nothing is added (no duplicate is created).
- **SKIP** — the track had no usable geometry (no track points) and was skipped.
- **ERROR** — something went wrong with that one track; the rest of the import continues.

`[SCREENSHOT NEEDED: Import live feed mid-run, showing a mix of INSERT / ALIAS / DUPLICATE lines]`

### 1.3 — The recap
When the import finishes, a recap dialog summarizes the run with four counts:

- **New** — tracks inserted
- **Aliased** — alternate names recorded for tracks you already had
- **Duplicate** — exact name+route matches, nothing added
- **Skipped** — tracks with no geometry

`[SCREENSHOT NEEDED: Import recap dialog showing the four counts (e.g. 16 duplicate)]`

> **Note — malformed files.** If a GPX file was cut off mid-recording (no closing tags), import will report 0 new tracks for it. The live feed/recap shows this rather than failing silently. A properly finished recording imports normally.

---

## 2 — Track names and aliases

**Where:** Convoy Map / Planning Map › open a track › track detail panel.

Every track has one **official name** — the name you see in lists and on the map. A track can also have one or more **aliases**: alternate names it's also known by (for example, the same trail recorded by another rider under a different spelling). Aliases let GroupTrack recognize that two recordings are the same route without creating duplicates, and they make a track findable by any of its names.

### 2.1 — Seeing a track's aliases
Open a track's detail panel. If the track has aliases, an indicator shows that alternate names exist; tap it to view the full list. The official name is always shown at the top; aliases are listed below it.

`[SCREENSHOT NEEDED: Track detail panel with the "has aliases" indicator visible]`
`[SCREENSHOT NEEDED: Expanded alias list for a track (official name + alternate names)]`

### 2.2 — Making an alias the preferred name (swap)
If you'd rather a track go by one of its alias names, choose that alias and make it the preferred name. GroupTrack swaps the names: the alias becomes the official name, and the previous official name becomes an alias. This is a local change — it affects how the track appears on your device.

`[SCREENSHOT NEEDED: The alias action menu with the "make preferred name" option]`
`[SCREENSHOT NEEDED: Track detail panel after a swap, showing the new official name]`

### 2.3 — Renaming an alias
You can edit the text of an individual alias without affecting the official name or the track's data.

`[SCREENSHOT NEEDED: Editing an alias's text]`

> **Note — trail names.** Trails (the regional trail data you import as reference) are managed externally and aren't renamed or swapped the way your own tracks are. Their alternate names are still searchable.

---

## 3 — Searching by name or alias

**Where:** the universal search (Convoy Map search) and the planning search.

Search now matches on a track's official name **or any of its aliases**. If you remember a trail by a different name than the one it's filed under, searching that name still finds it. Results always display the official name; when a result matched because of an alias, that's indicated so you know why it appeared.

`[SCREENSHOT NEEDED: Search results where a track matched via an alias, with the alias-match indicator]`

---

## 4 — Deleting a track

**Where:** the track detail panel.

Deleting a track now removes it completely — the map record, its stored details (distance, duration, etc.), all of its aliases, and its saved file — in one action. There are no leftover fragments.

`[SCREENSHOT NEEDED: Delete confirmation from the track detail panel]`

---

## Folding-in notes (for the doc build, not tester-facing)
- Sections 1.x slot under the existing **Convoy Map › Import Artifacts** launch point in the drill-down tree.
- Sections 2–4 slot under **open a track › track detail panel** (shared between Convoy Map and Planning Map hosts — `ArtifactDetailPanel`).
- The alias-display / swap-rename / search-highlight UI is **not yet wired** in the build as of `d719fbc95` (services exist; detail-panel wiring is the next session's first task). **Do not publish sections 2–4 to testers until that UI ships and is captured.** Section 1 (import feed + recap) IS in the build and can be captured now.
- Replace each `[SCREENSHOT NEEDED]` with the captured frame at the same drill-down depth/style as the existing 06-23 screenshots.
