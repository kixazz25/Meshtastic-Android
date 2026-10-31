// VEC3D-2026-10-30 (2.7e): THE 3D BUTTON comes to life -- terrain and hillshading from the DEM store (AWS Terrain Tiles, Terrarium, z0-12).
// gtSet3D(true): raised terrain (MapLibre setTerrain), hillshading under the labels, the map tilted to 60 degrees.
// gtSet3D(false): flat, north-of-the-moment kept, tilt 0, hillshade off.
// Elevation is served by the app at https://gtvec.local/d/z/x/y.png (gt://d/... through the page's gt protocol). Where no elevation is
// stored the tile is missing and MapLibre treats that ground as flat -- never a broken map.
// gtSetHeadingUp stays a placeholder (VECPORT todo) until the heading work.
var GT_DEM_ATTR = 'Elevation: USGS 3DEP, SRTM, GMTED2010 (AWS Terrain Tiles)';
function gtDemReady() {
  if (map.getSource('gt-dem')) return;
  map.addSource('gt-dem', { type: 'raster-dem', tiles: ['gt://d/{z}/{x}/{y}.png'], tileSize: 256, encoding: 'terrarium',
    minzoom: 0, maxzoom: 12, attribution: GT_DEM_ATTR });
  // hillshading reads its own copy of the source (MapLibre's advice: terrain and hillshade on separate sources)
  map.addSource('gt-dem-shade', { type: 'raster-dem', tiles: ['gt://d/{z}/{x}/{y}.png'], tileSize: 256, encoding: 'terrarium',
    minzoom: 0, maxzoom: 12 });
  var under = (typeof gtFirstLabel === 'function') ? gtFirstLabel() : undefined;
  map.addLayer({ id: 'gt-hillshade', type: 'hillshade', source: 'gt-dem-shade',
    layout: { visibility: 'none' },
    paint: { 'hillshade-exaggeration': 0.5, 'hillshade-shadow-color': '#473B24', 'hillshade-highlight-color': '#FFFFFF' } }, under);
}
window.gtSet3D = function (on) {
  try {
    gtWhenReady(function () {
      gtDemReady();
      if (on) {
        // VEC3DUNCAP-2026-10-31 (Fred 15:13): the Z14 cap removed -- VECELEV fixed the frozen ground height (test: does Z15+ hold?)
        map.setTerrain({ source: 'gt-dem', exaggeration: 1.4 });
        map.setLayoutProperty('gt-hillshade', 'visibility', 'visible');
        map.easeTo({ pitch: 60, duration: 600 });
      } else {
        map.setTerrain(null);
        map.setLayoutProperty('gt-hillshade', 'visibility', 'none');
        map.easeTo({ pitch: 0, duration: 600 });
      }
      console.log('VEC3D 3D ' + (on ? 'ON' : 'OFF'));
    });
  } catch (e) { console.log('VEC3D failed ' + e); }
};
map.on('error', function (e) {   // a missing elevation tile is expected outside the downloaded areas -- say so once, not per tile
  var m = e && e.error && e.error.message ? e.error.message : '';
  if (m.indexOf('gtvec.local/d/') >= 0) { if (!window.__gtDemMissLogged) { window.__gtDemMissLogged = true; console.log('VEC3D no elevation stored for part of the view (flat there)'); } }
});
console.log('VEC3D loaded');
