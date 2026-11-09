# Step 14c — ride certification: what was found and fixed (11-08), so it is not rediscovered

**Result:** rides CERTIFIED twice in a row vs 2.7e on copies of the frozen data (runs 2026-11-08_1225 and _1229: 2042 same; only the 6 labelled lenient-JSON lines differ). Commit 9657a8ac1 (pushed). The app still runs 2.7e's ride code; the switch is next.

## The method (keep using it)
- `gt_cert_v14.sh <build> rides` (v12, v13 superseded). Records the Droid log LIVE during the run (the map's DetailGate spam flushes the buffer) to `$CERT/logcat_<build>.txt(.all)`.
- Certified only when: `# rides done` written, zero unlabelled DIFFs, zero SpatialDb/SQLite warnings FROM GROUPTRACK'S OWN PROCESS (v13 wrongly counted another app's warning).
- Two clean runs in a row before calling it certified.
- Don't open the Rides screen during a run (its heal writes the live rides folder).
- 2.7e ride code stays live during certification; GtRidesAndroid is in its own file; the harness copies OLD results into the shared classes. The four wrappers are installed only at the switch.
- The app is not debuggable (run-as fails); the harness copies its exported packages to GroupTrack/gtcert_rides_export/<old|new>/ for adb pull.

## Harness lessons (STEP14C-B .. F)
- B: one input only the old code can read (single-quoted "lenient" JSON) shifted every later line and the clock ticks: 201 false DIFFs. Fix: the lenient cases run LAST (case 8, labelled expected DIFF), and the compare re-syncs on keys (an extra line is one DIFF, not a cascade).
- D: after every case the app's database handles must still be that side's copies, and open; otherwise the run aborts.
- E/F: probes around each build (route id, narrative rows in the copy, readRouteNotes, routeRecipe, env recipe) and the frozen/copy file report (sizes, fingerprints, -journal/-wal) — this is what found the real cause.

## THE REAL CAUSE of the "missing trailhead" (runs 0928, 1019, 1207) — STEP14C-G
- The app's startup database repair (GtDbAudit.repair, 10 s after the screen opens) reads SpatialDbManager's spatial handle, then later the data handle, and ATTACHes one to the other to delete rows "pointing at nothing".
- When a cert run swapped the handles in between, it paired LIVE spatial with the TEST-COPY data database and deleted the copy's 9 route notes for "broken ridge 11/04/2026 2" (a route Fred deleted from live).
- The other order (copy spatial + LIVE data) would have deleted LIVE route notes / route names and live track files for anything newer than the frozen copy.
- Fix (TEST-ONLY, removed with GtCertRun): GtCertRun.active is set when a request is taken; DbAudit.repairNow logs "DBREPAIR skipped: a certification run is using swapped database handles" and returns.
- Live data checked 11-08 12:33 (copies in GroupTrackSpike/_data/live_2026-11-08): 22 routes (newest 11-05) all with notes, none with fewer notes than on 11-06; 131 tracks (newest 11-07 10:25) all with files. Nothing lost: nothing newer than the frozen copy existed, so even the dangerous order had nothing to delete.
- Rule for any future test that swaps handles: startup jobs that read SpatialDbManager handles at two moments must not run during the test.

## Port facts recorded
- A recipe anchorName stored as JSON null becomes the trailhead name "null" in the GPX (org.json's optString). The port keeps it byte for byte; it belongs with the backlog's "null" trails item.
- Single-quoted (lenient) ride JSON: 2.7e accepts it, the shared code rejects it ("That isn't a GroupTrack OG ride file.") — accepted as an expected difference (PORT_NOTES a3).
