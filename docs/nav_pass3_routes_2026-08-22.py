#!/usr/bin/env python3
"""
nav_pass3_routes_2026-08-22.py

PASS 3 -- four route recommendations, emitted as one importable GPX.

THE PROBLEM (Fred, 08-22): not the fastest way to Toroweap. The POIs are the
objective; Bar 10 and Toroweap are constraints. Round trip, back to Bar 10,
50-70 miles TOTAL, meandering through as many worthwhile features as the
mileage allows.

TOLERANCE = 25 ft, decided from the sweep:
    0 ft  -> 5,540 junctions, 30% degree-4   (exact shared vertices, all real)
   10 ft  -> +2,119, samples 0.0-6.0 ft apart (float noise repair -- real)
   25 ft  -> +6,205, samples 11.8-14.8 ft     (probably real, worth a caution)
   50 ft+ -> degree-4 climbs to 81-85%, which is parallel tracks being welded
             together, not crossings. 150 ft actually LOSES junctions as the
             grid merges distinct ones.

So junctions above 10 ft are CARRIED WITH A CAUTION rather than hidden behind a
threshold -- Fred: "add that factor to the trail rating... highlight the 25'
intersection on the suggested route map so they can use sat to zoom in."

OUTPUT: D:\\nav_test\\grouptrack_suggested_routes_2026-08-22.gpx
  - 4 tracks, one per recommendation
  - waypoints: CAUTION junctions, viewpoints (peak/cliff/volcano), water (spring)
  - waypoint names are the POI feature names

READ ONLY on the DB.
"""

import sqlite3, math, os, sys, re, heapq, json
from collections import defaultdict, Counter, deque

DB  = r"D:\nav_test\grouptrack_spatial.db"
GPX = r"D:\nav_test\grouptrack_suggested_routes_2026-08-22.gpx"

POINTS = [("Bar 10", 36.43, -113.35),
          ("Toroweap", 36.20, -113.07),
          ("Bar 10", 36.43, -113.35)]
TOTAL_MILES = (50.0, 70.0)      # the WHOLE round trip
N_ROUTES    = 4

SNAP_FT     = 25.0
CAUTION_FT  = 10.0              # junctions wider than this get a caution marker
POI_BUF_MI  = 0.5
SPEED_MPH   = (12.0, 18.0)      # RideCalc band

LAT_S, LAT_N = 35.95, 36.65
LON_W, LON_E = -113.80, -112.80
NONAMES = {"", "not named", "unnamed", "none", "null", "n/a", "-"}

WATER = {"spring"}
VIEW  = {"peak", "cliff", "volcano"}


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


