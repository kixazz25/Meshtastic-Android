#!/usr/bin/env python3
"""Add Leaflet.draw plugin and draw tool to convoy_map.html"""
import sys

TARGET = "app/src/main/assets/convoy_map.html"

with open(TARGET, "r", encoding="utf-8") as f:
    src = f.read()

# ── Change 1: Add Leaflet.draw CSS and JS after existing Leaflet includes ─────
OLD_HEAD = '  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>'
NEW_HEAD = (
    '  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>\n'
    '  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet.draw/1.0.4/leaflet.draw.css"/>\n'
    '  <script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet.draw/1.0.4/leaflet.draw.js"></script>'
)
if OLD_HEAD not in src:
    print("FAIL: Leaflet script tag not found")
    sys.exit(1)
src = src.replace(OLD_HEAD, NEW_HEAD, 1)
print("OK   Change 1: Leaflet.draw CSS and JS added")

# ── Change 2: Add draw layer and control after map init ──────────────────────
# Find: var map, tileLayer, markers
OLD_VARS = "    var map, tileLayer, markers = {}, deviceMarker = null;"
NEW_VARS = (
    "    var map, tileLayer, markers = {}, deviceMarker = null;\n"
    "    var drawnItems = null;\n"
    "    var drawControl = null;"
)
if OLD_VARS not in src:
    print("FAIL: var declarations not found")
    sys.exit(1)
src = src.replace(OLD_VARS, NEW_VARS, 1)
print("OK   Change 2: draw layer variables added")

# ── Change 3: Add draw tool functions ─────────────────────────────────────────
# Insert after setTileUrl function — find the closing brace of setTileUrl
OLD_SETTILE = (
    "    function setTileUrl(url) {\n"
    "      if (tileLayer) map.removeLayer(tileLayer);\n"
    "      tileLayer = L.tileLayer(url, {maxZoom:19});\n"
    "      tileLayer.addTo(map);\n"
    "    }"
)
NEW_SETTILE = (
    "    function setTileUrl(url) {\n"
    "      if (tileLayer) map.removeLayer(tileLayer);\n"
    "      tileLayer = L.tileLayer(url, {maxZoom:19});\n"
    "      tileLayer.addTo(map);\n"
    "    }\n"
    "\n"
    "    // ── Draw tool functions ────────────────────────────────────────────\n"
    "    function initDrawTool() {\n"
    "      drawnItems = new L.FeatureGroup();\n"
    "      map.addLayer(drawnItems);\n"
    "      drawControl = new L.Draw.Rectangle(map, {\n"
    "        shapeOptions: { color: '#FFB74D', weight: 2, fillOpacity: 0.15 }\n"
    "      });\n"
    "      map.on(L.Draw.Event.CREATED, function(e) {\n"
    "        drawnItems.clearLayers();\n"
    "        drawnItems.addLayer(e.layer);\n"
    "        var b = e.layer.getBounds();\n"
    "        if (typeof Android !== 'undefined' && Android.onAreaSelected) {\n"
    "          Android.onAreaSelected(b.getNorth(), b.getSouth(), b.getEast(), b.getWest());\n"
    "        }\n"
    "      });\n"
    "    }\n"
    "\n"
    "    function activateDrawMode() {\n"
    "      if (drawnItems) drawnItems.clearLayers();\n"
    "      if (drawControl) drawControl.enable();\n"
    "    }\n"
    "\n"
    "    function cancelDrawMode() {\n"
    "      if (drawControl) drawControl.disable();\n"
    "      if (drawnItems) drawnItems.clearLayers();\n"
    "    }\n"
    "\n"
    "    function showDownloadArea(n, s, e, w) {\n"
    "      if (!drawnItems) return;\n"
    "      drawnItems.clearLayers();\n"
    "      var rect = L.rectangle([[s, w], [n, e]], {\n"
    "        color: '#27AE60', weight: 2, fillOpacity: 0.1, dashArray: '5,5'\n"
    "      });\n"
    "      drawnItems.addLayer(rect);\n"
    "    }"
)
if OLD_SETTILE not in src:
    print("FAIL: setTileUrl function not found")
    sys.exit(1)
src = src.replace(OLD_SETTILE, NEW_SETTILE, 1)
print("OK   Change 3: draw tool functions added")

# ── Change 4: Initialize draw tool after map init ────────────────────────────
# Find map initialization — look for L.map(
OLD_MAP_INIT = "    map = L.map('map',"
if OLD_MAP_INIT not in src:
    # Try alternate
    OLD_MAP_INIT = "    map = L.map('map'"
    
# Find the line after map init that calls setView or similar
# Insert initDrawTool() call after map is created
# Find map init block end by looking for first setView call after map creation
lines = src.split('\n')
map_init_line = None
for i, line in enumerate(lines):
    if "map = L.map(" in line:
        map_init_line = i
        break

if map_init_line is None:
    print("FAIL: map init not found")
    sys.exit(1)

# Find the next blank line or function call after map setup to insert initDrawTool
insert_after = map_init_line
for i in range(map_init_line, min(map_init_line + 20, len(lines))):
    if "setTileUrl(" in lines[i] or "tileLayer" in lines[i]:
        insert_after = i
        break

lines.insert(insert_after + 1, "    initDrawTool();")
src = '\n'.join(lines)
print(f"OK   Change 4: initDrawTool() call inserted after line {insert_after + 1}")

with open(TARGET, "w", encoding="utf-8") as f:
    f.write(src)

print("")
print("DONE — convoy_map.html patched with Leaflet.draw")
print("Run: ./gradlew assembleGoogleDebug")
