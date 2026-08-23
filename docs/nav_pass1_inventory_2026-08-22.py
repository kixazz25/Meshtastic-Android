#!/usr/bin/env python3
"""
nav_pass1_inventory_2026-08-22.py

PASS 1 of 3 -- what is actually in the Bar 10 -> Toroweap corridor.

Before writing a route finder, find out whether the data can support one. If
the corridor turns out to be a few hundred unnamed fragments, no walker will
produce a describable route and we want to know that now.

This script READS ONLY. It opens the DB read-only and writes nothing.

Run:
    python nav_pass1_inventory_2026-08-22.py

Output is meant to be pasted back whole.
"""

import sqlite3, math, os, sys, json, re
from collections import Counter, defaultdict

DB = r"D:\nav_test\grouptrack_spatial.db"

# ── The corridor ────────────────────────────────────────────────────
# Bar 10 Ranch / airstrip   ~ 36.43 N, -113.35 W
# Toroweap (Tuweep) Overlook ~ 36.20 N, -113.07 W
# Straight-line between them is only ~20 mi, so the box is opened out to
# leave room for 50-70 mi of actual trail between the two.
LAT_S, LAT_N = 35.95, 36.65
LON_W, LON_E = -113.80, -112.80

BAR10 = (36.43, -113.35)
TOROWEAP = (36.20, -113.07)


def hav(a, b):
    """Great-circle miles between (lat,lon) pairs."""
    R = 3958.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1
    dl = math.radians(b[1] - a[1])
    h = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2 * R * math.asin(math.sqrt(h))


def wkt_points(wkt):
    """Coordinate pairs out of a LINESTRING / MULTILINESTRING WKT.
    Returns a list of (lat, lon). WKT is lon lat order."""
    if not wkt:
        return []
    nums = re.findall(r'(-?\d+\.?\d*)\s+(-?\d+\.?\d*)', wkt)
    return [(float(la), float(lo)) for lo, la in nums]


def length_mi(wkt):
    pts = wkt_points(wkt)
    return sum(hav(pts[i], pts[i+1]) for i in range(len(pts)-1)) if len(pts) > 1 else 0.0


