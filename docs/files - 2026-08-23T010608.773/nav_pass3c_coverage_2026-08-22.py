#!/usr/bin/env python3
"""
nav_pass3c_coverage_2026-08-22.py

PASS 3c -- take the mileage limit OFF and find out what the data can actually do.

WHY. Pass 3b produced ONE route, 69.5 mi, collecting 5 POIs of 39 -- and no
springs at all, though 22 are on the network. Fred: "not sure why closest trail
is taking priority. point value should be the winner and if we are not getting
all features & POIs the scoring is incorrect?"

He was right, and the bug was structural rather than a bad constant. Pass 3b
scored `poi_value - miles*0.06`, which makes it a CHEAPEST-PATH search with a
POI bonus. What was asked for is a MAXIMUM-POI search with a mileage budget.
Distance should never have been in the score -- it is a constraint, not a cost.
With a 53.7 mi direct round trip inside a 50-70 band there was ~16 mi of slack,
and every detouring branch lost the beam to a direct one before it could pay off.

SO: score POIs only. Miles are spent until the budget runs out. And this pass
removes the budget entirely first, to establish the CEILING -- what does full
coverage cost? Then it reports the curve: POIs collected at 60, 80, 100, 150,
200 mi and unlimited. That curve is what should inform the suggester, rather
than a band picked in advance.

ALSO FIXED FROM 3b:
  - cautions were emitted per NODE VISIT, so one junction the route passes three
    times appeared three times (310 cautions, 186 waypoints from 39 POIs).
    Now deduped by junction.
  - leg accounting dropped ~17 mi around the Toroweap turnaround.

READ ONLY.
"""

import sqlite3, math, os, sys, re, heapq, json, datetime
from collections import defaultdict, Counter, deque

DB  = r"D:\nav_test\grouptrack_spatial.db"
OUT = r"D:\nav_test\out"

BAR10    = (36.43, -113.35)
TOROWEAP = (36.20, -113.07)

BUDGETS = [60, 80, 100, 150, 200, None]   # None = unlimited
SNAP_FT, CAUTION_FT, POI_BUF_MI = 25.0, 10.0, 0.5
SPEED_MPH = (12.0, 18.0)
LAT_S, LAT_N, LON_W, LON_E = 35.95, 36.65, -113.80, -112.80
NONAMES = {"", "not named", "unnamed", "none", "null", "n/a", "-"}
WATER = {"spring"}
VIEW  = {"peak", "cliff", "volcano"}


def hav(a, b):
    R = 3958.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2-p1; dl = math.radians(b[1]-a[1])
    h = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*R*math.asin(math.sqrt(h))


def wkt_points(w):
    if not w: return []
    return [(float(a), float(b)) for b, a in
            re.findall(r'(-?\d+\.?\d*)\s+(-?\d+\.?\d*)', w)]


def real_name(n):
    return bool(n) and str(n).strip().lower() not in NONAMES


def esc(s):
    return (str(s).replace("&","&amp;").replace("<","&lt;")
            .replace(">","&gt;").replace('"',"&quot;"))


