#!/usr/bin/env python3
"""
update_geofabrik_states.py
Scrapes the Geofabrik index and builds geofabrik_states.json
with accurate download URLs for every US state/territory.

Run periodically or before a release to keep URLs current.
Outputs to the same directory as this script.

Usage:
    python update_geofabrik_states.py
    python update_geofabrik_states.py --out /path/to/assets/geofabrik_states.json
"""

import json, sys, os, urllib.request, re
from datetime import date

INDEX_URL = "https://download.geofabrik.de/index-v1.json"

# Known US state bboxes (USGS boundaries, [west, south, east, north])
# Used as fallback if the index doesn't provide geometry
STATE_BBOXES = {
    "alabama": [-88.47, 30.22, -84.89, 35.01],
    "alaska": [-179.15, 51.21, -129.98, 71.39],
    "arizona": [-114.82, 31.33, -109.04, 37.00],
    "arkansas": [-94.62, 33.00, -89.64, 36.50],
    "colorado": [-109.06, 36.99, -102.04, 41.00],
    "connecticut": [-73.73, 40.99, -71.79, 42.05],
    "delaware": [-75.79, 38.45, -75.05, 39.84],
    "district-of-columbia": [-77.12, 38.79, -76.91, 38.99],
    "florida": [-87.63, 24.52, -80.03, 31.00],
    "georgia": [-85.61, 30.36, -80.84, 35.00],
    "hawaii": [-160.24, 18.91, -154.81, 22.24],
    "idaho": [-117.24, 41.99, -111.04, 49.00],
    "illinois": [-91.51, 36.97, -87.50, 42.51],
    "indiana": [-88.10, 37.77, -84.78, 41.76],
    "iowa": [-96.64, 40.38, -90.14, 43.50],
    "kansas": [-102.05, 36.99, -94.59, 40.00],
    "kentucky": [-89.57, 36.50, -81.96, 39.15],
    "louisiana": [-94.04, 28.93, -88.82, 33.02],
    "maine": [-71.08, 43.06, -66.95, 47.46],
    "maryland": [-79.49, 37.91, -75.05, 39.72],
    "massachusetts": [-73.51, 41.24, -69.93, 42.89],
    "michigan": [-90.42, 41.70, -82.12, 48.31],
    "minnesota": [-97.24, 43.50, -89.49, 49.38],
    "mississippi": [-91.66, 30.17, -88.10, 34.99],
    "missouri": [-95.77, 36.00, -89.10, 40.61],
    "montana": [-116.05, 44.36, -104.04, 49.00],
    "nebraska": [-104.05, 40.00, -95.31, 43.00],
    "nevada": [-120.01, 35.00, -114.04, 42.00],
    "new-hampshire": [-72.56, 42.70, -70.70, 45.31],
    "new-jersey": [-75.56, 38.93, -73.89, 41.36],
    "new-mexico": [-109.05, 31.33, -103.00, 37.00],
    "new-york": [-79.76, 40.50, -71.86, 45.02],
    "north-carolina": [-84.32, 33.84, -75.46, 36.59],
    "north-dakota": [-104.05, 45.94, -96.55, 49.00],
    "ohio": [-84.82, 38.40, -80.52, 42.32],
    "oklahoma": [-103.00, 33.62, -94.43, 37.00],
    "oregon": [-124.57, 41.99, -116.46, 46.29],
    "pennsylvania": [-80.52, 39.72, -74.69, 42.27],
    "puerto-rico": [-67.94, 17.88, -65.22, 18.52],
    "rhode-island": [-71.86, 41.15, -71.12, 42.02],
    "south-carolina": [-83.35, 32.03, -78.54, 35.22],
    "south-dakota": [-104.06, 42.48, -96.44, 45.95],
    "tennessee": [-90.31, 34.98, -81.65, 36.68],
    "texas": [-106.65, 25.84, -93.51, 36.50],
    "us-virgin-islands": [-65.08, 17.67, -64.56, 18.41],
    "utah": [-114.05, 37.00, -109.04, 42.00],
    "vermont": [-73.44, 42.73, -71.46, 45.02],
    "virginia": [-83.68, 36.54, -75.24, 39.47],
    "washington": [-124.73, 45.54, -116.92, 49.00],
    "west-virginia": [-82.64, 37.20, -77.72, 40.64],
    "wisconsin": [-92.89, 42.49, -86.25, 47.31],
    "wyoming": [-111.06, 40.99, -104.05, 45.01],
    # California sub-regions
    "california/norcal": [-124.41, 35.00, -114.13, 42.01],
    "california/socal": [-121.00, 32.53, -114.13, 35.00],
}

