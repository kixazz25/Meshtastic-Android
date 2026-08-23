#!/usr/bin/env python3
"""
nav_compare_after_2026-08-22.py

The planner rewrote the draft: 662,973 -> 596,024 bytes. Find out what it did.

Three things worth knowing:
  1. Did it DROP vertices, or change their fields?
  2. Did it MOVE any -- and how far? That is snap-2 pulling points onto trails.
  3. Did lineId survive, or did it re-snap to a different trail?

The third matters most. If our vertices were re-snapped to a NEIGHBOURING track
rather than the one we chose, the route on screen is not the route we computed,
and that would explain lines wandering across ground with no trail on it.
"""

import json, math, os
from collections import Counter

BEFORE = r"D:\nav_test\out\route_drafts\Toroweap 1.json"
AFTER  = r"D:\nav_test\after\Toroweap 1.json"


def ft(a, b):
    R = 20902231.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1
    dl = math.radians(b[1] - a[1])
    x = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2 * R * math.asin(math.sqrt(x))


for p in (BEFORE, AFTER):
    if not os.path.isfile(p):
        print("missing: %s" % p); raise SystemExit(1)

b = json.load(open(BEFORE, encoding="utf-8"))
a = json.load(open(AFTER, encoding="utf-8"))
vb, va = b["vertices"], a["vertices"]

print("=" * 72)
print("BEFORE (written by the research script) vs AFTER (rewritten by the app)")
print("=" * 72)
print("  vertices      %6d  ->  %6d   (%+d)" % (len(vb), len(va), len(va)-len(vb)))
for k in ("schemaVersion", "name", "method", "createdAt", "updatedAt"):
    print("  %-13s %-24s -> %s" % (k, b.get(k), a.get(k)))

# field-level: what keys does each vertex carry now?
kb = Counter(tuple(sorted(x.keys())) for x in vb)
ka = Counter(tuple(sorted(x.keys())) for x in va)
print("\n  vertex keys BEFORE: %s" % (list(kb)[0],))
print("  vertex keys AFTER : %s" % (list(ka)[0],))

for fld in ("snapped", "lineType"):
    print("  %-9s before: %s" % (fld, Counter(x.get(fld) for x in vb).most_common()))
    print("  %-9s after : %s" % (fld, Counter(x.get(fld) for x in va).most_common()))

nb = len({x.get("lineId") for x in vb})
na = len({x.get("lineId") for x in va})
print("\n  distinct lineIds  %d -> %d" % (nb, na))
print("  lineId null       %d -> %d"
      % (sum(1 for x in vb if not x.get("lineId")),
         sum(1 for x in va if not x.get("lineId"))))

# geometry: length and the biggest steps, same measure as before
def stats(v, label):
    g = [ft((v[i]["lat"], v[i]["lon"]), (v[i+1]["lat"], v[i+1]["lon"]))
         for i in range(len(v)-1)]
    tot = sum(g)
    gs = sorted(g)
    print("\n  %s" % label)
    print("    length      %.1f mi" % (tot/5280.0))
    print("    median step %.1f ft   max %.0f ft" % (gs[len(gs)//2], gs[-1]))
    print("    steps >500ft %d   >0.25mi %d   >1mi %d"
          % (sum(1 for x in g if x > 500),
             sum(1 for x in g if x > 1320),
             sum(1 for x in g if x > 5280)))
    return tot

tb = stats(vb, "BEFORE")
ta = stats(va, "AFTER")
print("\n  length change: %+.1f mi (%+.0f%%)"
      % ((ta-tb)/5280.0, 100.0*(ta-tb)/tb))

# if the counts match, measure how far each vertex moved
if len(vb) == len(va):
    moved = [ft((vb[i]["lat"], vb[i]["lon"]), (va[i]["lat"], va[i]["lon"]))
             for i in range(len(vb))]
    ms = sorted(moved)
    print("\n  VERTEX MOVEMENT (counts match, so index-for-index)")
    print("    unmoved (<1ft) %d of %d" % (sum(1 for x in moved if x < 1), len(moved)))
    print("    median %.1f ft   90th %.1f ft   max %.0f ft"
          % (ms[len(ms)//2], ms[int(len(ms)*0.9)], ms[-1]))
    print("    moved >25ft %d   >100ft %d   >500ft %d"
          % (sum(1 for x in moved if x > 25),
             sum(1 for x in moved if x > 100),
             sum(1 for x in moved if x > 500)))
    ch = sum(1 for i in range(len(vb))
             if vb[i].get("lineId") != va[i].get("lineId"))
    print("    lineId CHANGED on %d vertices (%.0f%%)"
          % (ch, 100.0*ch/len(vb)))
    print("\n    farthest moves:")
    idx = sorted(range(len(moved)), key=lambda i: -moved[i])[:10]
    for i in idx:
        print("      %7.0f ft  idx %5d  %.5f,%.5f -> %.5f,%.5f  %s"
              % (moved[i], i, vb[i]["lat"], vb[i]["lon"],
                 va[i]["lat"], va[i]["lon"],
                 "lineId changed" if vb[i].get("lineId") != va[i].get("lineId")
                 else "same lineId"))
else:
    print("\n  vertex counts differ -- the app dropped or added points, so an")
    print("  index-for-index comparison would be misleading. The length and step")
    print("  figures above are the honest comparison.")
