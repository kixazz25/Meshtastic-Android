#!/usr/bin/env python3
"""
nav_pass2b_graph_2026-08-22.py

PASS 2b -- rebuild the network properly. SUPERSEDES pass 2.

⛔ WHY PASS 2 WAS WRONG (my bug, not the data):
Pass 2 built junctions from segment ENDPOINTS only. Where one trail crosses or
T-joins another part-way along, the intersection is an INTERIOR vertex of both
and no junction was ever made. OSM usually splits ways at intersections; BLM and
USFS data frequently do not -- and this corridor is heavily BLM.

The symptom was unmistakable once read properly: 408 components, and 1,628 of
3,683 nodes were dead ends (44%). That is not a trail network, that is a graph
built wrong.

It also inverted the conclusion. Toroweap sits in component #0 -- the BIG one,
2,611 nodes. Bar 10 was the isolated island (#61). The end I had been treating
as unreachable was the well-connected one.

FIX: every vertex shared by two or more trails becomes a junction, and edges are
SPLIT there. A trail crossing three others becomes four edges.

⭐ ALSO GENERALISED, per Fred 08-22: the route is a SEQUENCE OF FIXED POINTS and
the search fills the gaps. One point = out and back. Two = today's test. Three+ =
an overnighter, where each leg carries its own mileage band. Set POINTS below.

READ ONLY on the DB. Writes the graph for pass 3.
"""

import sqlite3, math, os, sys, re, json
from collections import defaultdict, Counter, deque

DB  = r"D:\nav_test\grouptrack_spatial.db"
OUT = r"D:\nav_test\corridor_graph.json"

# ── THE RIDE ────────────────────────────────────────────────────────
# A list of fixed points. Consecutive pairs are legs; the search meanders
# between them. Repeat the first point at the end for a round trip.
POINTS = [
    ("Bar 10",   36.43, -113.35),
    ("Toroweap", 36.20, -113.07),
    ("Bar 10",   36.43, -113.35),
]
LEG_MILES = (50, 70)      # per leg... for a single-leg round trip this is the whole ride

LAT_S, LAT_N = 35.95, 36.65
LON_W, LON_E = -113.80, -112.80

SNAP_FT    = 150.0        # two vertices this close are the same junction
POI_BUF_MI = 0.5
NONAMES = {"", "not named", "unnamed", "none", "null", "n/a", "-"}


def hav(a, b):
    R = 3958.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1
    dl = math.radians(b[1] - a[1])
    h = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2 * R * math.asin(math.sqrt(h))


def wkt_points(wkt):
    if not wkt:
        return []
    nums = re.findall(r'(-?\d+\.?\d*)\s+(-?\d+\.?\d*)', wkt)
    return [(float(la), float(lo)) for lo, la in nums]


def real_name(n):
    return bool(n) and str(n).strip().lower() not in NONAMES


