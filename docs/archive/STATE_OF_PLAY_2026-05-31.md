# STATE OF PLAY — end of 2026-05-31 (read this first next session)

## Where everything actually is (nothing lost)
- **Repo `~/Meshtastic-Android/docs/` = 231 files = THE authoritative source.** Complete, untouched. recommit11 commits it nightly. This is the real set.
- **`~/Meshtastic-Android/docs_BACKUP_2026-05-31/` = full safety copy** made today. Redundant backup.
- **Drive `GroupTrack_docs/` (My Drive) = the working folder Claude CAN read/write.** Holds: CATALOG.md, v25_master_checklist.md (+readable), handoff_2026-05-31.md, INDEX.md (+readable), daily_doc_update. These are reliable — files Claude creates land where Claude can see them.
- **Drive `GroupTrack_history/` = empty.** Intended for the 200 history docs; backfill NOT done.
- **Drive root = cleaned.** 291 duplicate copies (the failed 11:09 backfill) deleted today. Recoverable in Drive trash 30 days if ever needed.

## What WORKS (the win — keep it)
- Claude reads/writes the `GroupTrack_docs` folder reliably every session. No more feeding docs for these.
- **CATALOG.md** exists — the index of every document (15 topic groups, current ✅ vs superseded ⤵). This is the thing that replaces memory as the "what exists / which is current / where" linkage. Version picks still need date-confirmation (tagged "confirm").
- Master checklist is live markdown in Drive, current, with the import-dupe FLAWs and snap-2 route decision captured.

## What DID NOT work (open problem for next time)
- **Getting the 200 existing repo docs UP to Drive where Claude can read them.** Three attempts failed:
  1. Drive-for-Desktop "My Computer/backup" → lands in Computers branch, connector can't see it.
  2. Copy into root → 291 duplicates, connector's root query returns empty.
- **Root cause found:** the connector's My-Drive root ID is `0ANN9Ght6zDKiUk9PVA`; `parentId='root'` searches return empty, so Claude can only see files by folder-ID or by distinctive title search. Files Claude CREATES are visible; files pushed from PC have not become visible.
- Account matches on both sides (kixazz25@gmail.com), so it's NOT a two-account issue.

## NEXT SESSION — first moves (do not re-derive)
1. Decide how to get history docs reachable. Untried option: in Drive web UI, drag the repo docs INTO `GroupTrack_docs`/`GroupTrack_history` so they inherit a folder parent Claude can query by ID (root-level didn't work; folder-level might). Test with ONE file first, confirm Claude sees it, THEN bulk.
2. If that fails too: fall back to uploading only the handful of LIVING docs per session (not all 200) — Claude maintains those, history stays read-only in repo.
3. Confirm CATALOG.md version picks against `ls -lt` dates.

## THE ACTUAL WORK (queued, not started — was displaced by the docs-system effort)
1. Cosmetic/quick-win pass: Tier 1 (5 cosmetic, one patch) + Tier 2 (import/track-source cluster) + z12 separate. Hold `!!`/safe-call tidy separate.
2. Dupe-creation roadblocks: import (name,geometry) existence-check + post-import dedupe pass + DB UNIQUE constraint + source_id + null-naming.
3. Route creation: point-to-point SNAP-2 (trails+tracks). Tester-chosen; do NOT revert to freehand.

## Honest note
Tonight was spent almost entirely on the doc-storage system, not on app code. The forward-going piece (catalog + Claude-maintained Drive folder) is real and working. The backfill of history is the unsolved piece. The app-work above is untouched and waiting.