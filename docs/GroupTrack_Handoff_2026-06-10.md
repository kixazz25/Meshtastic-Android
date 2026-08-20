# GroupTrack — Handoff / End-of-day recap — 2026-06-10

_Note-to-future-Claude. Read this + GroupTrack_V25_LivingChecklist_CONSOLIDATED_2026-06-10.md first next session. Project runs out of Fred's Downloads; these docs are the backstop when memory/context is thin. The CURRENT doc baseline is the 2026-06-06 set (checklist v3 + DecisionLog + 06-05 manual/release notes) — build on Fred's upload, NOT the Drive connector (which lags at 06-04 and can't see the `google_docs` folder)._

## FINISHED + COMMITTED today
- **C-1: Planning-map snap-2 mirror — DONE, proven on device, committed.** The planning map now traces route geometry along trails on every redraw path, matching convoy:
  - rollback redraw — 56713ab1e
  - resume redraw — 5630fb0b9
  - undo redraw — 6b1628f82
  - (live onMapTap draw was already traced from an earlier session — no patch.)
  - Method: read each draw site LIVE (raw Python rb — file is CRLF, cat/grep/sed lie), wrap the redraw in `scope.launch { withContext(IO){ queryTrailsByViewport + queryTracksByViewport → buildSegments } → evaluateJavascript }`, build, prove on device (Utah/St.George viewport, Trails ON), commit one site at a time.
  - Consequence: **snap-2 is now DONE on BOTH maps.**

- **Cosmetic batch — 2 of 9 low-impact items done + committed at `2d12a81fd`** ("2 files changed, 4 deletions"):
  - **[8.4]** all three snap-2 diagnostic logs stripped — ConvoyScreen.kt 557 "tracedLen=" + 718 "S2 tracedLen=", ConvoyMapViewerScreen.kt 512 "S2P tracedLen=". (No build needed — no behavior change.)
  - **[6.6]** duplicate `import androidx.compose.material3.AlertDialog` removed (was at LIVE lines 35 & 87 — the master checklist's "34 & 85" had drifted; confirms read-live-first).

## STOPPED — bad terminal (not a code problem)
Git-Bash misbehaved all evening: crashed twice, strip writes needed 3 passes to land (a replace once ran against pre-write state), and at the end the terminal REPLAYED a stale command onto a new line (bash paren syntax error). That last one was the stop sign. Git also auto-packed (gc) mid-commit — BENIGN (routine maintenance, repo tidied, commit landed clean). Fred is REBOOTING to clear the stale-input replay + dropped writes.
- **Lesson for next session:** heredocs (`python3 << EOF`) and `git diff` both choke Fred's Git-Bash. Use SINGLE-LINE `python3 -c` edits with a match-count guard, verify with byte/count reads, NOT `git diff`.

## DECIDED / RESEARCHED (not code)
- **Open-issue list CONSOLIDATED** (Fred's instruction: full problem definitions, no dropped content, grouped). Two companion docs: CONSOLIDATED checklist (by function, 11 groups + Deferred, source-tagged) + OpenIssues BY_SCREEN (same items re-sorted by screen). These are the working problem list for finishing 2.5.
- **Plan:** do the 9 LOW-IMPACT items first to shrink the list, THEN the higher-impact items (regrouped by function). 2 of 9 done. The formal ACCEPTANCE checklist (walk-through certifying the finished product) comes AFTER cleanup, built against the cleaned-up app — NOT now.
- **Lead-track redesign [2.1]:** design authority = GroupTrack_LeadTrackReplacement_Spec.docx (May 31; confirmed no newer doc supersedes). Replace the 3-flow pipeline with one lead-position polyline + drawTrack. Discovery-first against field_crossref. Functional, do after routes + planning cleanup.
- **C-2 undo "bug" = non-bug:** undo works; the earlier symptom was map-switch shared-state contamination (RouteManager singleton shared across maps) — folds into the auto-save-on-map-switch item [1.5].
- **NH trail data — PARKED:** source-document path (NH GRANIT as the 8th trail_sources.json entry), not the manual DB-load. Full story: GroupTrack_NH_TrailImport_task_2026-06-10.md.

## NEXT SESSION — first moves (after Fred reboots)
1. Confirm clean tree: `git log --oneline -1` (want `2d12a81fd`) + `git status`.
2. **[6.7] !!/safe-call tidy** — the next low-impact item. NOT mechanical like 8.4/6.6 — it's judgment-per-line (a `!!` may need `?.`/guard, not just deletion). START READ-ONLY: list the sites, decide each, then one write + verify. Defer if it's a rabbit-hole.
3. Then [8.8] lint tidy → then the ADD/MOVE items on a good terminal: [3.5] convoy `?` move, [1.7] route picker cosmetics, [3.7] z12, [3.8] marker shape.
4. After the 9: functional review + schedule discussion; then the heavier functional clusters (import [4.x], DB/dedup [5.x], SpatialDisplayManager [3.2], QUEUES [3.3], waypoint-drop [3.4], bbox-persistence [3.1], lead-track [2.1], ANRs [8.1/8.2]).

## DOC HYGIENE
- The 06-07 and 06-10 sessions still need a dated Decision Log block appended (append-only). [batch item 9.6]
- Manual + release-notes snap-now-live edits are drafted (GroupTrack_Manual_CreatingARoute_update_2026-06-10.md + GroupTrack_ReleaseNotes_update_2026-06-10.md) — apply IN PLACE to the 06-05 living docs when ready; don't regenerate.
