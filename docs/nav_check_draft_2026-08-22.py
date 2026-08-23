#!/usr/bin/env python3
"""
nav_check_draft_2026-08-22.py

Read the emitted draft and find out whether the straight lines over empty
ground are a stitching error in my geometry or something on the planner side.

Fred saw: "straight lines crossing wide areas with zero trails" and
"lots of criss-crossing".

MY SUSPICION, to be confirmed or killed by this: when consecutive edges in the
Dijkstra path are stitched, I flip each segment to match the previous endpoint.
If two consecutive edges do not actually share a vertex -- because the path was
found in the SNAPPED graph while the emitted geometry is RAW -- the vertex list
jumps, and the planner draws a straight line across the jump.

If the largest gaps are miles, it is my stitching. If they are all under a few
hundred feet, the cause is elsewhere and I look at the planner.
"""

import json, math, os, sys

FILES = [r"D:\nav_test\out\route_drafts\Toroweap 1.json",
         r"D:\nav_test\out\route_drafts\Toroweap 2.json"]


def ft(a, b):
    R = 20902231.0          # earth radius in feet
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1
    dl = math.radians(b[1] - a[1])
    x = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2 * R * math.asin(math.sqrt(x))


for path in FILES:
    if not os.path.isfile(path):
        print("missing: %s" % path); continue
    d = json.load(open(path, encoding="utf-8"))
    v = d.get("vertices", [])
    print("=" * 70)
    print(os.path.basename(path))
    print("=" * 70)
    print("  name        : %s" % d.get("name"))
    print("  schema      : %s   method: %s" % (d.get("schemaVersion"), d.get("method")))
    print("  vertices    : %d" % len(v))
    if len(v) < 2:
        continue

    gaps = []
    for i in range(len(v) - 1):
        g = ft((v[i]["lat"], v[i]["lon"]), (v[i+1]["lat"], v[i+1]["lon"]))
        gaps.append((g, i))
    total = sum(g for g, _ in gaps)
    gaps.sort(reverse=True)

    print("  path length : %.1f mi   (%.0f ft)" % (total/5280.0, total))
    print("  median step : %.1f ft" % sorted(g for g, _ in gaps)[len(gaps)//2])
    over = lambda n: sum(1 for g, _ in gaps if g > n)
    print("  steps over  200ft: %d   500ft: %d   0.25mi: %d   1mi: %d"
          % (over(200), over(500), over(1320), over(5280)))

    jump_ft = sum(g for g, _ in gaps if g > 500)
    print("  miles inside jumps over 500 ft: %.1f  (%.0f%% of the route)"
          % (jump_ft/5280.0, 100.0*jump_ft/total))

    print("\n  largest steps:")
    for g, i in gaps[:15]:
        a, b = v[i], v[i+1]
        same = "same trail" if a.get("lineId") == b.get("lineId") else "TRAIL CHANGE"
        print("    %9.0f ft  idx %5d  %.5f,%.5f -> %.5f,%.5f  %s"
              % (g, i, a["lat"], a["lon"], b["lat"], b["lon"], same))

    # how many distinct trails, and how often it switches
    switches = sum(1 for i in range(len(v)-1)
                   if v[i].get("lineId") != v[i+1].get("lineId"))
    trails = len({x.get("lineId") for x in v})
    print("\n  distinct lineIds: %d   trail switches: %d" % (trails, switches))

    # are the big steps AT trail switches? that is the stitching signature
    bigsw = sum(1 for g, i in gaps if g > 500 and
                v[i].get("lineId") != v[i+1].get("lineId"))
    bigsame = sum(1 for g, i in gaps if g > 500 and
                  v[i].get("lineId") == v[i+1].get("lineId"))
    print("  steps over 500ft AT a trail switch : %d" % bigsw)
    print("  steps over 500ft WITHIN one trail  : %d" % bigsame)
    print("\n  READ: big steps at trail switches = my stitching (edges that do")
    print("  not share a vertex). Big steps within one trail = the source")
    print("  geometry itself is sparse there, which is the data, not the code.")
    print()
