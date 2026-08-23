#!/usr/bin/env python3
"""
nav_pass2_graph_2026-08-22.py

PASS 2 of 3 -- the network, and whether the circuit is possible at all.

THE PROBLEM, as Fred framed it 2026-08-22:
    "our objective is not the fastest way to reach toroweap but rather to
     identify poi along the way and build a meandering route through the pois
     that meets the mileage time constraint"

So this is NOT shortest-path. The POIs are the objective; Bar 10 and Toroweap
are constraints. Bar 10 -> Toroweap -> Bar 10, round trip, 50-70 miles total.
Straight line between the anchors is 22.3 mi, so a direct there-and-back is
~45 mi -- the band leaves 5-25 mi of slack to meander through POIs.

Two supplied points is the LOOSEST case: the whole corridor is available. More
must-stops would constrain the shape and leave less to solve.

This pass builds the substrate and reports on it. It does NOT pick routes --
pass 3 does that, once we know the network supports it.

READ ONLY. Writes one JSON graph file for pass 3 to consume.

    python nav_pass2_graph_2026-08-22.py
"""

import sqlite3, math, os, sys, re, json
from collections import defaultdict, Counter, deque

DB  = r"D:\nav_test\grouptrack_spatial.db"
OUT = r"D:\nav_test\corridor_graph.json"

BAR10    = (36.43, -113.35)
TOROWEAP = (36.20, -113.07)

LAT_S, LAT_N = 35.95, 36.65
LON_W, LON_E = -113.80, -112.80

