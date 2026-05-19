#!/usr/bin/env python3
"""
scan_trail_sources.py — GroupTrack reusable trail source scanner

Reads trail_sources.json from app assets, queries each source for:
  - Total record count
  - Actual data extent (bounding box in WGS84)
  - Distinct trail type/use values
  - Categorizes into: OHV | Hike+Bike | Hike | Bike

Updates trail_sources.json with scan results.
Saves detailed report to tools/trail_scan_report.json.

Usage:
  cd ~/Meshtastic-Android
  python3 tools/scan_trail_sources.py

Rerun anytime to refresh counts and extents.
"""
import json, urllib.request, ssl, time, os, sys

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET = os.path.join(PROJECT, "app", "src", "main", "assets", "trail_sources.json")
REPORT = os.path.join(PROJECT, "tools", "trail_scan_report.json")

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

def fetch(url, label=""):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "GroupTrack/2.5"})
        resp = urllib.request.urlopen(req, timeout=45, context=ctx)
        data = json.loads(resp.read().decode("utf-8"))
        if "error" in data:
            print(f"    API Error: {data['error'].get('message', str(data['error']))}")
            return None
        return data
    except Exception as e:
        print(f"    FETCH Error ({label}): {type(e).__name__}: {e}")
        return None

def get_base(query_url):
    """Strip /query from end to get base layer URL"""
    return query_url.rsplit("/query", 1)[0]

def query_count(query_url):
    url = f"{query_url}?where=1%3D1&returnCountOnly=true&f=json"
    data = fetch(url, "count")
    return data.get("count") if data else None

def query_extent(query_url):
    url = f"{query_url}?where=1%3D1&returnExtentOnly=true&outSR=4326&f=json"
    data = fetch(url, "extent")
    if data and "extent" in data:
        ext = data["extent"]
        return {
            "n": round(ext.get("ymax", 0), 4),
            "s": round(ext.get("ymin", 0), 4),
            "e": round(ext.get("xmax", 0), 4),
            "w": round(ext.get("xmin", 0), 4)
        }
    return None

def query_distinct(query_url, field):
    url = f"{query_url}?where=1%3D1&outFields={field}&returnDistinctValues=true&returnGeometry=false&f=json&resultRecordCount=200"
    data = fetch(url, f"distinct:{field}")
    if data and "features" in data:
        vals = set()
        for feat in data["features"]:
            v = feat.get("attributes", {}).get(field)
            if v is not None and str(v).strip():
                vals.add(str(v).strip())
        return sorted(vals)
    return None

def categorize(type_vals, use_vals):
    cats = {"OHV": [], "Hike+Bike": [], "Hike": [], "Bike": [], "Other": []}
    for v in set((type_vals or []) + (use_vals or [])):
        vl = v.lower()
        if any(k in vl for k in ["motor", "ohv", "atv", "utv", "4wd", "4x4", "vehicle", "motorcycle", "snowmobile"]):
            cats["OHV"].append(v)
        elif any(k in vl for k in ["hik", "pedestrian", "foot", "walk"]) and any(k in vl for k in ["bik", "cycl"]):
            cats["Hike+Bike"].append(v)
        elif any(k in vl for k in ["hik", "pedestrian", "foot", "walk"]):
            cats["Hike"].append(v)
        elif any(k in vl for k in ["bik", "cycl"]):
            cats["Bike"].append(v)
        else:
            cats["Other"].append(v)
    return cats

# ── Main ──────────────────────────────────────────────

if not os.path.exists(ASSET):
    print(f"ERROR: {ASSET} not found. Run patch_v25_trail_tools_v1.py first.")
    sys.exit(1)

with open(ASSET, "r", encoding="utf-8") as f:
    catalog = json.load(f)

print("=" * 65)
print(f"GroupTrack Trail Source Scanner")
print(f"Asset: {ASSET}")
print(f"Sources: {len(catalog['sources'])}")
print("=" * 65)

report = {"scanned": time.strftime("%Y-%m-%d %H:%M"), "sources": {}}

for src in catalog["sources"]:
    sid = src["id"]
    qurl = src["query_url"]
    fields = src.get("fields", {})
    type_field = fields.get("type", "")
    use_field = fields.get("use", "")

    print(f"\n{'─' * 65}")
    print(f" {src['name']} ({sid})")
    print(f"{'─' * 65}")

    scan = {"timestamp": time.strftime("%Y-%m-%d %H:%M"), "status": "ok"}

    # Count
    print(f"  [1/3] Count...")
    count = query_count(qurl)
    scan["total_count"] = count
    print(f"    {'%s records' % f'{count:,}' if count else 'FAILED'}")
    time.sleep(1)

    # Extent
    print(f"  [2/3] Extent...")
    extent = query_extent(qurl)
    scan["extent"] = extent
    if extent:
        print(f"    N:{extent['n']}  S:{extent['s']}  E:{extent['e']}  W:{extent['w']}")
    else:
        print(f"    FAILED")
        scan["status"] = "extent_failed"
    time.sleep(1)

    # Types
    print(f"  [3/3] Types...")
    type_vals = query_distinct(qurl, type_field) if type_field else None
    use_vals = query_distinct(qurl, use_field) if use_field else None
    scan["type_values"] = type_vals
    scan["use_values"] = use_vals
    if type_vals:
        print(f"    {type_field}: {type_vals[:8]}{'...' if len(type_vals)>8 else ''}")
    if use_vals:
        print(f"    {use_field}: {use_vals[:8]}{'...' if len(use_vals)>8 else ''}")

    cats = categorize(type_vals, use_vals)
    scan["categories"] = cats
    for cat, vals in cats.items():
        if vals:
            print(f"    -> {cat}: {vals[:5]}")

    time.sleep(1)

    # Update source in catalog
    src["scan"] = scan
    if extent:
        src["boundary"] = extent

    report["sources"][sid] = scan

# Save updated catalog
with open(ASSET, "w", encoding="utf-8") as f:
    json.dump(catalog, f, indent=2, ensure_ascii=False)
print(f"\nUpdated: {ASSET}")

# Save report
with open(REPORT, "w", encoding="utf-8") as f:
    json.dump(report, f, indent=2, ensure_ascii=False)
print(f"Report: {REPORT}")

# Summary
print(f"\n{'=' * 65}")
print(f"{'Source':<30} {'Count':>8} {'N':>8} {'S':>8} {'E':>9} {'W':>9}")
print(f"{'─' * 74}")
for src in catalog["sources"]:
    s = src.get("scan", {})
    ct = f"{s['total_count']:,}" if s.get('total_count') else "ERR"
    ext = s.get("extent") or src.get("boundary")
    if ext:
        print(f"{src['name']:<30} {ct:>8} {ext['n']:>8} {ext['s']:>8} {ext['e']:>9} {ext['w']:>9}")
    else:
        print(f"{src['name']:<30} {ct:>8} {'--':>8} {'--':>8} {'--':>9} {'--':>9}")
print(f"{'=' * 65}")
