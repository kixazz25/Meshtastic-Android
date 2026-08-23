#!/usr/bin/env python3
"""
nav_pass3b_wip_routes_2026-08-22.py

PASS 3b -- four route recommendations as WIP DRAFTS the planner can open.

Supersedes pass 3's GPX-track output. Fred, 08-22: create them as WIP routes,
open them in Route+ and evaluate there -- the planner is already the inspection
surface, over real satellite imagery, with the editing tools to hand.

WHAT IT WRITES  (all into D:\\nav_test\\out\\ for you to push)

  route_drafts/Suggested Route N.json             <- open in the planner
  route_drafts/Suggested Route N.highlights.json  <- sidecar: scoring + narrative
  grouptrack_suggested_waypoints_2026-08-22.gpx   <- water / viewpoint / caution

DRAFT FORMAT -- exactly as RouteDraftStore documents it:
  { schemaVersion:1, name, createdAt, updatedAt, method:"point",
    vertices:[{lat,lon,lineId,lineType,segmentIndex,t,snapped}, ...] }

⭐ FULL FIDELITY, deliberately. Fred: "start out with the 4000 points and see if
it is a problem we even need to solve." Thinning was going to be guesswork about
where turns are and how snap-2 reconstructs between vertices -- no point
hypothesising when one run measures it. Vertex counts are reported; if the
planner struggles we will know the ceiling.

lineId carries the trail_id each vertex came from and snapped=true, because these
came from real trail geometry. Marking them unsnapped would invite the planner to
re-snap points that are already exactly right.

THE TOLERANCE, from the sweep:
  25 ft to build the network -- but every junction wider than 10 ft is carried as
  a CAUTION waypoint with its real gap, not hidden behind a threshold.
  "Trails mapped 14 ft apart" is something the rider can check against satellite.

READ ONLY on the DB.
"""

import sqlite3, math, os, sys, re, heapq, json, datetime
from collections import defaultdict, Counter, deque

DB   = r"D:\nav_test\grouptrack_spatial.db"
OUT  = r"D:\nav_test\out"

POINTS      = [("Bar 10", 36.43, -113.35), ("Toroweap", 36.20, -113.07)]
TOTAL_MILES = (50.0, 70.0)
N_ROUTES    = 4

SNAP_FT    = 25.0
CAUTION_FT = 10.0
POI_BUF_MI = 0.5
SPEED_MPH  = (12.0, 18.0)

LAT_S, LAT_N = 35.95, 36.65
LON_W, LON_E = -113.80, -112.80
NONAMES = {"", "not named", "unnamed", "none", "null", "n/a", "-"}
WATER = {"spring"}
VIEW  = {"peak", "cliff", "volcano"}


def hav(a, b):
    R = 3958.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1; dl = math.radians(b[1] - a[1])
    h = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2 * R * math.asin(math.sqrt(h))


def wkt_points(w):
    if not w: return []
    return [(float(la), float(lo)) for lo, la in
            re.findall(r'(-?\d+\.?\d*)\s+(-?\d+\.?\d*)', w)]


def real_name(n):
    return bool(n) and str(n).strip().lower() not in NONAMES