def esc(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


def main():
    if not os.path.isfile(DB):
        print("NOT FOUND: %s" % DB); return 1
    con = sqlite3.connect("file:%s?mode=ro" % DB.replace("\\", "/"), uri=True)
    cur = con.cursor()

    print("=" * 74)
    print("PASS 3 -- ROUTE RECOMMENDATIONS  (snap %.0f ft, caution above %.0f ft)"
          % (SNAP_FT, CAUTION_FT))
    print("=" * 74)

    rows = list(cur.execute(
        "SELECT trail_id,name,geometry FROM trails "
        "WHERE min_lat<=? AND max_lat>=? AND min_lon<=? AND max_lon>=?",
        (LAT_N, LAT_S, LON_E, LON_W)))
    geoms = []
    for tid, nm, wkt in rows:
        pts = wkt_points(wkt)
        if len(pts) >= 2:
            geoms.append((tid, nm, pts))
    print("trails: %d" % len(geoms))

    d = (SNAP_FT / 5280.0) / 69.0
    def key(p):
        return (int(round(p[0]/d)), int(round(p[1]/d)))

    # junction discovery + how far apart the merged vertices really are
    touch = defaultdict(set)
    spread = defaultdict(list)
    for tid, nm, pts in geoms:
        for p in pts:
            k = key(p)
            touch[k].add(tid)
            spread[k].append(p)
    shared = {k for k, s in touch.items() if len(s) > 1}

    gap_ft = {}
    for k in shared:
        ps = spread[k]
        g = 0.0
        for i in range(len(ps)):
            for j in range(i+1, min(len(ps), i+6)):
                g = max(g, hav(ps[i], ps[j]) * 5280)
        gap_ft[k] = g
    print("junctions: %d   (%d wider than %.0f ft -> caution)"
          % (len(shared), sum(1 for v in gap_ft.values() if v > CAUTION_FT), CAUTION_FT))

    # split into edges
    edges, nodes = [], {}
    for tid, nm, pts in geoms:
        cuts = [0, len(pts)-1] + [i for i in range(1, len(pts)-1) if key(pts[i]) in shared]
        cuts = sorted(set(cuts))
        for a, b in zip(cuts, cuts[1:]):
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
            edges.append((ka, kb, L, (nm if real_name(nm) else None), seg))
    adj = defaultdict(list)
    for i, (a, b, L, nm, seg) in enumerate(edges):
        adj[a].append((b, i)); adj[b].append((a, i))
    print("edges: %d   nodes: %d" % (len(edges), len(adj)))

    # main component
    seen, comps = set(), []
    for n in adj:
        if n in seen: continue
        q, c = deque([n]), []; seen.add(n)
        while q:
            x = q.popleft(); c.append(x)
            for y, _ in adj[x]:
                if y not in seen: seen.add(y); q.append(y)
        comps.append(c)
    comps.sort(key=len, reverse=True)
    main = set(comps[0])
    print("main component: %d nodes of %d" % (len(main), len(adj)))

    def nearest(pt):
        best, bd = None, 1e9
        for k in main:
            dd = hav(pt, nodes[k])
            if dd < bd: bd, best = dd, k
        return best, bd

    n_bar, d_bar = nearest((POINTS[0][1], POINTS[0][2]))
    n_tor, d_tor = nearest((POINTS[1][1], POINTS[1][2]))
    print("Bar 10 -> node %.2f mi   Toroweap -> node %.2f mi" % (d_bar, d_tor))

    # ── POIs onto nodes ─────────────────────────────────────────────
    pois = list(cur.execute(
        "SELECT name,fclass,lat,lon FROM reference_points "
        "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
        (LAT_S, LAT_N, LON_W, LON_E)))
    poi_at = {}
    for nm, fc, la, lo in pois:
        k, dd = nearest((la, lo))
        if dd <= POI_BUF_MI:
            poi_at.setdefault(k, []).append({"name": nm, "fclass": fc,
                                             "lat": la, "lon": lo, "d": dd})
    npoi = sum(len(v) for v in poi_at.values())
    print("POIs on network: %d at %d nodes" % (npoi, len(poi_at)))
    for k, n in Counter(p["fclass"] for v in poi_at.values() for p in v).most_common():
        print("   %-10s %d" % (k, n))

    # ── all-pairs among anchors + POI nodes ─────────────────────────
    terms = [n_bar, n_tor] + [k for k in poi_at if k not in (n_bar, n_tor)]
    print("\nrouting between %d waypoint nodes..." % len(terms))

    def dijkstra(src):
        dist = {src: 0.0}; prev = {}
        pq = [(0.0, src)]
        while pq:
            dd, x = heapq.heappop(pq)
            if dd > dist.get(x, 1e18): continue
            for y, ei in adj[x]:
                nd = dd + edges[ei][2]
                if nd < dist.get(y, 1e18):
                    dist[y] = nd; prev[y] = (x, ei); heapq.heappush(pq, (nd, y))
        return dist, prev

    D, P = {}, {}
    for t in terms:
        D[t], P[t] = dijkstra(t)

    def path_edges(src, dst):
        out, x = [], dst
        while x != src:
            if x not in P[src]: return None
            px, ei = P[src][x]
            out.append(ei); x = px
        out.reverse(); return out

    base = D[n_bar].get(n_tor)
    if base is None:
        print("no path Bar 10 -> Toroweap"); return 2
    print("shortest one way %.1f mi   direct round trip %.1f mi   band %.0f-%.0f"
          % (base, 2*base, TOTAL_MILES[0], TOTAL_MILES[1]))

    # ── search: beam over POI sequences, out via some, back via others ──
    poinodes = [k for k in poi_at if k in D and D[n_bar].get(k) is not None]
    def val(k):
        v = 0.0
        for p in poi_at[k]:
            v += 3.0 if p["fclass"] in VIEW else (2.0 if p["fclass"] in WATER else 1.0)
        return v

    cands = []
    BEAM = 260
    # outbound: Bar10 -> ... -> Toroweap
    beam = [(0.0, 0.0, [n_bar], {n_bar})]
    legs_out = []
    for _ in range(6):
        nxt = []
        for sc, mi, seq, used in beam:
            last = seq[-1]
            fin = mi + (D[last].get(n_tor) or 1e9)
            if fin < TOTAL_MILES[1]:
                legs_out.append((sc, fin, seq + [n_tor]))
            for k in poinodes:
                if k in used: continue
                step = D[last].get(k)
                if step is None: continue
                m2 = mi + step
                if m2 + (D[k].get(n_tor) or 1e9) > TOTAL_MILES[1] * 0.62:
                    continue
                nxt.append((sc + val(k) - step*0.06, m2, seq + [k], used | {k}))
        nxt.sort(key=lambda x: -x[0]); beam = nxt[:BEAM]
        if not beam: break
    legs_out.sort(key=lambda x: -x[0])
    print("outbound legs found: %d" % len(legs_out))

    # return: Toroweap -> ... -> Bar10, avoiding what the outbound used
    results = []
    for sc_o, mi_o, seq_o in legs_out[:120]:
        oedges = set()
        for a, b in zip(seq_o, seq_o[1:]):
            pe = path_edges(a, b)
            if pe is None: oedges = None; break
            oedges.update(pe)
        if oedges is None: continue
        beam = [(0.0, 0.0, [n_tor], set(seq_o))]
        best_r = []
        for _ in range(6):
            nxt = []
            for sc, mi, seq, used in beam:
                last = seq[-1]
                fin = mi + (D[last].get(n_bar) or 1e9)
                tot = mi_o + fin
                if TOTAL_MILES[0] <= tot <= TOTAL_MILES[1]:
                    best_r.append((sc, fin, seq + [n_bar]))
                for k in poinodes:
                    if k in used: continue
                    step = D[last].get(k)
                    if step is None: continue
                    m2 = mi + step
                    if mi_o + m2 + (D[k].get(n_bar) or 1e9) > TOTAL_MILES[1]:
                        continue
                    nxt.append((sc + val(k) - step*0.06, m2, seq + [k], used | {k}))
            nxt.sort(key=lambda x: -x[0]); beam = nxt[:BEAM]
            if not beam: break
        for sc_r, mi_r, seq_r in best_r[:6]:
            full = seq_o + seq_r[1:]
            eids, ok = [], True
            for a, b in zip(full, full[1:]):
                pe = path_edges(a, b)
                if pe is None: ok = False; break
                eids.extend(pe)
            if not ok: continue
            es = set(eids)
            overlap = len(oedges & set(eids[len(oedges):])) / max(len(es), 1)
            miles = sum(edges[e][2] for e in eids)
            if not (TOTAL_MILES[0] <= miles <= TOTAL_MILES[1]): continue
            seenp, plist = set(), []
            for k in full:
                for p in poi_at.get(k, []):
                    if p["name"] in seenp: continue
                    seenp.add(p["name"]); plist.append(p)
            score = (sum(3.0 if p["fclass"] in VIEW else 2.0 if p["fclass"] in WATER
                         else 1.0 for p in plist)
                     - overlap * 6.0)
            results.append({"nodes": full, "edges": eids, "eset": es,
                            "miles": miles, "pois": plist, "score": score,
                            "overlap": overlap})

    print("candidate circuits: %d" % len(results))
    if not results:
        print("none in the mileage band -- widen TOTAL_MILES and re-run"); return 3

    results.sort(key=lambda r: -r["score"])
    chosen = []
    for r in results:
        if all(len(r["eset"] & c["eset"]) / max(len(r["eset"] | c["eset"]), 1) < 0.55
               for c in chosen):
            chosen.append(r)
        if len(chosen) == N_ROUTES: break
    print("distinct recommendations: %d\n" % len(chosen))

    # ── report + GPX ────────────────────────────────────────────────
    wpts, trks = {}, []
    for i, r in enumerate(chosen, 1):
        pts, cautions, names = [], [], Counter()
        for e in r["edges"]:
            a, b, L, nm, seg = edges[e]
            if pts and hav(pts[-1], seg[0]) > hav(pts[-1], seg[-1]):
                seg = seg[::-1]
            pts.extend(seg if not pts else seg[1:])
            if nm: names[nm] += L
        for k in r["nodes"]:
            g = gap_ft.get(k, 0.0)
            if g > CAUTION_FT:
                cautions.append((nodes[k], g))
        lo, hi = r["miles"]/SPEED_MPH[1], r["miles"]/SPEED_MPH[0]
        print("-" * 74)
        print("SUGGESTED ROUTE %d      %.1f miles      %.1f-%.1f hours riding"
              % (i, r["miles"], lo, hi))
        print("-" * 74)
        print("  points of interest: %d   (%s)"
              % (len(r["pois"]),
                 ", ".join("%s %d" % (k, v) for k, v in
                           Counter(p["fclass"] for p in r["pois"]).most_common())))
        print("  unconfirmed junctions: %d   distinct from other routes: %.0f%%"
              % (len(cautions), 100*(1-r["overlap"])))
        print("  named trail miles:")
        for nm, mi in names.most_common(8):
            print("      %-38s %5.1f mi" % (nm[:38], mi))
        print("  features:")
        for p in r["pois"]:
            print("      %-10s %s" % (p["fclass"], p["name"]))
        if cautions:
            print("  CAUTION -- junction mapped but not confirmed (zoom SAT here):")
            for (la, lo_), g in cautions[:12]:
                print("      %.5f, %.5f   trails %.0f ft apart" % (la, lo_, g))

        trks.append((i, r["miles"], pts))
        for p in r["pois"]:
            sym = "Drinking Water" if p["fclass"] in WATER else "Summit"
            typ = "water" if p["fclass"] in WATER else "viewpoint"
            wpts[p["name"]] = (p["lat"], p["lon"], typ, sym,
                               "%s near suggested route %d" % (p["fclass"], i))
        for (la, lo_), g in cautions:
            nm = "CAUTION junction %.0fft" % g
            wpts["%s %.4f,%.4f" % (nm, la, lo_)] = (
                la, lo_, "caution", "Danger Area",
                "Trails mapped %.0f ft apart - junction not confirmed. "
                "Zoom satellite to check for a connecting track." % g)

    x = ['<?xml version="1.0" encoding="UTF-8"?>',
         '<gpx version="1.1" creator="GroupTrack route research 2026-08-22" '
         'xmlns="http://www.topografix.com/GPX/1/1">',
         '<metadata><name>GroupTrack suggested routes - Bar 10 / Toroweap</name>'
         '<desc>%d circuits, %.0f-%.0f mi, POI-weighted</desc></metadata>'
         % (len(chosen), TOTAL_MILES[0], TOTAL_MILES[1])]
    for nm, (la, lo_, typ, sym, desc) in sorted(wpts.items()):
        x.append('<wpt lat="%.6f" lon="%.6f"><name>%s</name><type>%s</type>'
                 '<sym>%s</sym><desc>%s</desc></wpt>'
                 % (la, lo_, esc(nm), esc(typ), esc(sym), esc(desc)))
    for i, mi, pts in trks:
        x.append('<trk><name>Suggested Route %d - %.0f mi</name><trkseg>' % (i, mi))
        x.extend('<trkpt lat="%.6f" lon="%.6f"></trkpt>' % (a, b) for a, b in pts)
        x.append('</trkseg></trk>')
    x.append('</gpx>')
    open(GPX, "w", encoding="utf-8").write("\n".join(x))

    print("\n" + "=" * 74)
    print("GPX: %s  (%.0f KB)" % (GPX, os.path.getsize(GPX)/1024))
    print("  %d tracks, %d waypoints" % (len(trks), len(wpts)))
    print("=" * 74)
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
