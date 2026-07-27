# NOTE — Large-GPX crash CONFIRMED LIVE (2026-06-01 EOD)

Update to ADDENDUM_large_gpx_split. This is no longer theoretical.

## What happened
During the Droid 2 track import (after the trail import + recap succeeded), the TRACK import CRASHED on a large GPX file and locked up the device. Confirmed cause: the oversized file (50+ concatenated GPX downloads in one file, >32MB) — the same large-file crash recorded in checklist Section C. Fred is rerunning just the last unprocessed file.

## Why the rerun is SAFE (dedup makes import idempotent)
Rerunning after a partial/crashed import does NOT create duplicates: tracks already imported before the crash collapse on geom_hash (and the source-uid skip), so only genuinely-new tracks from the unprocessed file get added. Retry freely.

## PRIORITY BUMP
The large-GPX split (ADDENDUM_large_gpx_split) is now a CONFIRMED ACTIVE CRASHER, not a someday item. That oversized file will crash every import attempt until the split (or parser string-loop fix) lands. Raise its priority accordingly.

## IMMEDIATE WORKAROUND (until the fix is built)
Manually split that one oversized GPX OUTSIDE the app before importing: GPX is XML, split on `</trk>` boundaries into a few smaller files (~50 tracks each), import the pieces separately. This is the manual version of the split fix we'll build.

## Device note
If the rerun locks the device again, it's the same media-scan + large-file thrash from earlier today — reboot + `adb kill-server`/`start-server`, then retry (idempotent, so safe).