def main():
    if not os.path.isfile(DB):
        print("NOT FOUND: %s" % DB); return 1
    con = sqlite3.connect("file:%s?mode=ro" % DB.replace("\\", "/"), uri=True)
    cur = con.cursor()

    print("=" * 70)
    print("PASS 2b -- NETWORK REBUILT WITH INTERIOR JUNCTIONS")
    print("=" * 70)

    rows = list(cur.execute(
        "SELECT trail_id,name,geometry FROM trails "
        "WHERE min_lat<=? AND max_lat>=? AND min_lon<=? AND max_lon>=?",
        (LAT_N, LAT_S, LON_E, LON_W)))
    print("trails in corridor: %d" % len(rows))

    snap_deg = (SNAP_FT / 5280.0) / 69.0
    def key(p):
        return (int(round(p[0] / snap_deg)), int(round(p[1] / snap_deg)))

    # ── pass A: count how many DISTINCT trails touch each snapped vertex
    touch = defaultdict(set)
    geoms = []
    for tid, nm, wkt in rows:
        pts = wkt_points(wkt)
        if len(pts) < 2:
            continue
        geoms.append((tid, nm, pts))
        for p in pts:
            touch[key(p)].add(tid)

    shared = {k for k, s in touch.items() if len(s) > 1}
    print("distinct snapped vertices: %d" % len(touch))
    print("  shared by 2+ trails (real junctions): %d" % len(shared))

    # ── pass B: split every trail at its endpoints AND any shared vertex
    edges = []            # (u, v, miles, name, trail_id, pts)
    nodes = {}
    for tid, nm, pts in geoms:
        cutpoints = [0, len(pts) - 1]
        for i in range(1, len(pts) - 1):
            if key(pts[i]) in shared:
                cutpoints.append(i)
        cutpoints = sorted(set(cutpoints))
        for a, b in zip(cutpoints, cutpoints[1:]):
            seg = pts[a:b+1]
            if len(seg) < 2:
                continue
            L = sum(hav(seg[i], seg[i+1]) for i in range(len(seg)-1))
            if L <= 0.0005:
                continue
            ka, kb = key(seg[0]), key(seg[-1])
            if ka == kb:
                continue
            nodes.setdefault(ka, seg[0]); nodes.setdefault(kb, seg[-1])
            edges.append((ka, kb, L, (nm if real_name(nm) else None), tid, seg))

    print("\nedges after splitting: %d   junction nodes: %d" % (len(edges), len(nodes)))

    adj = defaultdict(list)
    for i, (a, b, L, nm, tid, seg) in enumerate(edges):
        adj[a].append((b, i)); adj[b].append((a, i))

    deg = Counter(len(v) for v in adj.values())
    dead = deg.get(1, 0)
    print("node degree: %s" % dict(sorted(deg.items())[:9]))
    print("  dead ends: %d of %d  (%.0f%%)" % (dead, len(adj), 100.0*dead/max(len(adj),1)))
    print("  (pass 2 had 1628 of 3683 = 44%. Lower is a real network.)")

    # ── components ──────────────────────────────────────────────────
    seen, comps = set(), []
    for n in adj:
        if n in seen:
            continue
        q, comp = deque([n]), []
        seen.add(n)
        while q:
            x = q.popleft(); comp.append(x)
            for y, _ in adj[x]:
                if y not in seen:
                    seen.add(y); q.append(y)
        comps.append(comp)
    comps.sort(key=len, reverse=True)
    print("\ncomponents: %d   (pass 2 had 408)" % len(comps))
    for c in comps[:5]:
        print("   %d nodes  (%.0f%% of network)" % (len(c), 100.0*len(c)/len(adj)))

    cid = {}
    for i, c in enumerate(comps):
        for n in c:
            cid[n] = i

    def nearest_node(pt, want_comp=None):
        best, bd = None, 1e9
        for k, p in nodes.items():
            if want_comp is not None and cid.get(k) != want_comp:
                continue
            d = hav(pt, p)
            if d < bd:
                bd, best = d, k
        return best, bd

    # ── the ride's fixed points ─────────────────────────────────────
    print("\n" + "=" * 70)
    print("RIDE POINTS -- %d point(s), %d leg(s), %d-%d mi per leg"
          % (len(POINTS), max(len(POINTS)-1, 1), LEG_MILES[0], LEG_MILES[1]))
    print("=" * 70)
    anchors = []
    for nm, la, lo in POINTS:
        n, d = nearest_node((la, lo))
        anchors.append({"name": nm, "lat": la, "lon": lo,
                        "node": "%d,%d" % n, "dist_mi": round(d, 3),
                        "component": cid.get(n)})
        print("  %-10s nearest junction %5.2f mi   component #%s (%d nodes)"
              % (nm, d, cid.get(n), len(comps[cid.get(n)]) if cid.get(n) is not None else 0))

    # also report each anchor's nearest node WITHIN the largest component,
    # because that is what actually matters for routing
    print("\n  within the LARGEST component:")
    ok = True
    for nm, la, lo in POINTS:
        n, d = nearest_node((la, lo), want_comp=0)
        print("    %-10s %5.2f mi away" % (nm, d))
        if d > 5.0:
            ok = False
    print("\n  all points reachable in one network: %s" % ok)

    if len(set(a["component"] for a in anchors)) > 1:
        print("  ⚠ points sit in different components -- see the distances above;")
        print("    a point a short hop from the main network is workable,")
        print("    a point genuinely isolated is not.")

    # slack
    import heapq
    def sp(u, v):
        dist = {u: 0.0}; pq = [(0.0, u)]
        while pq:
            d, x = heapq.heappop(pq)
            if x == v:
                return d
            if d > dist.get(x, 1e18):
                continue
            for y, ei in adj[x]:
                nd = d + edges[ei][2]
                if nd < dist.get(y, 1e18):
                    dist[y] = nd; heapq.heappush(pq, (nd, y))
        return None

    print("\n  LEG DISTANCES (shortest trail path -- the meander must exceed these)")
    total_min = 0.0
    for i in range(len(POINTS)-1):
        a = nearest_node((POINTS[i][1], POINTS[i][2]), want_comp=0)[0]
        b = nearest_node((POINTS[i+1][1], POINTS[i+1][2]), want_comp=0)[0]
        d = sp(a, b)
        if d is None:
            print("    %s -> %s : NO PATH" % (POINTS[i][0], POINTS[i+1][0]))
        else:
            total_min += d
            print("    %-10s -> %-10s %6.1f mi   slack in band: %.1f to %.1f mi"
                  % (POINTS[i][0], POINTS[i+1][0], d,
                     LEG_MILES[0]-d, LEG_MILES[1]-d))
    print("    %-24s %6.1f mi total minimum" % ("", total_min))

    # ── POIs ────────────────────────────────────────────────────────
    pois = list(cur.execute(
        "SELECT name,fclass,lat,lon FROM reference_points "
        "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
        (LAT_S, LAT_N, LON_W, LON_E)))
    grid = defaultdict(list)
    G = 0.01
    for i, (a, b, L, nm, tid, seg) in enumerate(edges):
        for p in seg:
            grid[(int(p[0]/G), int(p[1]/G))].append((i, p))

    hooked = []
    for nm, fc, la, lo in pois:
        gx, gy = int(la/G), int(lo/G)
        best, bd = None, 1e9
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for ei, p in grid.get((gx+dx, gy+dy), ()):
                    d = hav((la, lo), p)
                    if d < bd:
                        bd, best = d, ei
        if best is not None and bd <= POI_BUF_MI:
            hooked.append({"name": nm, "fclass": fc, "lat": la, "lon": lo,
                           "edge": best, "dist_mi": round(bd, 3),
                           "component": cid.get(edges[best][0])})
    print("\nPOIs: %d in corridor, %d within %.1f mi of a trail"
          % (len(pois), len(hooked), POI_BUF_MI))
    main_pois = [h for h in hooked if h["component"] == 0]
    print("  on the MAIN network: %d" % len(main_pois))
    for k, n in Counter(h["fclass"] for h in main_pois).most_common():
        print("    %-12s %d" % (k, n))

    json.dump({
        "points": anchors,
        "leg_miles": list(LEG_MILES),
        "nodes": {"%d,%d" % k: list(v) for k, v in nodes.items()},
        "edges": [{"u": "%d,%d" % a, "v": "%d,%d" % b, "mi": round(L, 4),
                   "name": nm, "trail_id": tid,
                   "pts": [[round(p[0],6), round(p[1],6)] for p in seg]}
                  for a, b, L, nm, tid, seg in edges],
        "pois": hooked,
    }, open(OUT, "w"))
    print("\ngraph written: %s  (%.1f MB)" % (OUT, os.path.getsize(OUT)/1048576))
    con.close()
    print("\nDONE -- paste the output back.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