def main():
    if not os.path.isfile(DB):
        print("NOT FOUND: %s" % DB); return 1
    print("DB: %s  (%.1f MB)\n" % (DB, os.path.getsize(DB)/1048576))

    con = sqlite3.connect("file:%s?mode=ro" % DB.replace("\\", "/"), uri=True)
    cur = con.cursor()

    # ── schema, discovered not assumed ──────────────────────────────
    tables = [r[0] for r in cur.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
    print("=" * 68)
    print("TABLES (%d)" % len(tables))
    print("=" * 68)
    cols = {}
    for t in tables:
        c = [r[1] for r in cur.execute("PRAGMA table_info(%s)" % t)]
        cols[t] = c
        try:
            n = cur.execute("SELECT COUNT(*) FROM %s" % t).fetchone()[0]
        except Exception:
            n = "?"
        print("  %-24s %8s rows   %s" % (t, n, ", ".join(c[:9]) + ("..." if len(c) > 9 else "")))

    def pick(table, *cands):
        for c in cands:
            if table in cols and c in cols[table]:
                return c
        return None

    # ── trails in the corridor ──────────────────────────────────────
    print("\n" + "=" * 68)
    print("CORRIDOR  lat %.2f..%.2f  lon %.2f..%.2f" % (LAT_S, LAT_N, LON_W, LON_E))
    print("Bar 10 %s   Toroweap %s   straight line %.1f mi"
          % (BAR10, TOROWEAP, hav(BAR10, TOROWEAP)))
    print("=" * 68)

    tt = "trails"
    if tt not in cols:
        print("no `trails` table -- schema differs from expectation, stopping.")
        return 2

    c_id   = pick(tt, "trail_id", "id")
    c_name = pick(tt, "name", "trail_name")
    c_geom = pick(tt, "geometry", "geom", "wkt")
    c_hash = pick(tt, "geom_hash")
    c_minla, c_maxla = pick(tt, "min_lat"), pick(tt, "max_lat")
    c_minlo, c_maxlo = pick(tt, "min_lon"), pick(tt, "max_lon")
    c_carto = pick(tt, "carto_code")
    print("using columns: id=%s name=%s geom=%s bbox=%s/%s/%s/%s carto=%s\n"
          % (c_id, c_name, c_geom, c_minla, c_maxla, c_minlo, c_maxlo, c_carto))

    if not all([c_id, c_geom, c_minla]):
        print("required columns missing, stopping."); return 3

    q = ("SELECT %s,%s,%s FROM %s WHERE %s<=? AND %s>=? AND %s<=? AND %s>=?"
         % (c_id, c_name or "''", c_geom, tt,
            c_minla, c_maxla, c_minlo, c_maxlo))
    rows = list(cur.execute(q, (LAT_N, LAT_S, LON_E, LON_W)))
    print("trails intersecting the corridor: %d" % len(rows))

    named = [r for r in rows if r[1] and str(r[1]).strip()]
    print("  named:   %d  (%.1f%%)" % (len(named), 100.0*len(named)/max(len(rows), 1)))
    print("  unnamed: %d" % (len(rows) - len(named)))

    lens, total = [], 0.0
    endpoints = []
    for tid, nm, wkt in rows:
        L = length_mi(wkt)
        lens.append(L); total += L
        pts = wkt_points(wkt)
        if len(pts) >= 2:
            endpoints.append((tid, nm, pts[0], pts[-1], L))
    lens.sort()
    if lens:
        print("\n  total trail length in box: %.0f mi" % total)
        print("  segment length  min %.2f  median %.2f  mean %.2f  max %.2f mi"
              % (lens[0], lens[len(lens)//2], total/len(lens), lens[-1]))
        print("  segments over 1 mi: %d   over 5 mi: %d"
              % (sum(1 for x in lens if x > 1), sum(1 for x in lens if x > 5)))

    # names actually available -- the thing that decides whether a route is describable
    print("\n  most common trail names in the corridor:")
    for nm, n in Counter(str(r[1]).strip() for r in named).most_common(15):
        print("    %-46s %d segment(s)" % (nm[:46], n))

    # ── properties: surface / difficulty / motorized ────────────────
    pt = "trail_properties"
    if pt in cols:
        print("\n  trail_properties columns: %s" % ", ".join(cols[pt]))
        ids = tuple(r[0] for r in rows)
        if ids:
            CH = 400
            got = Counter(); srcs = Counter()
            for i in range(0, len(ids), CH):
                chunk = ids[i:i+CH]
                ph = ",".join("?"*len(chunk))
                sel = "SELECT source_id, carto_code, motorized_allowed, surface_type FROM %s WHERE trail_id IN (%s)" % (pt, ph)
                try:
                    for sid, carto, motor, surf in cur.execute(sel, chunk):
                        srcs[sid] += 1
                        if carto and str(carto).strip(): got["carto_code"] += 1
                        if motor and str(motor).strip(): got["motorized_allowed"] += 1
                        if surf and str(surf).strip():   got["surface_type"] += 1
                except Exception as e:
                    print("    (property query failed: %s)" % e); break
            print("\n  by source:")
            for s, n in srcs.most_common():
                print("    %-28s %d" % (s, n))
            print("\n  populated attributes:")
            for k, n in got.most_common():
                print("    %-22s %d of %d  (%.0f%%)" % (k, n, len(ids), 100.0*n/len(ids)))

    # ── points of interest ──────────────────────────────────────────
    for tbl, label in (("reference_points", "reference points (OSM peaks/springs/places)"),
                       ("waypoints", "waypoints (user + trailheads)")):
        if tbl not in cols:
            continue
        la = pick(tbl, "lat", "latitude", "min_lat")
        lo = pick(tbl, "lon", "longitude", "min_lon")
        nm = pick(tbl, "name")
        fc = pick(tbl, "fclass", "type", "waypoint_type")
        if not (la and lo):
            print("\n  %s: no usable lat/lon columns (%s)" % (label, cols[tbl])); continue
        sel = "SELECT %s,%s,%s FROM %s WHERE %s BETWEEN ? AND ? AND %s BETWEEN ? AND ?" % (
            nm or "''", fc or "''", la, tbl, la, lo)
        try:
            prows = list(cur.execute(sel, (LAT_S, LAT_N, LON_W, LON_E)))
        except Exception as e:
            print("\n  %s: query failed %s" % (label, e)); continue
        print("\n  %s in the corridor: %d" % (label, len(prows)))
        kinds = Counter(str(r[1]) for r in prows if r[1])
        for k, n in kinds.most_common(12):
            print("    %-24s %d" % (k[:24], n))
        named_p = [r[0] for r in prows if r[0] and str(r[0]).strip()]
        print("    named: %d" % len(named_p))
        for s in named_p[:20]:
            print("      %s" % s)

    # ── how close is the data to Bar 10 and Toroweap? ───────────────
    print("\n" + "=" * 68)
    print("ANCHORS -- is there trail data at each end?")
    print("=" * 68)
    for label, pt_ in (("Bar 10", BAR10), ("Toroweap", TOROWEAP)):
        near = []
        for tid, nm, a, b, L in endpoints:
            d = min(hav(pt_, a), hav(pt_, b))
            if d < 5.0:
                near.append((d, nm or "(unnamed)", L))
        near.sort()
        print("\n%s -- %d trail ends within 5 mi" % (label, len(near)))
        for d, nm, L in near[:12]:
            print("   %5.2f mi  %-42s seg %.1f mi" % (d, nm[:42], L))

    con.close()
    print("\nDONE -- paste this whole output back.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
