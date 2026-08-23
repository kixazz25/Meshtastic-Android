#!/usr/bin/env python3
"""
nav_pass1b_toroweap_2026-08-22.py

PASS 1b -- can trail data actually reach Toroweap?

Pass 1 reported only TWO trail ends within 5 mi of Toroweap, against 571 at
Bar 10. That looks like the south end is unreachable -- but the test was too
crude to conclude it. It measured segment ENDPOINTS only, so a long trail
running straight THROUGH Toroweap would not register if both its ends are
far away. The 7.7 mi unnamed segment sitting at 4.44 mi is exactly that shape.

This pass tests EVERY VERTEX of every trail in the area, which is what actually
answers the question.

Also re-counts names properly. Pass 1 said "100% named" because 3,334 trails
carry the literal string "Not Named" -- a value, not a blank. Real figure is
about 23%.

READ ONLY. Opens the DB read-only and writes nothing.
"""

import sqlite3, math, os, sys, re
from collections import Counter

DB = r"D:\nav_test\grouptrack_spatial.db"

TOROWEAP = (36.20, -113.07)
BAR10    = (36.43, -113.35)

# strings that mean "no name" even though the column is populated
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
    return n and str(n).strip().lower() not in NONAMES


def main():
    if not os.path.isfile(DB):
        print("NOT FOUND: %s" % DB); return 1
    con = sqlite3.connect("file:%s?mode=ro" % DB.replace("\\", "/"), uri=True)
    cur = con.cursor()

    # ── honest name count over the whole corridor ───────────────────
    LAT_S, LAT_N, LON_W, LON_E = 35.95, 36.65, -113.80, -112.80
    rows = list(cur.execute(
        "SELECT trail_id,name,geometry FROM trails "
        "WHERE min_lat<=? AND max_lat>=? AND min_lon<=? AND max_lon>=?",
        (LAT_N, LAT_S, LON_E, LON_W)))
    named = [r for r in rows if real_name(r[1])]
    print("=" * 68)
    print("NAME COUNT, corrected")
    print("=" * 68)
    print("  trails in corridor : %d" % len(rows))
    print("  genuinely named    : %d  (%.1f%%)" % (len(named), 100.0*len(named)/max(len(rows),1)))
    print("  placeholder names  : %d" % (len(rows) - len(named)))
    ph = Counter(str(r[1]).strip() for r in rows if not real_name(r[1]))
    for k, n in ph.most_common(5):
        print("      %-22s %d" % (repr(k)[:22], n))

    # ── every vertex, not just endpoints ────────────────────────────
    for label, anchor in (("TOROWEAP", TOROWEAP), ("BAR 10", BAR10)):
        print("\n" + "=" * 68)
        print("%s  %s -- nearest APPROACH of any trail (all vertices)" % (label, anchor))
        print("=" * 68)
        hits = []
        for tid, nm, wkt in rows:
            pts = wkt_points(wkt)
            if not pts:
                continue
            best = min(hav(anchor, p) for p in pts)
            if best < 8.0:
                # is the anchor mid-segment or at an end?
                ends = min(hav(anchor, pts[0]), hav(anchor, pts[-1]))
                L = sum(hav(pts[i], pts[i+1]) for i in range(len(pts)-1))
                hits.append((best, ends, nm, L, tid))
        hits.sort()
        print("  trails passing within 8 mi: %d" % len(hits))
        print("  within 1 mi: %d    within 2 mi: %d    within 5 mi: %d"
              % (sum(1 for h in hits if h[0] < 1),
                 sum(1 for h in hits if h[0] < 2),
                 sum(1 for h in hits if h[0] < 5)))
        print("\n  %-8s %-8s %-38s %-7s" % ("closest", "nearest", "name", "length"))
        print("  %-8s %-8s %-38s %-7s" % ("approach", "end", "", "mi"))
        for best, ends, nm, L, tid in hits[:20]:
            mid = "  <- passes through" if ends - best > 1.0 else ""
            print("  %6.2f   %6.2f   %-38s %6.1f%s"
                  % (best, ends, (nm if real_name(nm) else "(unnamed)")[:38], L, mid))

        # named trails only -- these are the describable ones
        nh = [h for h in hits if real_name(h[2])]
        print("\n  NAMED trails within 8 mi: %d" % len(nh))
        seen = set()
        for best, ends, nm, L, tid in nh:
            k = str(nm).strip()
            if k in seen:
                continue
            seen.add(k)
            print("    %6.2f mi   %s" % (best, k))
            if len(seen) >= 15:
                break

    # ── what is the big unnamed 7.7 mi segment near Toroweap? ───────
    print("\n" + "=" * 68)
    print("THE LONG SEGMENTS NEAR TOROWEAP (over 3 mi, within 10 mi)")
    print("=" * 68)
    for tid, nm, wkt in rows:
        pts = wkt_points(wkt)
        if len(pts) < 2:
            continue
        L = sum(hav(pts[i], pts[i+1]) for i in range(len(pts)-1))
        if L < 3.0:
            continue
        best = min(hav(TOROWEAP, p) for p in pts)
        if best > 10.0:
            continue
        print("\n  %-34s %.1f mi   closest %.2f mi" %
              ((nm if real_name(nm) else "(unnamed)")[:34], L, best))
        print("    starts %.4f,%.4f   ends %.4f,%.4f"
              % (pts[0][0], pts[0][1], pts[-1][0], pts[-1][1]))

    con.close()
    print("\nDONE -- paste the whole output back.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
