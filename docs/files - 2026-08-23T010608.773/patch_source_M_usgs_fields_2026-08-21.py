#!/usr/bin/env python3
"""
patch_source_M_usgs_fields_2026-08-21.py

PATCH M — the source that failed 100% of the time, in every state.

MEASURED: usgs_national_trails reported 391/391 errors (New Hampshire) and
718/718 errors (Southwest). Clean HTTP 200 both times, 1,760,847 chars of valid
GeoJSON. Records parsed -- `processed` counted them -- then every one failed.

ROOT CAUSE, found by reading the code rather than guessing:

    TrailImporter.kt:434
    val uid = props.optString(src.fieldId, "").ifEmpty { return "error" }

src.fieldId is "OBJECTID" from the catalog. The USGS response carries
**objectid**, lowercase -- confirmed by curl against the live endpoint. The
lookup returns empty, insertFeature returns "error" before it reaches geometry
or name, and TrailImporter.kt:182 (`else -> errors++`) counts it WITHOUT
LOGGING. That bare else is why the logcat had nothing to show.

The name field is wrong the same way: the catalog says "trailname"; the response
carries "name" (and "maplabel"). That one would have produced unnamed trails
rather than failures, once past :434.

⚠ THIS IS NOT A SYSTEMATIC CASING BUG. Of the eight sources, only
usfs_nfs_trails uses lowercase objectid -- and azsp_trails, blm_gtlf_all and
nps_public_trails all imported successfully today with "OBJECTID". ArcGIS field
casing varies per service. Each right-hand value must match ITS OWN layer, so
this patch touches USGS only.

⭐ THE DURABLE FINDING: nothing validates a field map against what its endpoint
actually returns. A wrong value is a silent 100% failure, and it stayed
invisible until the recap counters landed this morning -- before that the source
reported "0 imported" and read as "no coverage in this state".

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: USGSFIELDS-2026-08-21M
"""

import sys, os, shutil, datetime, json, io

MARKER = "USGSFIELDS-2026-08-21M"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\assets\trail_sources.json"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# Verified against the live endpoint 2026-08-21:
#   https://carto.nationalmap.gov/arcgis/rest/services/transportation/MapServer/11/query
#   ...&outSR=4326&f=geojson  ->  "objectid":1514757, "name":"Metacomet-Monadnock Trail"
FIXES = {
    "id":   ("OBJECTID", "objectid"),
    "name": ("trailname", "name"),
}
# ohvover50inches -> ohvisorunder50inches is ALSO wrong in the catalog, but it
# feeds an extras lookup that degrades to empty rather than failing a row.
# Left alone deliberately: this patch changes only what stops the 100% failure,
# so the device test that follows measures one thing.


def main():
    apply = "--apply" in sys.argv
    target = os.path.join(REPO, REL)

    if not os.path.isfile(target):
        print("ABORT: not found:\n  %s" % target); return 1

    raw = io.open(target, encoding="utf-8").read()
    if MARKER in raw:
        print("Already applied (%s present)." % MARKER); return 0

    try:
        doc = json.loads(raw)
    except Exception as e:
        print("ABORT: not parseable JSON: %s" % e); return 2

    sources = doc if isinstance(doc, list) else doc.get("sources", doc)
    hit = None
    for s in sources:
        if s.get("id") == "usgs_national_trails":
            hit = s; break
    if hit is None:
        print("ABORT: usgs_national_trails not found in the catalog."); return 3

    fields = hit.get("fields", {})
    problems = []
    for key, (expect_old, new) in FIXES.items():
        cur = fields.get(key)
        if cur != expect_old:
            problems.append("  fields.%-5s is %r, expected %r" % (key, cur, expect_old))
        else:
            print("  OK  fields.%-5s %r -> %r" % (key, cur, new))
    if problems:
        print("\nABORT -- the catalog does not look the way this patch expects:")
        print("\n".join(problems))
        print("  Someone has already edited it. No write.")
        return 4

    # ⚠ do NOT touch any other source. Casing is per-service and three uppercase
    # sources imported cleanly today.
    others = [s.get("id") for s in sources
              if s.get("id") != "usgs_national_trails"
              and s.get("fields", {}).get("id") == "OBJECTID"]
    print("\n  --  leaving %d other OBJECTID source(s) alone: %s"
          % (len(others), ", ".join(others)))

    for key, (_old, new) in FIXES.items():
        fields[key] = new

    # a note in the source record itself, so the next reader knows this was measured
    hit["field_note"] = ("%s: id/name corrected against the live endpoint "
                         "(returns lowercase objectid and 'name'). Previously "
                         "failed 100%% of records at TrailImporter:434." % MARKER)

    out = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"

    # sanity: nothing else changed
    before_srcs = len(sources)
    after = json.loads(out)
    after_srcs = len(after if isinstance(after, list) else after.get("sources", after))
    if before_srcs != after_srcs:
        print("\nABORT -- source count changed %d -> %d. No write."
              % (before_srcs, after_srcs)); return 5
    print("  OK  %d sources in, %d out" % (before_srcs, after_srcs))
    print("\nMarker present in output: %s" % (MARKER in out))

    if not apply:
        print("\nDRY RUN -- NOTHING WRITTEN.")
        print("  python %s --apply" % os.path.basename(__file__)); return 0

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = os.path.join(BACKUP_DIR, "trail_sources.json.bak_%s" % stamp)
    shutil.copy2(target, backup)
    io.open(target, "w", encoding="utf-8").write(out)
    print("Backup: %s" % backup)
    print("APPLIED: %s" % target)
    print("\n⚠ THIS IS AN ASSET CHANGE -- it needs a build, and it must be")
    print("  verified INSIDE the artifact, not just in the tree.")
    print("\nTest on Droid 2: Import Trails -> BY STATE -> New Hampshire.")
    print("  USGS should report ADDS, not 391 errors.")
    print("  Anything else means the id was not the whole story.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
