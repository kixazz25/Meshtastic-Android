# GroupTrack — 2026-06-10 doc set — START HERE

_Drop this folder's contents into GroupTrack_docs. These are the current working docs as of end-of-day 2026-06-10. Read in this order next session._

## Read order
1. **GroupTrack_Handoff_2026-06-10.md** — what finished today, what's committed, where we stopped and why, first moves next session.
2. **GroupTrack_V25_LivingChecklist_CONSOLIDATED_2026-06-10.md** — THE working problem list. Every unresolved V2.5 open item, full problem definition, grouped by function (11 groups + Deferred), source-tagged. This is the 2.5 task list.
3. **GroupTrack_V25_OpenIssues_BY_SCREEN_2026-06-10.md** — the same items re-sorted by screen/menu, cross-referenced by [bracket IDs]. Use alongside #2 for line-by-line review + sequencing.
4. **GroupTrack_CosmeticBatch_RunSheet_2026-06-10.md** — the 9 low-impact quick-win items as a run-sheet (discovery → confirm → edit → commit, per item).

## State at end of 2026-06-10
- COMMITTED today: C-1 planning snap-2 mirror (56713ab1e / 5630fb0b9 / 6b1628f82) → snap-2 now DONE on BOTH maps.
- COMMITTED today: cosmetic batch 2 of 9 — [8.4] strip diagnostic logs + [6.6] remove duplicate AlertDialog import = **2d12a81fd**.
- NEXT: [6.7] !!/safe-call tidy (judgment-per-line; start read-only), then [8.8], then the add/move items.
- STOPPED on a misbehaving terminal (crashes + dropped writes + stale-command replay). Fred rebooting. Next session: use single-line `python3 -c` edits with a match guard, verify by byte/count, NOT `git diff`/heredocs.

## Supporting / reference docs in this set
- **GroupTrack_Manual_CreatingARoute_update_2026-06-10.md** — in-place edits for the 06-05 living manual's "Creating a Route" (snap now live on both maps). Apply when ready; don't regenerate.
- **GroupTrack_ReleaseNotes_update_2026-06-10.md** — matching snap-now-live edits for the 06-05 release notes.
- **GroupTrack_NH_TrailImport_task_2026-06-10.md** — NH trail data task/discovery/way-forward (PARKED; source-document path).
- **TRAIL_SOURCE_NH_2026-06-10.md** — the NH GRANIT trail_sources.json entry draft (2 fields to verify from a browser).
- **load_nh_trails_to_db_2026-06-10_v1.py** — PARKED manual DB-loader (only if the source path stalls).

## Doc baseline reminder
The current baseline these build ON is the 2026-06-06 set (LivingChecklist_2026-06-06_v3, DecisionLog_APPEND_2026-06-06, UserManual_2026-06-05, ReleaseNotes_2026-06-05). Build on Fred's uploads, not the Drive connector (it lags at 06-04 and can't see the `google_docs` folder). Still TODO: append a 06-07 + 06-10 block to the Decision Log (append-only).
