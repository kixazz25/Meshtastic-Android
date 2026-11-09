# POST-CONVERSION ACTIVITY — the import rules (decided 11-08 17:27–17:59)

**Status (Fred 17:59):** decided, NOT built. Done AFTER the 2.8 conversion. Step 15 ports the import AS IT IS today, with today's rules, line for line and certified identical. This design is applied afterwards as its own change, on the shared code, once for Android and iPad. Do not re-decide anything below; it is settled. Fred: "it will focus the components and make refactoring easier when we are done."

Source of the facts about today's code: inbox/step15_2026-11-08/GPX_identify_and_write_2026-11-08.md (every line cites file:line).

## The frame (Fred 17:32)
- 4 file formats: GPX, KML, KMZ, GeoJSON. 4 artifact types: waypoints, routes, tracks, trails.
- The target of every import is a GPX with each artifact already identified; the GPX importer then reads it by fixed rules.
- Only the import process is in scope.

## Definitions (17:39–17:41)
- **Track** = what was driven: a recording, points with times (usually elevation).
- **Route** = what was planned: no times.
- **Trail** = a line someone means as a trail. GPX has NO standard trail value ("does it exist?" — not as a standard; in practice trail GPX files are untimed `<trk>` with nothing marking them). Only the person importing, or GroupTrack's own mark, can say "trail".

## THE DESIGN: two import processes (Fred 17:56–17:59)
Both end in the SAME GPX importer (one writer, one set of rules). Only the preprocessing differs. The panel's "Are these files trails?" question goes away; the Map menu gets two entries. Fred: "aligned in the proper sections of the maps and grouped together; it simplifies the panel for imports."

### 1. Import trails
- Any format (GPX, KML, KMZ, GeoJSON).
- Every line becomes a trail — timed or not, single or multi-piece.
- The preprocessor writes GroupTrack's trail mark (`<type>grouptrack-trail</type>`) on every line, GPX included (a GPX -> GPX preprocess), then the GPX importer writes them. The importer's only trail rule is the mark.
- OPEN: points in a trail file (trailheads, parking): keep as waypoints, or import only the lines?

### 2. Import tracks, routes and waypoints
- Any format. The content decides; no question.

| Artifact | GPX | KML / KMZ | GeoJSON |
|---|---|---|---|
| Waypoint | `<wpt>` | Point | Point / MultiPoint (each point one waypoint) |
| Route | `<rte>`; a `<trk>` WITHOUT times | LineString (no times) — **"line strings are routes. period."** (17:44) | LineString / MultiLineString without times |
| Track | a `<trk>` WITH times | `gx:Track` / `gx:MultiTrack` (times) | a line with times (`coordTimes`) |
| Trail | never — except a line carrying our `grouptrack-trail` mark (a GroupTrack trail export) | never | never |
| Not imported | — | Polygon (an area) | Polygon |

### Rules for every import
- **A point is always a waypoint** (17:37).
- **The waypoint's description is saved** (today it is thrown away).
- **The waypoint type comes from the file's type or icon** (GPX `<type>` / `<sym>`, KML icon, GeoJSON `type` / `marker-symbol`) through a MAPPING TABLE of source values to our 10 types (trailhead, fuel, gate, hazard, scenic, water, camp, parking, rally, other); else "other". Not guessed from the name.
- **A multi-piece line without times becomes one route per piece** ("X", "X (part 2)") — never joined (joining draws a straight line between pieces that do not touch) (17:48).
- **A multi-piece recording (`gx:MultiTrack`) becomes ONE track with one segment per piece** (17:48).
- **Elevation is kept** wherever the file has it.
- **No guessing from names, folders or styles.** Geometry and times decide; only the Trails import overrides.
- **Resync tracks** (the my_tracks folder sync) is NOT an import: it keeps GroupTrack's own track files as tracks.

## To build with it (from the 11-08 reference, section D)
- The mapping table — drafted from the real files in inbox/step15_testset_real/ for Fred to correct.
- GroupTrack's single-trail export (GtGpx.trail) writes the trail mark (today it does not, so a trail re-imports as a track — contradicting the panel's own text).
- The converter: keep elevation; `gx:Track` / `gx:MultiTrack` (times, space-separated `gx:coord`); routes as `<rte>`; multi-piece rules above; descriptions kept.
- Emailed / tapped KMZ and GeoJSON reach the import (today only GPX and KML are recognised by their text).
- **Keep originals that went through the converter** (Fred 18:23: post-conversion, not now). Today the import panel deletes a picked KML from Download after moving it into the app (the staged copy reads as `<kml`), leaving only the lossy `-Converted.gpx` — the KML's elevation and `gx:Track` recordings are then gone if the rider has no other copy. KMZ and GeoJSON originals are kept only by accident (the text check cannot read them).
- Retire the importer's unused `asTrails` switch and the old direct KML import (`ConvoyTrackOps.importTrackFile`, no callers since ONEIMPORT 11-04) after the callers check.
- The older `<type>trail</type>` (11-03) form: decide whether it is still read.
- Tests: one case per cell of the table, each with its known right answer, plus the Hillbilly Hills KMZ/GPX pair.

## History found on the way (for the record)
- The original track import read KML directly (`ConvoyTrackOps.importTrackFile`: GPX or KML, Placemarks split into my_tracks .kml files, dates from `<when>`). ONEIMPORT 11-04 replaced it: KML/KMZ/GeoJSON always go through the converter to GPX; only GPX reaches the database import. Leftovers still live: my_tracks lists .kml, the earliest-time reader reads `<when>`, emailed KML is recognised.
- Today's converter on "No": lines -> tracks (elevation dropped; a MultiGeometry split into several tracks); points -> waypoints type "other"; descriptions and attributes lost; `gx:Track` and polygons dropped silently. No test exists for this path.
- No code identifies tracks or trails from a Gaia or onX value; their only effects: a route's `<desc>` becomes its narrative, `<extensions>` stripped, date formats.