def main():
    if not os.path.isfile(DB):
        print("NOT FOUND: %s" % DB); return 1
    os.makedirs(os.path.join(OUT, "route_drafts"), exist_ok=True)
    con = sqlite3.connect("file:%s?mode=ro" % DB.replace("\\","/"), uri=True)
    cur = con.cursor()

    print("="*76)
    print("PASS 3c -- WHAT CAN THE DATA ACTUALLY DO?  (no mileage limit first)")
    print("="*76)

    geoms = []
    for tid, nm, wkt in cur.execute(
            "SELECT trail_id,name,geometry FROM trails WHERE min_lat<=? AND max_lat>=? "
            "AND min_lon<=? AND max_lon>=?", (LAT_N, LAT_S, LON_E, LON_W)):
        p = wkt_points(wkt)
        if len(p) >= 2: geoms.append((tid, nm, p))

    d_ = (SNAP_FT/5280.0)/69.0
    def key(p): return (int(round(p[0]/d_)), int(round(p[1]/d_)))
    touch, spread = defaultdict(set), defaultdict(list)
    for tid, nm, pts in geoms:
        for p in pts:
            k = key(p); touch[k].add(tid); spread[k].append(p)
    shared = {k for k, s in touch.items() if len(s) > 1}
    gap_ft = {}
    for k in shared:
        ps = spread[k]; g = 0.0
        for i in range(len(ps)):
            for j in range(i+1, min(len(ps), i+6)):
                g = max(g, hav(ps[i], ps[j])*5280)
        gap_ft[k] = g

    edges, nodes = [], {}
    for tid, nm, pts in geoms:
        cuts = sorted(set([0, len(pts)-1] +
                          [i for i in range(1, len(pts)-1) if key(pts[i]) in shared]))
        for a, b in zip(cuts, cuts[1:]):
            seg = pts[a:b+1]
            if len(seg) < 2: continue
            L = sum(hav(seg[i], seg[i+1]) for i in range(len(seg)-1))
            if L <= 0.0005: continue
            ka, kb = key(seg[0]), key(seg[-1])
            if ka == kb: continue
            nodes.setdefault(ka, seg[0]); nodes.setdefault(kb, seg[-1])
            edges.append((ka, kb, L, (nm if real_name(nm) else None), tid, seg))
    adj = defaultdict(list)
    for i, e in enumerate(edges):
        adj[e[0]].append((e[1], i)); adj[e[1]].append((e[0], i))

    seen, comps = set(), []
    for n in adj:
        if n in seen: continue
        q, c = deque([n]), []; seen.add(n)
        while q:
            x = q.popleft(); c.append(x)
            for y, _ in adj[x]:
                if y not in seen: seen.add(y); q.append(y)
        comps.append(c)
    comps.sort(key=len, reverse=True); main_c = set(comps[0])
    print("network: %d edges, %d nodes, main component %d"
          % (len(edges), len(adj), len(main_c)))

    def nearest(pt):
        best, bd = None, 1e9
        for k in main_c:
            v = hav(pt, nodes[k])
            if v < bd: bd, best = v, k
        return best, bd
    n_bar, _ = nearest(BAR10); n_tor, _ = nearest(TOROWEAP)

    poi_at = {}
    for nm, fc, la, lo in cur.execute(
            "SELECT name,fclass,lat,lon FROM reference_points WHERE lat BETWEEN ? AND ? "
            "AND lon BETWEEN ? AND ?", (LAT_S, LAT_N, LON_W, LON_E)):
        k, v = nearest((la, lo))
        if v <= POI_BUF_MI:
            poi_at.setdefault(k, []).append({"name": nm, "fclass": fc,
                                             "lat": la, "lon": lo, "off_mi": round(v,3)})
    allp = [p for v in poi_at.values() for p in v]
    print("POIs reachable: %d at %d nodes  (%s)"
          % (len(allp), len(poi_at),
             ", ".join("%s %d" % (k,v) for k,v in
                       Counter(p["fclass"] for p in allp).most_common())))

    terms = [n_bar, n_tor] + [k for k in poi_at if k not in (n_bar, n_tor)]
    def dij(s):
        dist = {s:0.0}; prev = {}; pq=[(0.0,s)]
        while pq:
            d0,x = heapq.heappop(pq)
            if d0 > dist.get(x,1e18): continue
            for y,ei in adj[x]:
                nd = d0+edges[ei][2]
                if nd < dist.get(y,1e18):
                    dist[y]=nd; prev[y]=(x,ei); heapq.heappush(pq,(nd,y))
        return dist, prev
    D, P = {}, {}
    for t in terms: D[t], P[t] = dij(t)
    def pe(s,t):
        out,x = [],t
        while x != s:
            if x not in P[s]: return None
            px,ei = P[s][x]; out.append(ei); x=px
        out.reverse(); return out

    base = D[n_bar].get(n_tor)
    print("direct: Bar10 -> Toroweap %.1f mi, round trip %.1f mi" % (base, 2*base))

    pn = [k for k in poi_at if D[n_bar].get(k) is not None and D[n_tor].get(k) is not None]
    print("POI nodes routable from BOTH anchors: %d of %d\n" % (len(pn), len(poi_at)))

    # ── greedy nearest-unvisited coverage, no budget ────────────────
    # Deliberately simple: this establishes the CEILING and the curve, not the
    # optimal tour. Optimality can come later; what matters now is the shape.
    def cover(order_from, must_end, budget):
        """Greedy: from a start node, repeatedly hop to the nearest unvisited POI
        node that still leaves room to finish. Returns (nodes, miles, poinodes)."""
        cur_n, miles, seq, got = order_from, 0.0, [order_from], []
        left = set(pn)
        while True:
            best, bd = None, 1e18
            for k in left:
                s1 = D[cur_n].get(k)
                if s1 is None: continue
                back = D[k].get(must_end)
                if back is None: continue
                if budget is not None and miles + s1 + back > budget: continue
                if s1 < bd: bd, best = s1, k
            if best is None: break
            miles += bd; cur_n = best; seq.append(best); got.append(best)
            left.discard(best)
        fin = D[cur_n].get(must_end)
        if fin is None: return None
        miles += fin; seq.append(must_end)
        return seq, miles, got

    print("="*76)
    print("COVERAGE CURVE -- Bar10 -> (POIs) -> Toroweap -> (POIs) -> Bar10")
    print("="*76)
    print("%-10s %10s %8s %8s   %s" % ("budget", "miles", "POIs", "of", "mix"))
    curve = {}
    for B in BUDGETS:
        half = None if B is None else B*0.55
        a = cover(n_bar, n_tor, half)
        if not a: continue
        seq_a, mi_a, got_a = a
        rem = None if B is None else B - mi_a
        cur_n, miles_b, seq_b, got_b = n_tor, 0.0, [n_tor], []
        left = set(pn) - set(got_a)
        while True:
            best, bd = None, 1e18
            for k in left:
                s1 = D[cur_n].get(k); back = D[k].get(n_bar)
                if s1 is None or back is None: continue
                if rem is not None and miles_b + s1 + back > rem: continue
                if s1 < bd: bd, best = s1, k
            if best is None: break
            miles_b += bd; cur_n = best; seq_b.append(best); got_b.append(best); left.discard(best)
        miles_b += D[cur_n].get(n_bar, 0.0); seq_b.append(n_bar)
        full = seq_a + seq_b[1:]
        total = mi_a + miles_b
        names = []
        for k in got_a + got_b:
            names.extend(p["name"] for p in poi_at[k])
        mix = Counter(p["fclass"] for k in got_a+got_b for p in poi_at[k])
        curve[B] = (full, total, names, mix)
        print("%-10s %9.1f %8d %8d   %s"
              % (("unlimited" if B is None else "%d mi" % B), total, len(names), len(allp),
                 ", ".join("%s %d" % (a_, b_) for a_, b_ in mix.most_common())))

    # ── what the ceiling misses ─────────────────────────────────────
    if None in curve:
        got = set(curve[None][2])
        missed = [p for p in allp if p["name"] not in got]
        print("\nUNREACHED even unlimited: %d" % len(missed))
        for p in missed[:20]:
            print("   %-10s %s" % (p["fclass"], p["name"]))

    print("\n" + "="*76)
    print("READ: the curve is what mileage BUYS. If 100 mi collects most of the")
    print("39 and 60 mi collects a third, that is the trade -- and it should")
    print("drive the suggester's design rather than a band picked in advance.")
    print("="*76)
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