# Two trail ends within this distance are treated as the same junction.
# OSM ways that meet at an intersection usually share an exact vertex, but
# imports from different agencies land a few metres apart.
SNAP_FT   = 150.0
POI_BUF_MI = 0.5          # the scoring radius from the 08-17 design record
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
    print("PASS 2 -- NETWORK IN THE BAR 10 / TOROWEAP CORRIDOR")
    print("=" * 70)

    rows = list(cur.execute(
        "SELECT trail_id,name,geometry FROM trails "
        "WHERE min_lat<=? AND max_lat>=? AND min_lon<=? AND max_lon>=?",
        (LAT_N, LAT_S, LON_E, LON_W)))
    print("trails in corridor: %d" % len(rows))

    # ── edges ───────────────────────────────────────────────────────
    snap_deg = (SNAP_FT / 5280.0) / 69.0
    def key(p):
        return (round(p[0] / snap_deg), round(p[1] / snap_deg))

    edges = []            # (u, v, miles, name, trail_id, pts)
    nodes = {}            # key -> (lat, lon)
    skipped = 0
    for tid, nm, wkt in rows:
        pts = wkt_points(wkt)
        if len(pts) < 2:
            skipped += 1; continue
        L = sum(hav(pts[i], pts[i+1]) for i in range(len(pts)-1))
        if L <= 0:
            skipped += 1; continue
        a, b = key(pts[0]), key(pts[-1])
        if a == b:
            skipped += 1; continue          # a closed loop segment, no through travel
        nodes.setdefault(a, pts[0]); nodes.setdefault(b, pts[-1])
        edges.append((a, b, L, (nm if real_name(nm) else None), tid, pts))

    print("edges built: %d   junction nodes: %d   skipped: %d"
          % (len(edges), len(nodes), skipped))

    adj = defaultdict(list)
    for i, (a, b, L, nm, tid, pts) in enumerate(edges):
        adj[a].append((b, i)); adj[b].append((a, i))

    deg = Counter(len(v) for v in adj.values())
    print("node degree: %s" % dict(sorted(deg.items())[:8]))
    print("  dead ends (degree 1): %d" % deg.get(1, 0))

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
    print("\nconnected components: %d" % len(comps))
    for c in comps[:5]:
        print("   %d nodes" % len(c))

    def nearest_node(pt):
        best, bd = None, 1e9
        for k, p in nodes.items():
            d = hav(pt, p)
            if d < bd:
                bd, best = d, k
        return best, bd

    n_bar, d_bar = nearest_node(BAR10)
    n_tor, d_tor = nearest_node(TOROWEAP)
    print("\nANCHORS")
    print("  Bar 10   nearest junction %.2f mi away" % d_bar)
    print("  Toroweap nearest junction %.2f mi away" % d_tor)

    cid = {}
    for i, c in enumerate(comps):
        for n in c:
            cid[n] = i
    same = cid.get(n_bar) == cid.get(n_tor)
    print("  same component: %s   (Bar10=#%s  Toroweap=#%s)"
          % (same, cid.get(n_bar), cid.get(n_tor)))

    if not same:
        print("\n  ⛔ The anchors are NOT connected in this data. A circuit cannot")
        print("     be built over known trails. Pass 3 would return nothing.")
        print("     Likely cause: the Toroweap approach classifies as `tertiary`,")
        print("     which the OSM trails filter deliberately excludes.")

    # shortest path, purely to size the slack the meander has to play with
    if same:
        import heapq
        dist = {n_bar: 0.0}; prev = {}
        pq = [(0.0, n_bar)]
        while pq:
            d, x = heapq.heappop(pq)
            if x == n_tor:
                break
            if d > dist.get(x, 1e18):
                continue
            for y, ei in adj[x]:
                nd = d + edges[ei][2]
                if nd < dist.get(y, 1e18):
                    dist[y] = nd; prev[y] = (x, ei); heapq.heappush(pq, (nd, y))
        sp = dist.get(n_tor)
        if sp:
            print("\n  shortest trail path Bar 10 -> Toroweap: %.1f mi" % sp)
            print("  round trip on that path:                 %.1f mi" % (2*sp))
            print("  slack inside a 50-70 mi budget:          %.1f to %.1f mi"
                  % (50 - 2*sp, 70 - 2*sp))
            if 2*sp > 70:
                print("  ⚠ the direct round trip ALREADY exceeds the budget")

    # ── POIs hooked to the network ──────────────────────────────────
    pois = list(cur.execute(
        "SELECT name,fclass,lat,lon FROM reference_points "
        "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
        (LAT_S, LAT_N, LON_W, LON_E)))
    print("\nPOIs in corridor: %d" % len(pois))

    # index vertices so the hook search is not O(poi * every vertex)
    grid = defaultdict(list)
    G = 0.01
    for i, (a, b, L, nm, tid, pts) in enumerate(edges):
        for p in pts:
            grid[(int(p[0]/G), int(p[1]/G))].append((i, p))

    hooked, orphan = [], 0
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
                           "edge": best, "dist_mi": round(bd, 3)})
        else:
            orphan += 1
    print("  reachable (within %.1f mi of a trail): %d" % (POI_BUF_MI, len(hooked)))
    print("  orphaned (no trail near):              %d" % orphan)
    kinds = Counter(h["fclass"] for h in hooked)
    for k, n in kinds.most_common():
        print("    %-12s %d" % (k, n))

    print("\n  reachable POIs, closest first:")
    for h in sorted(hooked, key=lambda x: x["dist_mi"])[:25]:
        print("    %5.2f mi  %-12s %s" % (h["dist_mi"], h["fclass"], h["name"]))

    # ── hand pass 3 a graph it can walk ─────────────────────────────
    graph = {
        "anchors": {"bar10": list(BAR10), "toroweap": list(TOROWEAP),
                    "bar10_node": list(n_bar), "toroweap_node": list(n_tor),
                    "connected": bool(same)},
        "nodes": {"%d,%d" % k: list(v) for k, v in nodes.items()},
        "edges": [{"u": "%d,%d" % a, "v": "%d,%d" % b, "mi": round(L, 4),
                   "name": nm, "trail_id": tid} for a, b, L, nm, tid, _ in edges],
        "pois": hooked,
    }
    json.dump(graph, open(OUT, "w"))
    print("\ngraph written: %s  (%.1f MB)" % (OUT, os.path.getsize(OUT)/1048576))
    con.close()
    print("\nDONE -- paste the output back.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
