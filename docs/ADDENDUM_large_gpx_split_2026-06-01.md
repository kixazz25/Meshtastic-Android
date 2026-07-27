# ADDENDUM — Large GPX (>32MB) handling: split approach (captured 2026-06-01)

Refines the existing OPEN item in v25_master_checklist Section C: "Large GPX >32MB: regex → string-loop (catastrophic backtracking)."

## The crash
GPX files over ~32MB crash the import process. These large files are not single huge recordings — they are **50+ separate GPX downloads concatenated into one file**. (onX-style markup exports bundling many tracks.)

## TWO fix approaches (not mutually exclusive)
1. **Parser fix (already noted in Section C):** the parser uses a regex that catastrophically backtracks on huge input. Switch regex → string-loop so the parser survives a big file as-is. Makes import robust to whatever size comes in.
2. **Split-before-import (Fred's framing, 2026-06-01):** since the big file is really N concatenated GPX downloads, SPLIT the oversized GPX into multiple ~50-track pieces BEFORE import, and process each piece. Sidesteps the size limit entirely by feeding the importer bite-sized chunks.

## Why split is appealing given the new dedup/recap work
- Each piece gets its own import pass + its own RECAP (the breakdown we just built) — more observable.
- The session-scoped in-memory dedup lookup already handles duplicates ACROSS batches correctly (begin/end session spans the pieces, or each piece checks against what's already in the DB), so splitting does NOT break dedup — same-geometry tracks across pieces still collapse / alias correctly.
- Keeps each import batch small = less memory pressure, less crash surface.

## To decide when worked
- Parser fix vs split vs both. Split alone may be enough to dodge the crash; parser fix makes it bulletproof regardless of input. Likely do split first (simpler, directly addresses the concatenated-download reality), keep parser-fix as the robustness backstop.
- Split mechanics: detect track boundaries in the GPX XML, emit chunks of ~50 tracks each, feed each to the existing import path.
- Where the split runs: pre-import step (scan the file, if >threshold or >N tracks, split into temp pieces, import each).

## Status
OPEN — refinement of the Section C >32MB item. Not started. Belongs with GPX Import Expansion work.
