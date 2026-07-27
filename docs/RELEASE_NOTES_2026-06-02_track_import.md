# RELEASE NOTES — Track Import update (2026-06-02)

Build: feature/convoy-event-ride @ 9377f23f7. Installed on Droid 1 + Droid 2.
For tester-facing copy, use the "Tester summary" block; the rest is internal detail.

## Tester summary (what changed, plain language)
**Track import is fixed for large files.**
- Importing big GPX files (the large onX "markups" exports — 25MB+ / dozens of tracks) no longer crashes the app. You can import your full track collection in one go.
- Large tracks import much faster — tracks that used to take minutes now take seconds.
- After an import you now get a summary: **"X new / Y already in library"** — so you can see how many tracks were actually added vs. how many were already on your device (duplicates are skipped automatically).
- Re-importing the same file is safe: tracks already in your library are recognized and skipped, not duplicated.

## What was broken (for context)
- Large GPX imports crashed the app (out of memory) before any tracks were saved.
- Even when imports ran, very large tracks could take 2–3+ minutes each, looking frozen.
- No import summary appeared, so you couldn't tell what was added.

## Known limitations this build
- Waypoint and route IMPORT from GPX is temporarily turned off (tracks only). Importing a file imports its tracks; any waypoints/routes in the file are skipped for now. (In-app waypoint creation on the planning map is unaffected.)
- The artifact list shows up to 200 items.
- After importing, you may need to leave and re-enter the screen for the list to refresh (tracks appear on the map immediately).

## Internal / technical detail
- `importGpxAllArtifacts` rewritten to STREAM the GPX (BufferedReader, one `<trk>` block at a time) — file is never fully loaded into memory. Fixes the lowmemorykiller OOM on large files (28.9MB/87-track confirmed).
- Fixed an O(n²) per-track scan in the streaming loop (was re-scanning the whole accumulated buffer each line). Big tracks 3.5min → ~30s.
- `insertTrackToDb` now returns inserted-vs-dropped (via SELECT changes()); honest "Inserted track / Skipped dupe track" logging (old log always said "Inserted").
- Recap dialog shows real inserted/dropped counts.
- Dedup confirmed: tracks dedup on geometry hash; same-geometry tracks skipped; re-import = all dupes. Droid 2 DB reconciled to 67 unique tracks.

## Still to come (next builds)
- Convoy map feature parity with planning map (waypoint drop, QUEUES) — in progress.
- Waypoint/route GPX import re-enabled (separate, tested path).
- Route planning (after convoy parity).
