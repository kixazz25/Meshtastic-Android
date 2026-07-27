# RESULTS APPENDIX — 2026-06-01 EOD (append to STATE_OF_PLAY_2026-06-01_EOD.md)

## DROID 2 FULL-IMPORT RESULTS — SUCCESS
- Recap rebuild (with the var dropped/aliased declaration fix): BUILD SUCCESSFUL (37m58s).
- Droid 2 (serial 24039703201775) upgraded v1 -> revised-v3 on its OWN owned DB (normal path, no foreign-DB issue — confirming the gate authority "bug" was indeed a test-rig artifact).
- TRAIL import + recap: **totals were PERFECT.** Recap reported real breakdown buckets (the fix works). **7 aliases**, **2 out-of-region** (rejected bucket — previously invisible, now reported).
- 7 aliases matches Droid 1's result — same source data, same same-geometry-different-name pairs (Equestrian Cg-type) correctly aliasing on both devices. Good cross-device check.
- TRACK import: run next (in progress at write time).

## STATUS
- P2 dedup core + import-recap patch: PROVEN on both a clean install (Droid 1) AND a real v1->v3 upgrade (Droid 2). Recap reports real numbers. **READY TO COMMIT.**
- NEXT ACTION: commit P2 dedup core + recap patch. Then per the sequence decision: ROUTE PLANNING pass (2 days) -> AWS mirror + cleanup.

## CARRIED TO TOMORROW (see the handoff + addendums)
- NEXT_SESSION_HANDOFF_aws_and_routes — route planning (snap-2) + AWS DB update detail
- ADDENDUM_esri_tile_store_and_forward — pull-once-cache-on-AWS tile idea (gating Q: Esri ToS on re-hosting)
- ADDENDUM_convoy_waypoint_and_queues — two PORT tasks (convoy waypoint drop, QUEUES button) — good build-window work
