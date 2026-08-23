#!/usr/bin/env python3
"""
nav_pass4c_toroweap_2026-08-22.py

PASS 4 -- FIXED-POINT INCLUSIVE.  Bar 10 -> Toroweap -> Bar 10.

TWO CORRECTIONS FROM 3d, both structural.

1. RANK, DO NOT PRE-CONSTRAIN.  Fred: "if we used typical navigation on its head
   and ranked the explored tracks by total distance and highest score we could
   then set the distance/time cutoff to that list."
   3d searched INSIDE a 55-70 band, and with a 53.7 mi fixed tether that band
   strangled the search before it could find anything. This explores across a
   wide distance range, scores everything, and RANKS. The rider's time budget
   becomes a cutoff applied to the ranked list, not a constraint that shapes
   the search.

2. PENALISE, DO NOT FILTER.  3d generated 4,000 candidates, ranked them, then
   filtered for distinctness -- which on a corridor with a fixed spine leaves
   exactly one. Navigation apps do the opposite: find the best, PENALISE the
   edges it used, re-solve. The second route is then the best route that avoids
   the first. That is the difference between "one answer and 3,999 rejects" and
   "four alternatives."

OBJECTIVE IS SCORE, NOT DISTANCE.  Miles are a budget, never a cost. Variety is
weighted: a second class of feature beats another of the same.

OUT: D:\\nav_test\\out\\route_drafts\\<SET_NAME> N.json  + .highlights.json
     D:\\nav_test\\out\\grouptrack_suggested_waypoints_2026-08-22.gpx
"""

import sqlite3, math, os, re, heapq, json, random, datetime
from collections import defaultdict, Counter, deque

DB  = r"D:\nav_test\grouptrack_spatial.db"
OUT = r"D:\nav_test\out"
SET_NAME = "Toroweap"          # -> "Toroweap 1".."Toroweap 4"

