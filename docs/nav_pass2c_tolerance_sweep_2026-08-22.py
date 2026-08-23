#!/usr/bin/env python3
"""
nav_pass2c_tolerance_sweep_2026-08-22.py

HOW MUCH OF THE NETWORK IS REAL?

Pass 2b snapped vertices within 150 ft into shared junctions and produced a
network that LOOKS healthy -- 3% dead ends, 38 components, 99% in one piece.
But 22,243 of 26,237 nodes came out at degree 4, and that is not what a trail
network looks like. Real networks are mostly degree 1, 2 and 3, with degree 4
as genuine crossroads.

Fred, 08-22: "we really need to be sure trails have a junction before suggesting
a route." A route that turns where no turn exists is worse than no route -- the
rider is out past Mt Trumbull with no signal and the app told them to go that
way.

So: build the graph at several tolerances and MEASURE, rather than pick one.

WHAT TO READ IN THE OUTPUT
  1. Where the component count stops falling. Junctions gained between 0 and
     ~25 ft are float noise being repaired and are real. Junctions still being
     gained at 150 ft are parallel tracks being welded together.
  2. Where degree-4 starts to dominate. That is the tolerance at which trails
     that merely pass near each other become crossings.
  3. The SAMPLES at the bottom -- actual pairs of trail names being joined, with
     the real distance between the vertices. That is the eyeball test.

READ ONLY. Writes nothing.
"""

import sqlite3, math, os, sys, re
from collections import defaultdict, Counter, deque

DB = r"D:\nav_test\grouptrack_spatial.db"

BAR10    = (36.43, -113.35)
TOROWEAP = (36.20, -113.07)
LAT_S, LAT_N = 35.95, 36.65
LON_W, LON_E = -113.80, -112.80

TOLERANCES_FT = [0, 10, 25, 50, 100, 150]
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


def build(geoms, tol_ft):
    """Returns (adj, nodes, edges, junction_keys, keyfn)."""
    if tol_ft <= 0:
        # exact match on the stored coordinate, to ~1e-6 deg (about 4 inches)
        def keyfn(p):
            return (round(p[0], 6), round(p[1], 6))
    else:
        d = (tol_ft / 5280.0) / 69.0
        def keyfn(p):
            return (int(round(p[0] / d)), int(round(p[1] / d)))

    touch = defaultdict(set)
    for tid, nm, pts in geoms:
        for p in pts:
            touch[keyfn(p)].add(tid)
    shared = {k for k, s in touch.items() if len(s) > 1}

    edges, nodes = [], {}
    for tid, nm, pts in geoms:
        cuts = [0, len(pts)-1]
        for i in range(1, len(pts)-1):
            if keyfn(pts[i]) in shared:
                cuts.append(i)
        cuts = sorted(set(cuts))
        for a, b in zip(cuts, cuts[1:]):
            seg = pts[a:b+1]
            if len(seg) < 2:
                continue
            L = sum(hav(seg[i], seg[i+1]) for i in range(len(seg)-1))
            if L <= 0.0005:
                continue
            ka, kb = keyfn(seg[0]), keyfn(seg[-1])
            if ka == kb:
                continue
            nodes.setdefault(ka, seg[0]); nodes.setdefault(kb, seg[-1])
            edges.append((ka, kb, L, nm, tid))
    adj = defaultdict(list)
    for i, (a, b, L, nm, tid) in enumerate(edges):
        adj[a].append((b, i)); adj[b].append((a, i))
    return adj, nodes, edges, shared, keyfn, touch


def components(adj):
    seen, comps = set(), []
    for n in adj:
        if n in seen:
            continue
        q, c = deque([n]), []
        seen.add(n)
        while q:
            x = q.popleft(); c.append(x)
            for y, _ in adj[x]:
                if y not in seen:
                    seen.add(y); q.append(y)
        comps.append(c)
    comps.sort(key=len, reverse=True)
    return comps


