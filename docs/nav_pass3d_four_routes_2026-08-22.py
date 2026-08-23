#!/usr/bin/env python3
"""
nav_pass3d_four_routes_2026-08-22.py

PASS 3d -- FOUR route drafts, properly optimised.

WHAT THE COVERAGE CURVE (3c) ESTABLISHED:
    60 mi ->  4 POIs      150 mi -> 12
    80 mi ->  6           200 mi -> 15
   100 mi ->  8           unlimited 448.7 mi -> all 39
All 39 are reachable; nothing is stranded. But the curve is close to LINEAR --
about 12 miles per POI at every budget. There is no cheap cluster to sweep, so
a 60-mile ride reaching 4-5 features is not a scoring failure, it is what the
corridor costs.

WHAT WAS STILL WRONG IN 3c: greedy nearest-unvisited chases whatever is closest,
which is why 60 mi returned four peaks and NO springs -- though springs are 22 of
the 39. The peaks happen to sit near the direct line. Choosing the next hop is
not the same as choosing the set.

THIS PASS: randomised greedy over value/distance ratio, thousands of restarts,
keeping the best SETS rather than the best next step. Distance stays out of the
score entirely -- it is a budget. Variety is weighted so four springs beat the
same peak reached twice.

FIXED FROM 3b:
  - cautions deduped by JUNCTION, not per node visit (3b emitted 310 for one
    route, and 186 waypoints from 39 POIs)
  - leg accounting no longer drops the miles around the Toroweap turnaround

OUT: D:\\nav_test\\out\\route_drafts\\Suggested Route 1..4.json  (+ .highlights.json)
     D:\\nav_test\\out\\grouptrack_suggested_waypoints_2026-08-22.gpx
"""

import sqlite3, math, os, sys, re, heapq, json, random, datetime
from collections import defaultdict, Counter, deque

DB  = r"D:\nav_test\grouptrack_spatial.db"
OUT = r"D:\nav_test\out"

BAR10, TOROWEAP = (36.43, -113.35), (36.20, -113.07)
BUDGET   = (55.0, 70.0)
N_ROUTES = 4
TRIES    = 4000