BAR10, TOROWEAP = (36.43, -113.35), (36.20, -113.07)
EXPLORE_MAX = 80.0             # ceiling (Fred 08-22: lowered from 200)
N_ROUTES    = 4
TRIES       = 2500
PENALTY     = 2.2              # multiplier on edges already used by a chosen route
REPORT_CUTS = [60, 65, 70, 75, 80]

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
    os.makedirs(os.path.join(OUT, "route_drafts"), exist_ok=True)
    con = sqlite3.connect("file:%s?mode=ro" % DB.replace("\\","/"), uri=True)
    cur = con.cursor()
    print("="*78)
    print("PASS 4 -- EXPLORE THEN RANK   %s   explore to %.0f mi"
          % (SET_NAME, EXPLORE_MAX))
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
    print("network %d edges / %d nodes;  %d POIs at %d nodes"
          % (len(edges), len(adj), len(allp), len(poi_at)))

    terms = [n_bar, n_tor] + [k for k in poi_at if k not in (n_bar, n_tor)]

    def dij(s, pen):
        dist={s:0.0}; prev={}; pq=[(0.0,s)]
        while pq:
            d0,x = heapq.heappop(pq)
            if d0 > dist.get(x,1e18): continue
            for y,ei in adj[x]:
                w = edges[ei][2] * pen.get(ei, 1.0)
                nd = d0 + w
                if nd < dist.get(y,1e18):
                    dist[y]=nd; prev[y]=(x,ei); heapq.heappush(pq,(nd,y))
        return dist, prev

    def build_tables(pen):
        D, P = {}, {}
        for t in terms: D[t], P[t] = dij(t, pen)
        return D, P

    def pe(P, s, t):
        out, x = [], t
        while x != s:
            if x not in P[s]: return None
            px, ei = P[s][x]; out.append(ei); x = px
        out.reverse(); return out

    def setscore(ks):
        seenn, byc, v = set(), Counter(), 0.0
        for k in ks:
            for p in poi_at[k]:
                if p["name"] in seenn: continue
                seenn.add(p["name"]); byc[p["fclass"]] += 1
                b = 3.0 if p["fclass"] in VIEW else 2.0 if p["fclass"] in WATER else 1.0
                v += b / (1.0 + 0.22*(byc[p["fclass"]]-1))
        return v

    def explore(D, P, pen):
        """Randomised greedy, NO mileage band -- explore to EXPLORE_MAX and let
        ranking sort it out afterwards."""
        found = []
        pn = [k for k in poi_at if D[n_bar].get(k) is not None]
        for _ in range(TRIES):
            # each restart gets its OWN target, so the pool spans the range.
            # without this the greedy always runs to the ceiling and every route
            # comes out the same length -- which is why the ranked table read
            # "none" at every cutoff below the max.
            target = random.uniform(EXPLORE_MAX * 0.62, EXPLORE_MAX)
            chosen, cur_n, mi, phase, endn = [], n_bar, 0.0, 0, n_tor
            left = set(pn)
            while True:
                cands = []
                for k in left:
                    s1 = D[cur_n].get(k); back = D[k].get(endn)
                    if s1 is None or back is None: continue
                    if mi + s1 + back > target * (0.55 if phase == 0 else 1.0):
                        continue
                    g = setscore(chosen + [k]) - setscore(chosen)
                    if g <= 0: continue
                    cands.append((g / max(s1, 0.35), k, s1))
                if not cands:
                    if phase == 0:
                        st = D[cur_n].get(n_tor)
                        if st is None: break
                        mi += st; cur_n = n_tor; phase, endn = 1, n_bar; continue
                    break
                cands.sort(reverse=True)
                _, k, s1 = cands[min(int(random.random()**2 * 5), len(cands)-1)]
                mi += s1; cur_n = k; chosen.append(k); left.discard(k)
            if phase == 0:
                st = D[cur_n].get(n_tor)
                if st is None: continue
                mi += st; cur_n = n_tor
            fin = D[cur_n].get(n_bar)
            if fin is None: continue
            seq = [n_bar] + chosen[:]
            if n_tor not in seq: seq.append(n_tor)
            seq.append(n_bar)
            eids, ok = [], True
            for a, b in zip(seq, seq[1:]):
                q = pe(P, a, b)
                if q is None: ok = False; break
                eids.extend(q)
            if not ok: continue
            real_mi = sum(edges[e][2] for e in eids)
            if real_mi > EXPLORE_MAX: continue
            found.append({"seq": seq, "eids": eids, "eset": set(eids),
                          "miles": real_mi, "score": setscore(chosen),
                          "chosen": chosen})
        return found

    print("\nexploring (no mileage band)...")
    pen = {}
    D, P = build_tables(pen)
    pool = explore(D, P, pen)
    print("routes explored: %d" % len(pool))
    if not pool:
        print("nothing found"); return 3

    # ── the ranked table: what distance BUYS ────────────────────────
    print("\n" + "="*78)
    print("RANKED -- best score available at each distance cutoff")
    print("="*78)
    print("%-10s %10s %8s %8s   %s" % ("cutoff", "miles", "score", "feats", "mix"))
    for cutm in REPORT_CUTS:
        sub = [r for r in pool if r["miles"] <= cutm]
        if not sub: 
            print("%-10s %10s" % ("<= %d mi" % cutm, "none")); continue
        b = max(sub, key=lambda r: r["score"])
        names = {p["name"]: p["fclass"] for k in b["chosen"] for p in poi_at[k]}
        mix = Counter(names.values())
        print("%-10s %9.1f %8.1f %8d   %s"
              % ("<= %d mi" % cutm, b["miles"], b["score"], len(names),
                 ", ".join("%s %d" % (a_,b_) for a_,b_ in mix.most_common())))

    # ── four alternatives by iterative penalty ──────────────────────
    print("\n" + "="*78)
    print("FOUR ALTERNATIVES (each penalised away from the ones before)")
    print("="*78)
    picks = []
    for i in range(N_ROUTES):
        if i > 0:
            for e in picks[-1]["eset"]:
                pen[e] = pen.get(e, 1.0) * PENALTY
            D, P = build_tables(pen)
            pool = explore(D, P, pen)
            if not pool:
                print("  (no further alternative found)"); break
        # POSITIVE SCORE FLOOR (Fred 08-22): a route with no features is not a
        # suggestion. The iterative penalty eventually makes every scoring
        # corridor too expensive and the search returns the best REMAINING route,
        # which visits nothing -- Toroweap 3 and 4 both came back score 0.0.
        # Better to return two good routes than pad to four.
        cand = [r for r in pool
                if r["score"] > 0.0
                and all(len(r["eset"] & q["eset"])/max(len(r["eset"] | q["eset"]),1) < 0.75
                        for q in picks)]
        if not cand:
            print("  (no further alternative with a positive score)"); break
        b = max(cand, key=lambda r: r["score"])
        b["repeat"] = 1.0 - len(b["eset"])/max(len(b["eids"]),1)
        picks.append(b)
        print("  %s %d: %.1f mi, score %.1f" % (SET_NAME, i+1, b["miles"], b["score"]))

    # ── emit ────────────────────────────────────────────────────────
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    wpts = {}
    for idx, r in enumerate(picks, 1):
        verts, legs, caut = [], [], {}
        cum, prev_name, leg_start, leg_pois = 0.0, None, 0.0, []
        seenpoi, plist = set(), []
        for e in r["eids"]:
            a, b, L, nm, tid, seg = edges[e]
            s = seg
            if verts and hav((verts[-1]["lat"], verts[-1]["lon"]), s[0]) > \
                         hav((verts[-1]["lat"], verts[-1]["lon"]), s[-1]):
                s = s[::-1]
            for j, p in enumerate(s):
                if verts and j == 0: continue
                verts.append({"lat": round(p[0],6), "lon": round(p[1],6),
                              "lineId": tid, "lineType": "trail",
                              "segmentIndex": -1, "t": 0.0, "snapped": True})
            lbl = nm or "unnamed track"
            if lbl != prev_name:
                if prev_name is not None:
                    legs.append({"trail": prev_name, "from_mi": round(leg_start,2),
                                 "to_mi": round(cum,2),
                                 "miles": round(cum-leg_start,2), "pois": leg_pois})
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
        name = "%s %d" % (SET_NAME, idx)
        mix = Counter(p["fclass"] for p in plist)

        print("\n" + "-"*78)
        print("%s      %.1f miles      %.1f-%.1f hours      score %.1f"
              % (name, r["miles"], lo_h, hi_h, r["score"]))
        print("-"*78)
        print("  %d of %d features (%s)   %d vertices   %d cautions   %.0f%% retrace"
              % (len(plist), len(allp),
                 ", ".join("%s %d" % (a_,b_) for a_,b_ in mix.most_common()),
                 len(verts), len(cautions), 100*r["repeat"]))
        for p in plist:
            print("      %-9s %-28s mile %.1f" %
                  (p["fclass"], p["name"][:28],
                   next((q["at_mi"] for lg in legs for q in lg["pois"]
                         if q["name"] == p["name"]), 0)))
        print("  named trail legs over 1 mi:")
        for lg in legs:
            if lg["miles"] < 1.0 or lg["trail"] == "unnamed track": continue
            print("    %5.1f-%5.1f  %-32s %4.1f mi"
                  % (lg["from_mi"], lg["to_mi"], lg["trail"][:32], lg["miles"]))
        print("  unconfirmed junctions: %d" % len(cautions))

        json.dump({"schemaVersion":1, "name":name, "createdAt":now, "updatedAt":now,
                   "method":"point", "vertices":verts},
                  open(os.path.join(OUT,"route_drafts","%s.json"%name),"w"))
        json.dump({"name":name, "generated":now,
                   "summary":{"total_miles":round(r["miles"],2),
                              "est_hours":[round(lo_h,2), round(hi_h,2)],
                              "speed_mph":list(SPEED_MPH), "score":round(r["score"],2),
                              "vertices":len(verts), "features":len(plist),
                              "of_available":len(allp), "feature_mix":dict(mix),
                              "unconfirmed_junctions":len(cautions),
                              "retrace_pct":round(100*r["repeat"],1)},
                   "legs":legs, "cautions":cautions, "features":plist},
                  open(os.path.join(OUT,"route_drafts","%s.highlights.json"%name),"w"),
                  indent=1)
        for p in plist:
            wpts[p["name"]] = (p["lat"], p["lon"],
                               "water" if p["fclass"] in WATER else "viewpoint",
                               "Drinking Water" if p["fclass"] in WATER else "Summit",
                               p["fclass"])
        for c in cautions:
            wpts["CAUTION %.4f,%.4f" % (c["lat"], c["lon"])] = (
                c["lat"], c["lon"], "caution", "Danger Area",
                "Trails mapped %.0f ft apart - junction NOT confirmed. Zoom satellite."
                % c["gap_ft"])

    g = ['<?xml version="1.0" encoding="UTF-8"?>',
         '<gpx version="1.1" creator="GroupTrack route research" '
         'xmlns="http://www.topografix.com/GPX/1/1">',
         '<metadata><name>%s waypoints</name></metadata>' % SET_NAME]
    for nm,(la,lo,ty,sy,de) in sorted(wpts.items()):
        g.append('<wpt lat="%.6f" lon="%.6f"><name>%s</name><type>%s</type>'
                 '<sym>%s</sym><desc>%s</desc></wpt>'
                 % (la,lo,esc(nm),esc(ty),esc(sy),esc(de)))
    g.append('</gpx>')
    gp = os.path.join(OUT, "%s_waypoints_2026-08-22.gpx" % SET_NAME.lower().replace(" ","_"))
    open(gp,"w",encoding="utf-8").write("\n".join(g))

    print("\n" + "="*78)
    print("%d drafts + sidecars -> %s\\route_drafts" % (len(picks), OUT))
    print("%s  (%d waypoints)" % (os.path.basename(gp), len(wpts)))
    print("\n1. WAYPOINTS FIRST:")
    print('   MSYS_NO_PATHCONV=1 adb -s 24039703201775 push "%s" /sdcard/Download/'
          % gp.replace("\\","/"))
    print("2. THEN DRAFTS:")
    print('   MSYS_NO_PATHCONV=1 adb -s 24039703201775 push "%s/route_drafts/." '
          '/sdcard/Documents/GroupTrack/route_drafts/' % OUT.replace("\\","/"))
    print("="*78)
    con.close()
    return 0


if __name__ == "__main__":
    main()