# Display names (override slug-derived names)
DISPLAY_NAMES = {
    "district-of-columbia": "District of Columbia",
    "new-hampshire": "New Hampshire",
    "new-jersey": "New Jersey",
    "new-mexico": "New Mexico",
    "new-york": "New York",
    "north-carolina": "North Carolina",
    "north-dakota": "North Dakota",
    "puerto-rico": "Puerto Rico",
    "rhode-island": "Rhode Island",
    "south-carolina": "South Carolina",
    "south-dakota": "South Dakota",
    "us-virgin-islands": "US Virgin Islands",
    "west-virginia": "West Virginia",
    "california/norcal": "California North",
    "california/socal": "California South",
}

# California sub-region metadata
CALIFORNIA_META = {
    "california/norcal": {
        "description": "San Luis Obispo area and north",
        "parent_state": "California",
    },
    "california/socal": {
        "description": "Santa Barbara / Bakersfield area and south",
        "parent_state": "California",
    },
}

BASE_URL = "https://download.geofabrik.de/north-america/us"


def slug_to_name(slug):
    """Convert slug to display name."""
    if slug in DISPLAY_NAMES:
        return DISPLAY_NAMES[slug]
    # "south-dakota" -> "South Dakota"
    return slug.replace("-", " ").title()


def fetch_index():
    """Download the Geofabrik index and extract US state entries."""
    print("Fetching %s ..." % INDEX_URL)
    req = urllib.request.Request(INDEX_URL, headers={"User-Agent": "GroupTrack/1.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read())
    
    us_regions = {}
    for f in data.get("features", []):
        props = f.get("properties", {})
        pid = props.get("id", "")
        # Match us/STATE or us/california/norcal etc
        m = re.match(r"^us/(.+)$", pid)
        if not m:
            continue
        slug = m.group(1)
        
        # Get URLs
        urls = props.get("urls", {})
        gpkg_url = urls.get("gpkg", None)
        
        # Get bbox from geometry if available
        geom = f.get("geometry", {})
        bbox = None
        if geom.get("type") in ("Polygon", "MultiPolygon"):
            if geom["type"] == "Polygon":
                coords = geom["coordinates"][0]
            else:
                coords = [c for ring in geom["coordinates"] for c in ring[0]]
            lats = [c[1] for c in coords]
            lons = [c[0] for c in coords]
            bbox = [round(min(lons), 3), round(min(lats), 3),
                    round(max(lons), 3), round(max(lats), 3)]
        
        us_regions[slug] = {
            "gpkg_url": gpkg_url,
            "bbox_from_index": bbox,
            "parent": props.get("parent", ""),
        }
    
    return us_regions


def build_asset(index_data=None):
    """Build the geofabrik_states.json asset."""
    states = []
    
    for slug, bbox in sorted(STATE_BBOXES.items()):
        entry = {
            "name": slug_to_name(slug),
            "slug": slug,
        }
        
        # Use index bbox if available, otherwise use hardcoded
        if index_data and slug in index_data and index_data[slug]["bbox_from_index"]:
            entry["bbox"] = index_data[slug]["bbox_from_index"]
            print("  %s: bbox from index" % slug)
        else:
            entry["bbox"] = bbox
            print("  %s: bbox from hardcoded" % slug)
        
        # Build GPKG URL
        if index_data and slug in index_data and index_data[slug]["gpkg_url"]:
            entry["gpkg_url"] = index_data[slug]["gpkg_url"]
            print("  %s: gpkg_url from index: %s" % (slug, entry["gpkg_url"]))
        else:
            # Derive from template
            entry["gpkg_url"] = "%s/%s-latest-free.gpkg.zip" % (BASE_URL, slug)
            print("  %s: gpkg_url from template" % slug)
        
        # Add California metadata
        if slug in CALIFORNIA_META:
            entry.update(CALIFORNIA_META[slug])
        
        states.append(entry)
    
    asset = {
        "version": 1,
        "generated": str(date.today()),
        "source": "https://download.geofabrik.de/north-america/us.html",
        "updater": "update_geofabrik_states.py",
        "base_url": BASE_URL,
        "states": states,
    }
    
    return asset


def main():
    out_path = "geofabrik_states.json"
    if len(sys.argv) > 2 and sys.argv[1] == "--out":
        out_path = sys.argv[2]
    
    # Try to fetch live index for accurate URLs
    index_data = None
    try:
        index_data = fetch_index()
        print("Fetched %d US regions from index" % len(index_data))
    except Exception as e:
        print("WARNING: Could not fetch index (%s)" % e)
        print("         Using hardcoded URLs — run again with internet to verify")
    
    asset = build_asset(index_data)
    
    with open(out_path, "w") as f:
        json.dump(asset, f, indent=2)
    
    print("\nWrote %s" % out_path)
    print("  %d entries" % len(asset["states"]))
    print("  generated: %s" % asset["generated"])
    
    # Verify all have gpkg_url
    missing = [s["name"] for s in asset["states"] if not s.get("gpkg_url")]
    if missing:
        print("  WARNING: missing gpkg_url for: %s" % ", ".join(missing))
    else:
        print("  All entries have gpkg_url ✓")


if __name__ == "__main__":
    main()
