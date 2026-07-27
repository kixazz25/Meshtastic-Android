# Cookbook / User Manual Segment — Import Artifacts (Tracks from GPX)
_Updated 2026-06-02. Fold into grouptrack_manual.html (repo docs/) — Planning section, "Work with Artifacts → Import." This is the import-artifacts cookbook entry reflecting the 06-02 track-import changes. Manual HTML lives in the repo and isn't in this Drive folder; merge there next repo session._

## What this function does
Import Artifacts reads a GPX file from your device and adds its **tracks** to your GroupTrack library so they draw on the map and appear in the artifact list. (Trail-source imports are a separate flow — the UGRC catalog. This entry covers importing your own GPX track files, e.g. onX "markups" exports.)

## Recipe: Import tracks from a GPX file
1. Open **Work with Artifacts** (accordion button on the map).
2. Choose **Import** and pick your GPX file (the file picker opens your device storage).
3. The import runs. Large files are processed one track at a time, so even big exports (dozens of tracks, 25MB+) import without crashing.
4. When it finishes, a **recap** shows: **"X new / Y already in library."**
   - **X new** = tracks added this run.
   - **Y already in library** = tracks skipped because the same track (same shape) was already saved.
5. New tracks draw on the map immediately. (If the artifact LIST looks empty, leave the screen and come back — known refresh gap; the data is saved.)

## How duplicates are handled
- A track is identified by its **shape (geometry)**, not its name. If you import the same track twice — even under a different name — the second copy is recognized and **skipped**, not duplicated.
- This means **re-importing the same file is safe**: the recap will show "0 new / N already in library."
- One file can legitimately contain the same track more than once (e.g. several recordings named the same); those collapse to one entry automatically.

## Recipe: RESYNC TRACKS (recover device tracks not in the database)
Use this when track files exist on the device but aren't showing in your library — for example after an update, or if you added GPX files to the device's track folder by some means other than Import.
1. Open **Work with Artifacts**.
2. Choose **RESYNC TRACKS**.
3. It scans the device's saved track files and adds any track that isn't already in the database (existing ones are skipped by shape).
4. Run it again to confirm — a second run should add **0**, meaning everything on the device is now in the library.

## Tips & limits
- **Tracks only, this build.** Importing a GPX brings in its tracks. Waypoints and routes inside the file are skipped for now (a separate import feature is coming). To place waypoints, use long-press on the map (Planning Map).
- **Very large files import fine now.** The earlier crash on big files is fixed. If any import ever stalls, note the file's size and track count and report it.
- **The list caps at 200 shown** — larger libraries are being paged in a later build.

## Troubleshooting
- *Imported tracks don't appear in the list* → leave the screen and return (refresh gap); they're already on the map.
- *Recap says "0 new"* → those tracks were already in your library (expected on a re-import).
- *A track is missing after an update* → run RESYNC TRACKS.
- *Import seems stuck* → note file size + number of tracks, report it.

## Change log for this segment
- **2026-06-02:** Import no longer crashes on large GPX files (streaming rewrite). Large tracks import in seconds instead of minutes. Added the "X new / Y already in library" recap. Documented shape-based dedup and safe re-import. Documented RESYNC TRACKS as the device-file → database recovery tool. Noted tracks-only limitation (waypoint/route import deferred) and the post-import list-refresh gap.