SNAP_FT, CAUTION_FT, POI_BUF_MI = 25.0, 10.0, 0.5
SPEED_MPH = (12.0, 18.0)
LAT_S, LAT_N, LON_W, LON_E = 35.95, 36.65, -113.80, -112.80
NONAMES = {"", "not named", "unnamed", "none", "null", "n/a", "-"}
WATER, VIEW = {"spring"}, {"peak", "cliff", "volcano"}
random.seed(20260822)


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
    print("="*78)
    print("PASS 3d -- FOUR ROUTES  budget %.0f-%.0f mi, %d restarts"
          % (BUDGET[0], BUDGET[1], TRIES))
    print("="*78)

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
    print("network %d edges / %d nodes;  POIs %d at %d nodes"
          % (len(edges), len(adj), len(allp), len(poi_at)))

    terms = [n_bar, n_tor] + [k for k in poi_at if k not in (n_bar, n_tor)]
    def dij(s):
        dist={s:0.0}; prev={}; pq=[(0.0,s)]
        while pq:
            d0,x=heapq.heappop(pq)
            if d0>dist.get(x,1e18): continue
            for y,ei in adj[x]:
                nd=d0+edges[ei][2]
                if nd<dist.get(y,1e18):
                    dist[y]=nd; prev[y]=(x,ei); heapq.heappush(pq,(nd,y))
        return dist,prev
    D,P={},{}
    for t in terms: D[t],P[t]=dij(t)
    def pe(s,t):
        out,x=[],t
        while x!=s:
            if x not in P[s]: return None
            px,ei=P[s][x]; out.append(ei); x=px
        out.reverse(); return out

    base = D[n_bar].get(n_tor)
    print("direct round trip %.1f mi -> slack %.1f-%.1f mi\n"
          % (2*base, BUDGET[0]-2*base, BUDGET[1]-2*base))

    pn = [k for k in poi_at if D[n_bar].get(k) is not None]

    def setscore(ks):
        """Value of a SET of poi nodes. Variety weighted -- a second class of
        feature is worth more than another of the same."""
        seenn, byc = set(), Counter()
        v = 0.0
        for k in ks:
            for p in poi_at[k]:
                if p["name"] in seenn: continue
                seenn.add(p["name"]); byc[p["fclass"]] += 1
                base_v = 3.0 if p["fclass"] in VIEW else 2.0 if p["fclass"] in WATER else 1.0
                v += base_v / (1.0 + 0.22*(byc[p["fclass"]]-1))
        return v

    # ── randomised greedy over value/distance ratio ─────────────────
    best = []
    for _ in range(TRIES):
        half = BUDGET[1]*0.55
        chosen, cur_n, mi = [], n_bar, 0.0
        left = set(pn)
        phase, endn = 0, n_tor
        while True:
            cands = []
            for k in left:
                s1 = D[cur_n].get(k); back = D[k].get(endn)
                if s1 is None or back is None: continue
                lim = half if phase == 0 else BUDGET[1]
                if mi + s1 + back > lim: continue
                gain = setscore(chosen + [k]) - setscore(chosen)
                if gain <= 0: continue
                cands.append((gain / max(s1, 0.35), k, s1))
            if not cands:
                if phase == 0:
                    mi += D[cur_n].get(n_tor, 1e9); cur_n = n_tor
                    phase, endn = 1, n_bar
                    continue
                break
            cands.sort(reverse=True)
            pick = cands[min(int(random.random()**2 * 4), len(cands)-1)]
            _, k, s1 = pick
            mi += s1; cur_n = k; chosen.append(k); left.discard(k)
        if phase == 0:
            mi += D[cur_n].get(n_tor, 1e9); cur_n = n_tor
        mi += D[cur_n].get(n_bar, 1e9)
        if not (BUDGET[0] <= mi <= BUDGET[1]): continue
        best.append((setscore(chosen), mi, tuple(chosen)))
    best.sort(key=lambda x: -x[0])
    print("feasible circuits found: %d" % len(best))
    if not best:
        print("none in budget"); return 3

    # ── rebuild full geometry, pick 4 distinct ──────────────────────
    def build(chosen):
        seq = [n_bar]
        # order: outbound half, toroweap, return half -- as generated
        for k in chosen: seq.append(k)
        # ensure toroweap is on the path
        if n_tor not in seq: seq.append(n_tor)
        seq.append(n_bar)
        eids = []
        for a, b in zip(seq, seq[1:]):
            q = pe(a, b)
            if q is None: return None
            eids.extend(q)
        return seq, eids

    picks = []
    for sc, mi, chosen in best:
        r = build(list(chosen))
        if not r: continue
        seq, eids = r
        es = set(eids)
        m = sum(edges[e][2] for e in eids)
        if not (BUDGET[0] <= m <= BUDGET[1]): continue
        if any(len(es & p["eset"])/max(len(es | p["eset"]),1) > 0.55 for p in picks):
            continue
        picks.append({"seq": seq, "eids": eids, "eset": es, "miles": m,
                      "score": sc, "chosen": chosen,
                      "repeat": 1.0 - len(es)/max(len(eids),1)})
        if len(picks) == N_ROUTES: break
    print("distinct recommendations: %d\n" % len(picks))

    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    wpts = {}
    for idx, r in enumerate(picks, 1):
        verts, legs = [], []
        caut = {}                       # keyed by junction -> dedup
        cum, prev_name, leg_start, leg_pois = 0.0, None, 0.0, []
        seenpoi = set(); plist = []
        for e in r["eids"]:
            a, b, L, nm, tid, seg = edges[e]
            s = seg
            if verts and hav((verts[-1]["lat"], verts[-1]["lon"]), s[0]) > \
                         hav((verts[-1]["lat"], verts[-1]["lon"]), s[-1]):
                s = s[::-1]
            for i, p in enumerate(s):
                if verts and i == 0: continue
                verts.append({"lat": round(p[0],6), "lon": round(p[1],6),
                              "lineId": tid, "lineType": "trail",
                              "segmentIndex": -1, "t": 0.0, "snapped": True})
            lbl = nm or "unnamed track"
            if lbl != prev_name:
                if prev_name is not None:
                    legs.append({"trail": prev_name, "from_mi": round(leg_start,2),
                                 "to_mi": round(cum,2), "miles": round(cum-leg_start,2),
                                 "pois": leg_pois})
                prev_name, leg_start, leg_pois = lbl, cum, []
            cum += L
            for k in (a, b):
                for p in poi_at.get(k, []):
                    if p["name"] in seenpoi: continue
                    seenpoi.add(p["name"]); plist.append(p)
                    leg_pois.append({"name": p["name"], "fclass": p["fclass"],
                                     "at_mi": round(cum,2), "off_mi": p["off_mi"]})
                g = gap_ft.get(k, 0.0)
                if g > CAUTION_FT and k not in caut:
                    caut[k] = {"at_mi": round(cum,2), "gap_ft": round(g,1),
                               "lat": round(nodes[k][0],6), "lon": round(nodes[k][1],6)}
        if prev_name is not None:
            legs.append({"trail": prev_name, "from_mi": round(leg_start,2),
                         "to_mi": round(cum,2), "miles": round(cum-leg_start,2),
                         "pois": leg_pois})
        cautions = sorted(caut.values(), key=lambda c: c["at_mi"])
        lo_h, hi_h = r["miles"]/SPEED_MPH[1], r["miles"]/SPEED_MPH[0]
        name = "Suggested Route %d" % idx
        mix = Counter(p["fclass"] for p in plist)

        print("-"*78)
        print("%s      %.1f miles      %.1f-%.1f hours" % (name, r["miles"], lo_h, hi_h))
        print("-"*78)
        print("  %d of %d features (%s)   %d vertices   %d cautions   %.0f%% retrace"
              % (len(plist), len(allp),
                 ", ".join("%s %d" % (a_,b_) for a_,b_ in mix.most_common()),
                 len(verts), len(cautions), 100*r["repeat"]))
        print("  features in order:")
        for p in plist:
            print("      %-9s %s" % (p["fclass"], p["name"]))
        print("  trail by trail (legs over 0.5 mi):")
        for lg in legs:
            if lg["miles"] < 0.5: continue
            extra = ("   " + ", ".join(q["name"] for q in lg["pois"])) if lg["pois"] else ""
            print("    %5.1f-%5.1f  %-32s %4.1f mi%s"
                  % (lg["from_mi"], lg["to_mi"], lg["trail"][:32], lg["miles"], extra))
        if cautions:
            print("  CAUTION unconfirmed junctions: %d" % len(cautions))
            for c in cautions[:8]:
                print("    mile %5.1f  %.5f,%.5f  %.0f ft apart"
                      % (c["at_mi"], c["lat"], c["lon"], c["gap_ft"]))

        json.dump({"schemaVersion":1, "name":name, "createdAt":now, "updatedAt":now,
                   "method":"point", "vertices":verts},
                  open(os.path.join(OUT,"route_drafts","%s.json"%name),"w"))
        json.dump({"name":name, "generated":now,
                   "summary":{"total_miles":round(r["miles"],2),
                              "est_hours":[round(lo_h,2),round(hi_h,2)],
                              "speed_mph":list(SPEED_MPH), "vertices":len(verts),
                              "features":len(plist), "of_available":len(allp),
                              "feature_mix":dict(mix),
                              "unconfirmed_junctions":len(cautions),
                              "retrace_pct":round(100*r["repeat"],1)},
                   "legs":legs, "cautions":cautions, "features":plist},
                  open(os.path.join(OUT,"route_drafts","%s.highlights.json"%name),"w"),
                  indent=1)

        for p in plist:
            wpts[p["name"]] = (p["lat"], p["lon"],
                               "water" if p["fclass"] in WATER else "viewpoint",
                               "Drinking Water" if p["fclass"] in WATER else "Summit",
                               "%s" % p["fclass"])
        for c in cautions:
            wpts["CAUTION %.4f,%.4f" % (c["lat"], c["lon"])] = (
                c["lat"], c["lon"], "caution", "Danger Area",
                "Trails mapped %.0f ft apart - junction NOT confirmed. Zoom satellite."
                % c["gap_ft"])

    g = ['<?xml version="1.0" encoding="UTF-8"?>',
         '<gpx version="1.1" creator="GroupTrack route research" '
         'xmlns="http://www.topografix.com/GPX/1/1">',
         '<metadata><name>Suggested route waypoints</name></metadata>']
    for nm,(la,lo,ty,sy,de) in sorted(wpts.items()):
        g.append('<wpt lat="%.6f" lon="%.6f"><name>%s</name><type>%s</type>'
                 '<sym>%s</sym><desc>%s</desc></wpt>'
                 % (la,lo,esc(nm),esc(ty),esc(sy),esc(de)))
    g.append('</gpx>')
    gp = os.path.join(OUT,"grouptrack_suggested_waypoints_2026-08-22.gpx")
    open(gp,"w",encoding="utf-8").write("\n".join(g))

    print("\n"+"="*78)
    print("%d drafts + sidecars in %s\\route_drafts" % (len(picks), OUT))
    print("%s  (%d waypoints)" % (os.path.basename(gp), len(wpts)))
    print("\n1. WAYPOINTS FIRST:")
    print('   MSYS_NO_PATHCONV=1 adb -s 24039703201775 push "%s" /sdcard/Download/'
          % gp.replace("\\","/"))
    print("   then import via Map Features -> IMPORT TRACKS, WAYPOINTS & ROUTES")
    print("\n2. THEN THE DRAFTS:")
    print('   MSYS_NO_PATHCONV=1 adb -s 24039703201775 push "%s/route_drafts/." '
          '/sdcard/Documents/GroupTrack/route_drafts/' % OUT.replace("\\","/"))
    print("="*78)
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
