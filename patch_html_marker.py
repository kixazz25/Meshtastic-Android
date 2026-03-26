import sys

path = "app/src/main/assets/convoy_map.html"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = "    function setView(lat, lon, zoom) { map.setView([lat, lon], zoom); }"

new = """    function setView(lat, lon, zoom) { map.setView([lat, lon], zoom); }
    var searchCenterMarker = null;
    function showSearchCenter(lat,lng) {
      if(searchCenterMarker) map.removeLayer(searchCenterMarker);
      searchCenterMarker = L.marker([lat,lng]).addTo(map).bindPopup(lat.toFixed(5)+", "+lng.toFixed(5)).openPopup();
    }
    function clearSearchCenter() {
      if(searchCenterMarker) { map.removeLayer(searchCenterMarker); searchCenterMarker=null; }
    }"""

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("PATCHED OK")
else:
    print("ERROR: anchor not found")
    sys.exit(1)
