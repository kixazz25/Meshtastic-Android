# GroupTrack Release Notes — Next-Release Block (2026-06-30 draft)

**Status:** DRAFT for the NEXT build's release notes (TBA — goes live only when the next AAB ships). Does NOT modify the current LIVE V2.5 notes (dated 2026-06-22). Cumulative: the existing V2.5 block stays as-is; this is a new dated block added above it when the next release ships.

**Do not publish until:** the track services work is field-verified on both devices AND the detail-panel UI (alias display / swap / search highlight) is wired and tested. The import live feed + recap is already in the build; the alias UI is next session's work.

---

## What's new — (next release, date TBA)

**Smarter track import.** When you import a GPX file, GroupTrack now shows a live, line-by-line feed of every track as it's processed, and a clear summary at the end — new tracks added, alternate names recorded, duplicates skipped, and any tracks with no usable data. Nothing happens silently.

**Track aliases.** Tracks can now carry alternate names. If the same route turns up under a different name (yours or another rider's), GroupTrack recognizes it's the same track and records the other name as an alias instead of creating a duplicate. You can view a track's alternate names, pick which one is its preferred name, and rename an alias — all from the track's detail panel.

**Search finds every name.** Searching now matches a track's official name or any of its aliases, so a trail is findable by whatever name you remember.

**Cleaner deletes.** Deleting a track now removes it completely — its map record, its details, its alternate names, and its file — with no leftover fragments.

---

## ⚠ MANDATORY after this update — resync your tracks

> **PLACEHOLDER — exact wording is Fred's draft from the 06-29 session. Re-pull that wording and paste it here verbatim; do NOT invent it.**
>
> Intent (for reference only, not final copy): sync is now manual-request-only by design. After updating to this build, testers must open the track import screen and run **RESYNC TRACKS** once to reconcile their saved tracks into the updated database. (This is distinct from, and in addition to, the existing "re-import your trails" update step.)

---

## Folding-in notes (doc build, not tester-facing)
- Add this as a new dated block ABOVE the existing "Updated 2026-06-22" V2.5 block in `grouptrack_release_notes.html`. Keep the existing install-as-update warning and the V2.5 content unchanged (cumulative notes).
- The MANDATORY RESYNC line is the queued 06-29 item — its final wording lives in the 06-29 conversation; re-pull before publishing.
- Hold the "Track aliases" + "Search finds every name" lines until the detail-panel/search UI ships and is verified. The "Smarter track import" line is shippable as soon as import is field-certified end-to-end.

---

## What's new — MAPS FOLLOW THE TRACKS (added 2026-07-02; ship when the next AAB cuts)

**Save maps for any track.** Open a track's details and tap **SAVE MAPS** — GroupTrack downloads the map tiles covering that track's area (padded out half a mile on every side) so the maps are on your device when you're off the grid.

**Maps on import (optional).** When you import a GPX file, check **"Download maps for imported tracks"** on the import screen and GroupTrack will pull the map coverage for every new track in that file. It's off by default — a large import can be a lot of map data, so you opt in when you want it.

*Folding-in note (doc build, not tester-facing):* shippable now — the feature is committed and tested (SAVE MAPS button verified downloading; import checkbox is the final piece, under review 07-02). Add above the prior dated block, cumulative. If the checkbox review surfaces an issue, hold this "on import" line until resolved.