def esc(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


def main():
    if not os.path.isfile(DB):
        print("NOT FOUND: %s" % DB); return 1
    os.makedirs(os.path.join(OUT, "route_drafts"), exist_ok=True)
    con = sqlite3.connect("file:%s?mode=ro" % DB.replace("\\", "/"), uri=True)
    cur = con.cursor()

    print("=" * 76)
    print("PASS 3b -- WIP ROUTE DRAFTS   snap %.0fft, caution >%.0fft, %.0f-%.0f mi"
          % (SNAP_FT, CAUTION_FT, TOTAL_MILES[0], TOTAL_MILES[1]))
    print("=" * 76)

    geoms = []
    for tid, nm, wkt in cur.execute(
            "SELECT trail_id,name,geometry FROM trails WHERE min_lat<=? AND max_lat>=? "
            "AND min_lon<=? AND max_lon>=?", (LAT_N, LAT_S, LON_E, LON_W)):
        p = wkt_points(wkt)
        if len(p) >= 2:
            geoms.append((tid, nm, p))
    print("trails: %d" % len(geoms))

    dd_ = (SNAP_FT / 5280.0) / 69.0
    def key(p): return (int(round(p[0]/dd_)), int(round(p[1]/dd_)))

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
    ncaut = sum(1 for v in gap_ft.values() if v > CAUTION_FT)
    print("junctions: %d   (%d wider than %.0f ft)" % (len(shared), ncaut, CAUTION_FT))

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
    print("edges: %d   nodes: %d" % (len(edges), len(adj)))

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
    main_c = set(comps[0])
    print("main component: %d nodes" % len(main_c))

    def nearest(pt):
        best, bd = None, 1e9
        for k in main_c:
            v = hav(pt, nodes[k])
            if v < bd: bd, best = v, k
        return best, bd

    n_bar, d_bar = nearest((POINTS[0][1], POINTS[0][2]))
    n_tor, d_tor = nearest((POINTS[1][1], POINTS[1][2]))
    print("Bar 10 %.2f mi to network   Toroweap %.2f mi" % (d_bar, d_tor))

    poi_at = {}
    for nm, fc, la, lo in cur.execute(
            "SELECT name,fclass,lat,lon FROM reference_points WHERE lat BETWEEN ? AND ? "
            "AND lon BETWEEN ? AND ?", (LAT_S, LAT_N, LON_W, LON_E)):
        k, v = nearest((la, lo))
        if v <= POI_BUF_MI:
            poi_at.setdefault(k, []).append({"name": nm, "fclass": fc,
                                             "lat": la, "lon": lo, "off_mi": round(v, 3)})
    print("POIs on network: %d at %d nodes"
          % (sum(len(v) for v in poi_at.values()), len(poi_at)))

    terms = [n_bar, n_tor] + [k for k in poi_at if k not in (n_bar, n_tor)]
    def dij(s):
        dist = {s: 0.0}; prev = {}; pq = [(0.0, s)]
        while pq:
            d0, x = heapq.heappop(pq)
            if d0 > dist.get(x, 1e18): continue
            for y, ei in adj[x]:
                nd = d0 + edges[ei][2]
                if nd < dist.get(y, 1e18):
                    dist[y] = nd; prev[y] = (x, ei); heapq.heappush(pq, (nd, y))
        return dist, prev
    D, P = {}, {}
    print("routing between %d waypoint nodes..." % len(terms))
    for t in terms: D[t], P[t] = dij(t)

    def pe(s, t):
        out, x = [], t
        while x != s:
            if x not in P[s]: return None
            px, ei = P[s][x]; out.append(ei); x = px
        out.reverse(); return out

    base = D[n_bar].get(n_tor)
    print("one way %.1f mi   direct round trip %.1f mi   slack %.1f-%.1f mi"
          % (base, 2*base, TOTAL_MILES[0]-2*base, TOTAL_MILES[1]-2*base))

    pn = [k for k in poi_at if D[n_bar].get(k) is not None]
    def val(k):
        return sum(3.0 if p["fclass"] in VIEW else 2.0 if p["fclass"] in WATER else 1.0
                   for p in poi_at[k])

    BEAM = 260
    beam = [(0.0, 0.0, [n_bar], {n_bar})]; outs = []
    for _ in range(6):
        nx = []
        for sc, mi, sq, us in beam:
            last = sq[-1]
            f = mi + (D[last].get(n_tor) or 1e9)
            if f < TOTAL_MILES[1]: outs.append((sc, f, sq + [n_tor]))
            for k in pn:
                if k in us: continue
                st = D[last].get(k)
                if st is None: continue
                m2 = mi + st
                if m2 + (D[k].get(n_tor) or 1e9) > TOTAL_MILES[1]*0.62: continue
                nx.append((sc + val(k) - st*0.06, m2, sq + [k], us | {k}))
        nx.sort(key=lambda x: -x[0]); beam = nx[:BEAM]
        if not beam: break
    outs.sort(key=lambda x: -x[0])
    print("outbound legs: %d" % len(outs))

    res = []
    for sc_o, mi_o, sq_o in outs[:120]:
        oe = set()
        bad = False
        for a, b in zip(sq_o, sq_o[1:]):
            q = pe(a, b)
            if q is None: bad = True; break
            oe.update(q)
        if bad: continue
        beam = [(0.0, 0.0, [n_tor], set(sq_o))]; rets = []
        for _ in range(6):
            nx = []
            for sc, mi, sq, us in beam:
                last = sq[-1]
                f = mi + (D[last].get(n_bar) or 1e9)
                if TOTAL_MILES[0] <= mi_o + f <= TOTAL_MILES[1]:
                    rets.append((sc, f, sq + [n_bar]))
                for k in pn:
                    if k in us: continue
                    st = D[last].get(k)
                    if st is None: continue
                    m2 = mi + st
                    if mi_o + m2 + (D[k].get(n_bar) or 1e9) > TOTAL_MILES[1]: continue
                    nx.append((sc + val(k) - st*0.06, m2, sq + [k], us | {k}))
            nx.sort(key=lambda x: -x[0]); beam = nx[:BEAM]
            if not beam: break
        for sc_r, mi_r, sq_r in rets[:6]:
            full = sq_o + sq_r[1:]
            eids, ok = [], True
            for a, b in zip(full, full[1:]):
                q = pe(a, b)
                if q is None: ok = False; break
                eids.extend(q)
            if not ok: continue
            mi = sum(edges[e][2] for e in eids)
            if not (TOTAL_MILES[0] <= mi <= TOTAL_MILES[1]): continue
            es = set(eids)
            rep = 1.0 - len(es)/max(len(eids), 1)
            sp_, pl = set(), []
            for k in full:
                for p in poi_at.get(k, []):
                    if p["name"] in sp_: continue
                    sp_.add(p["name"]); pl.append(p)
            score = sum(3.0 if p["fclass"] in VIEW else 2.0 if p["fclass"] in WATER
                        else 1.0 for p in pl) - rep*8.0
            res.append({"nodes": full, "eids": eids, "eset": es, "miles": mi,
                        "pois": pl, "score": score, "repeat": rep})
    print("candidate circuits: %d" % len(res))
    if not res:
        print("none in band -- widen TOTAL_MILES"); return 3

    res.sort(key=lambda r: -r["score"])
    chosen = []
    for r in res:
        if all(len(r["eset"] & c["eset"])/max(len(r["eset"] | c["eset"]), 1) < 0.55
               for c in chosen):
            chosen.append(r)
        if len(chosen) == N_ROUTES: break
    print("distinct recommendations: %d\n" % len(chosen))

    now = datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")
    wpts = {}

    for idx, r in enumerate(chosen, 1):
        verts, legs, cautions = [], [], []
        cum = 0.0
        prev_name, leg_start, leg_pois = None, 0.0, []
        for e in r["eids"]:
            a, b, L, nm, tid, seg = edges[e]
            s = seg
            if verts and hav((verts[-1]["lat"], verts[-1]["lon"]), s[0]) > \
                         hav((verts[-1]["lat"], verts[-1]["lon"]), s[-1]):
                s = s[::-1]
            for i, p in enumerate(s):
                if verts and i == 0: continue
                verts.append({"lat": round(p[0], 6), "lon": round(p[1], 6),
                              "lineId": tid, "lineType": "trail",
                              "segmentIndex": -1, "t": 0.0, "snapped": True})
            label = nm or "unnamed track"
            if label != prev_name:
                if prev_name is not None:
                    legs.append({"trail": prev_name, "from_mi": round(leg_start, 2),
                                 "to_mi": round(cum, 2),
                                 "miles": round(cum-leg_start, 2), "pois": leg_pois})
                prev_name, leg_start, leg_pois = label, cum, []
            cum += L
            for k in (a, b):
                for p in poi_at.get(k, []):
                    if p["name"] not in [q["name"] for q in leg_pois]:
                        leg_pois.append({"name": p["name"], "fclass": p["fclass"],
                                         "at_mi": round(cum, 2), "off_mi": p["off_mi"]})
                g = gap_ft.get(k, 0.0)
                if g > CAUTION_FT and not any(abs(c["at_mi"]-cum) < 0.05 for c in cautions):
                    cautions.append({"at_mi": round(cum, 2), "gap_ft": round(g, 1),
                                     "lat": round(nodes[k][0], 6),
                                     "lon": round(nodes[k][1], 6)})
        if prev_name is not None:
            legs.append({"trail": prev_name, "from_mi": round(leg_start, 2),
                         "to_mi": round(cum, 2), "miles": round(cum-leg_start, 2),
                         "pois": leg_pois})

        lo_h, hi_h = r["miles"]/SPEED_MPH[1], r["miles"]/SPEED_MPH[0]
        name = "Suggested Route %d" % idx
        mix = Counter(p["fclass"] for p in r["pois"])

        print("-" * 76)
        print("%s      %.1f miles      %.1f-%.1f hours" % (name, r["miles"], lo_h, hi_h))
        print("-" * 76)
        print("  vertices %d   POIs %d (%s)   cautions %d   retrace %.0f%%"
              % (len(verts), len(r["pois"]),
                 ", ".join("%s %d" % (k, v) for k, v in mix.most_common()),
                 len(cautions), 100*r["repeat"]))
        print("  trail by trail:")
        for lg in legs:
            if lg["miles"] < 0.3: continue
            print("    %5.1f-%5.1f  %-34s %4.1f mi%s"
                  % (lg["from_mi"], lg["to_mi"], lg["trail"][:34], lg["miles"],
                     "   " + ", ".join(p["name"] for p in lg["pois"]) if lg["pois"] else ""))
        if cautions:
            print("  CAUTION -- unconfirmed junctions (zoom SAT):")
            for c in cautions[:10]:
                print("    mile %5.1f  %.5f,%.5f  trails %.0f ft apart"
                      % (c["at_mi"], c["lat"], c["lon"], c["gap_ft"]))

        json.dump({"schemaVersion": 1, "name": name, "createdAt": now,
                   "updatedAt": now, "method": "point", "vertices": verts},
                  open(os.path.join(OUT, "route_drafts", "%s.json" % name), "w"))
        json.dump({"name": name, "generated": now,
                   "summary": {"total_miles": round(r["miles"], 2),
                               "est_hours": [round(lo_h, 2), round(hi_h, 2)],
                               "speed_mph": list(SPEED_MPH),
                               "vertices": len(verts),
                               "poi_count": len(r["pois"]), "poi_mix": dict(mix),
                               "unconfirmed_junctions": len(cautions),
                               "retrace_pct": round(100*r["repeat"], 1),
                               "score": round(r["score"], 2)},
                   "legs": legs, "cautions": cautions,
                   "pois": r["pois"]},
                  open(os.path.join(OUT, "route_drafts",
                                    "%s.highlights.json" % name), "w"), indent=1)

        for p in r["pois"]:
            wpts[p["name"]] = (p["lat"], p["lon"],
                               "water" if p["fclass"] in WATER else "viewpoint",
                               "Drinking Water" if p["fclass"] in WATER else "Summit",
                               "%s - %s" % (p["fclass"], name))
        for c in cautions:
            wpts["CAUTION %.0fft %.4f,%.4f" % (c["gap_ft"], c["lat"], c["lon"])] = (
                c["lat"], c["lon"], "caution", "Danger Area",
                "Trails mapped %.0f ft apart - junction NOT confirmed. Zoom satellite "
                "to check for a connecting track. %s mile %.1f"
                % (c["gap_ft"], name, c["at_mi"]))

    g = ['<?xml version="1.0" encoding="UTF-8"?>',
         '<gpx version="1.1" creator="GroupTrack route research" '
         'xmlns="http://www.topografix.com/GPX/1/1">',
         '<metadata><name>Suggested route waypoints - Bar 10 / Toroweap</name></metadata>']
    for nm, (la, lo, ty, sy, de) in sorted(wpts.items()):
        g.append('<wpt lat="%.6f" lon="%.6f"><name>%s</name><type>%s</type>'
                 '<sym>%s</sym><desc>%s</desc></wpt>'
                 % (la, lo, esc(nm), esc(ty), esc(sy), esc(de)))
    g.append('</gpx>')
    gp = os.path.join(OUT, "grouptrack_suggested_waypoints_2026-08-22.gpx")
    open(gp, "w", encoding="utf-8").write("\n".join(g))

    print("\n" + "=" * 76)
    print("WRITTEN to %s" % OUT)
    print("  %d drafts + %d sidecars in route_drafts\\" % (len(chosen), len(chosen)))
    print("  %s  (%d waypoints)" % (os.path.basename(gp), len(wpts)))
    print("\nPUSH THE DRAFTS:")
    print('  MSYS_NO_PATHCONV=1 adb -s 24039703201775 push "%s/route_drafts/." '
          '/sdcard/Documents/GroupTrack/route_drafts/' % OUT.replace("\\", "/"))
    print("=" * 76)
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