def main():
    if not os.path.isfile(DB):
        print("NOT FOUND: %s" % DB); return 1
    con = sqlite3.connect("file:%s?mode=ro" % DB.replace("\\", "/"), uri=True)
    cur = con.cursor()

    rows = list(cur.execute(
        "SELECT trail_id,name,geometry FROM trails "
        "WHERE min_lat<=? AND max_lat>=? AND min_lon<=? AND max_lon>=?",
        (LAT_N, LAT_S, LON_E, LON_W)))
    geoms = []
    for tid, nm, wkt in rows:
        pts = wkt_points(wkt)
        if len(pts) >= 2:
            geoms.append((tid, nm, pts))
    print("trails in corridor: %d   (usable geometry: %d)" % (len(rows), len(geoms)))
    name_of = {tid: (nm if real_name(nm) else "(unnamed)") for tid, nm, _ in geoms}

    print("\n" + "=" * 78)
    print("%-7s %8s %8s %7s %8s %8s %7s %9s" %
          ("tol ft", "junct", "edges", "nodes", "comps", "deadend", "deg4%", "B10->TOR"))
    print("=" * 78)

    prev_shared, prev_touch, prev_keyfn = None, None, None
    results = {}
    for tol in TOLERANCES_FT:
        adj, nodes, edges, shared, keyfn, touch = build(geoms, tol)
        comps = components(adj)
        deg = Counter(len(v) for v in adj.values())
        n = max(len(adj), 1)
        dead = deg.get(1, 0)
        d4 = deg.get(4, 0)

        # anchors + shortest path in the biggest component
        cid = {}
        for i, c in enumerate(comps):
            for x in c:
                cid[x] = i
        def nearest(pt, comp=0):
            best, bd = None, 1e9
            for k, p in nodes.items():
                if cid.get(k) != comp:
                    continue
                d = hav(pt, p)
                if d < bd:
                    bd, best = d, k
            return best, bd
        import heapq
        nb, db_ = nearest(BAR10)
        nt, dt_ = nearest(TOROWEAP)
        spd = None
        if nb and nt:
            dist = {nb: 0.0}; pq = [(0.0, nb)]
            while pq:
                d, x = heapq.heappop(pq)
                if x == nt:
                    spd = d; break
                if d > dist.get(x, 1e18):
                    continue
                for y, ei in adj[x]:
                    nd = d + edges[ei][2]
                    if nd < dist.get(y, 1e18):
                        dist[y] = nd; heapq.heappush(pq, (nd, y))

        print("%-7d %8d %8d %7d %8d %7.0f%% %6.0f%% %9s" %
              (tol, len(shared), len(edges), len(adj), len(comps),
               100.0*dead/n, 100.0*d4/n,
               ("%.1f mi" % spd) if spd else "none"))
        results[tol] = dict(shared=shared, touch=touch, keyfn=keyfn,
                            comps=len(comps), anchors=(db_, dt_))
        prev_shared = shared

    print("=" * 78)
    print("\nANCHOR DISTANCE TO THE MAIN NETWORK")
    for tol in TOLERANCES_FT:
        db_, dt_ = results[tol]["anchors"]
        print("  %3d ft   Bar 10 %5.2f mi   Toroweap %5.2f mi" % (tol, db_, dt_))

    # ── the eyeball test ────────────────────────────────────────────
    print("\n" + "=" * 78)
    print("JUNCTIONS GAINED BETWEEN TOLERANCES -- are these real?")
    print("=" * 78)
    for lo, hi in zip(TOLERANCES_FT, TOLERANCES_FT[1:]):
        s_lo, s_hi = results[lo]["shared"], results[hi]["shared"]
        t_hi, k_hi = results[hi]["touch"], results[hi]["keyfn"]
        # keys shared at hi. we cannot compare keys across tolerances directly
        # (different grids), so sample the hi-only junctions by re-testing the
        # actual vertex distance between the two trails meeting there.
        gained = len(s_hi) - len(s_lo)
        print("\n  %d ft -> %d ft : %+d junctions" % (lo, hi, gained))
        shown = 0
        for k in s_hi:
            tids = list(t_hi[k])
            if len(tids) < 2:
                continue
            a, b = tids[0], tids[1]
            if a == b:
                continue
            pa = next((p for t, n, ps in geoms if t == a for p in ps if k_hi(p) == k), None)
            pb = next((p for t, n, ps in geoms if t == b for p in ps if k_hi(p) == k), None)
            if not pa or not pb:
                continue
            gap_ft = hav(pa, pb) * 5280
            if gap_ft <= lo:      # would already have joined at the lower tol
                continue
            print("     %6.1f ft apart : %-30s  x  %s"
                  % (gap_ft, name_of.get(a, "?")[:30], name_of.get(b, "?")[:30]))
            shown += 1
            if shown >= 6:
                break
        if shown == 0:
            print("     (no sample found in this band)")

    con.close()
    print("\n" + "=" * 78)
    print("READ: the tolerance where components stop dropping is where real")
    print("junctions end. Junctions still appearing above that are trails that")
    print("pass NEAR each other, not trails that MEET.")
    print("=" * 78)
    return 0


if __name__ == "__main__":
    sys.exit(main())
