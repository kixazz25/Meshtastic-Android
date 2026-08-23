#!/usr/bin/env python3
"""
nav_sidecar_narrative_v2_2026-08-22.py

Rewrite the existing sidecars with something a RIDER can read.

Fred, 08-22: "nothing human readable here about feature and poi on this route.
needs to point to what's in this ride."

He is right -- the sidecar was machine-shaped. Nested from_mi / off_mi / fclass
is data for a popup that does not exist yet, not something you read to decide
whether to ride it.

This adds a `narrative` block ALONGSIDE the structured fields. Nothing is
removed: the structured data is what the popup will eventually render and what
the waypoints get created from; the narrative is what you read today.

⚠ NOTE, worth keeping: storing generated prose in a file is a compromise. If the
route is edited, the narrative describes a route the rider no longer has. The
right long-term answer is to GENERATE this at display time from the structured
fields. It is written here because the sidecar is currently the only consumer.

⚠ WAYPOINTS: to be created when the WIP is written, once that code exists (Fred
08-22). GPX waypoint import is currently not working, so the sidecar carries
name / lat / lon / type / mile for each feature and the app creates them later.

Reads and rewrites, in place:
    D:\\nav_test\\out\\route_drafts\\Toroweap 1.highlights.json
    D:\\nav_test\\out\\route_drafts\\Toroweap 2.highlights.json
"""

import json, os, glob
from collections import Counter

DIR = r"D:\nav_test\out\route_drafts"

WATER = {"spring"}
VIEW  = {"peak", "cliff", "volcano"}
PLAIN = {"peak": "summit", "cliff": "cliff", "volcano": "volcanic cone",
         "spring": "spring", "hamlet": "settlement", "locality": "locality"}


def ordinal_list(items):
    if not items:
        return ""
    if len(items) == 1:
        return items[0]
    return ", ".join(items[:-1]) + " and " + items[-1]


def narrate(d):
    s = d["summary"]
    feats = d.get("features", [])
    legs = d.get("legs", [])
    cauts = d.get("cautions", [])

    miles = s["total_miles"]
    lo, hi = s["est_hours"]
    mix = Counter(f["fclass"] for f in feats)

    # what is on this ride, in plain words
    bits = []
    for fc, n in mix.most_common():
        word = PLAIN.get(fc, fc)
        bits.append("%d %s%s" % (n, word, "s" if n > 1 and not word.endswith("s") else ""))
    headline = ("%.0f miles, roughly %.0f to %.0f hours of riding. "
                "%s along the way."
                % (miles, lo, hi, ordinal_list(bits).capitalize() if bits else "No named features"))

    # where each feature sits, by mile
    at_mi = {}
    for lg in legs:
        for p in lg.get("pois", []):
            at_mi[p["name"]] = p["at_mi"]
    seq = sorted(feats, key=lambda f: at_mi.get(f["name"], 0))
    # group features that sit at the same point -- "Oak Spring and Aspen Spring,
    # both at mile 28.7" reads better than two separate stops at one place.
    stops, i = [], 0
    while i < len(seq):
        f = seq[i]
        m = at_mi.get(f["name"], 0)
        same = [f]
        while i+1 < len(seq) and abs(at_mi.get(seq[i+1]["name"], 0) - m) < 0.15:
            i += 1; same.append(seq[i])
        off = f.get("off_mi", 0)
        near = "" if off <= 0.05 else (" (%.0f yards off the trail)" % (off * 1760))
        if len(same) == 1:
            stops.append("mile %.0f  %s -- %s%s"
                         % (m, f["name"], PLAIN.get(f["fclass"], f["fclass"]), near))
        else:
            stops.append("mile %.0f  %s -- together at the same spot"
                         % (m, ordinal_list([x["name"] for x in same])))
        i += 1

    # the named ground it uses
    named = {}
    for lg in legs:
        t = lg["trail"]
        if t and t != "unnamed track":
            named[t] = named.get(t, 0) + lg["miles"]
    named_txt = ordinal_list(["%s (%.0f mi)" % (k, v) for k, v in
                              sorted(named.items(), key=lambda x: -x[1])[:5]])

    unnamed_mi = sum(lg["miles"] for lg in legs if lg["trail"] == "unnamed track")
    pct_unnamed = 100.0 * unnamed_mi / max(miles, 1)

    warn = []
    r = s.get("retrace_pct", 0)
    if r > 40:
        warn.append("About %.0f%% of the ride retraces ground you have already "
                    "covered. Much of the way back is the way out -- the trail "
                    "network here does not close into a loop." % r)
    elif r > 20:
        warn.append("About %.0f%% of the ride doubles back on itself, usually a "
                    "short out-and-back to reach something worth seeing." % r)
    if cauts:
        worst = max(c["gap_ft"] for c in cauts)
        warn.append("%d junctions on this route are not confirmed in the map data. "
                    "The two trails are drawn between 10 and %.0f feet apart, which "
                    "usually means they meet -- but check the satellite view before "
                    "relying on the turn." % (len(cauts), worst))
    if pct_unnamed > 50:
        warn.append("%.0f%% of the distance is on tracks with no name in the data. "
                    "They are real tracks; nobody has recorded what they are called."
                    % pct_unnamed)

    return {
        "headline": headline,
        "what_you_will_see": [
            "%s%s" % (f["name"], " -- %s" % PLAIN.get(f["fclass"], f["fclass"]))
            for f in seq],
        "stops_in_order": stops,
        "ground_covered": ("Named trail: %s. The rest -- %.0f of %.0f miles -- is "
                           "unnamed track." % (named_txt or "none",
                                               unnamed_mi, miles))
                          if named else
                          ("All %.0f miles are on tracks with no name in the data."
                           % miles),
        "before_you_go": warn,
        "waypoints_to_create": [
            {"name": f["name"],
             "type": "water" if f["fclass"] in WATER else "viewpoint",
             "lat": f["lat"], "lon": f["lon"],
             "at_mile": at_mi.get(f["name"], 0)}
            for f in seq],
    }


def main():
    files = sorted(glob.glob(os.path.join(DIR, "*.highlights.json")))
    if not files:
        print("no sidecars found in %s" % DIR); return 1
    for path in files:
        d = json.load(open(path, encoding="utf-8"))
        d["narrative"] = narrate(d)
        json.dump(d, open(path, "w", encoding="utf-8"), indent=1)
        n = d["narrative"]
        print("=" * 74)
        print(os.path.basename(path))
        print("=" * 74)
        print("\n  %s\n" % n["headline"])
        print("  WHAT YOU WILL SEE")
        for x in n["what_you_will_see"]:
            print("    %s" % x)
        print("\n  IN ORDER")
        for x in n["stops_in_order"]:
            print("    %s" % x)
        print("\n  GROUND COVERED")
        print("    %s" % n["ground_covered"])
        if n["before_you_go"]:
            print("\n  BEFORE YOU GO")
            for x in n["before_you_go"]:
                print("    - %s" % x)
        print("\n  waypoints to create on promotion: %d"
              % len(n["waypoints_to_create"]))
        print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
