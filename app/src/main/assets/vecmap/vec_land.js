// VECLAND-2026-10-31 (Fred 07:37: "bring the fill styles in so I can get an idea in several areas what to expect"):
// the ground types the tiles carry but the Liberty style leaves out or draws faintly -- a look to judge, easy to drop.
//   rock / scree      grey-tan            (landcover class rock -- Liberty does not draw it)
//   scrub / heath     warm desert tan     (class grass, subclass scrub/heath/fell -- Liberty draws it as faint grass)
//   farmland          pale straw          (class farmland -- not drawn)
//   trees, grass      stronger (Liberty: 40 % and 30 %)
// All under water, roads and labels. VECLAND log: what land types are in the data in the view (every 3 s at most), so
// "not drawn" can be told from "not mapped" (land types come from OpenStreetMap volunteers -- patchy on remote ground).
(function () {
  var LC = 'landcover', SRC = 'openmaptiles';
  function add() {
    if (map.getLayer('gt-land-rock')) return;
    var before = map.getLayer('waterway_tunnel') ? 'waterway_tunnel' : (typeof gtFirstLabel === 'function' ? gtFirstLabel() : undefined);
    map.addLayer({ id: 'gt-land-farmland', type: 'fill', source: SRC, 'source-layer': LC,
      filter: ['==', ['get', 'class'], 'farmland'], paint: { 'fill-color': '#ECEBC8', 'fill-opacity': 0.6 } }, before);
    map.addLayer({ id: 'gt-land-scrub', type: 'fill', source: SRC, 'source-layer': LC,
      filter: ['all', ['==', ['get', 'class'], 'grass'], ['in', ['get', 'subclass'], ['literal', ['scrub', 'heath', 'fell']]]],
      paint: { 'fill-color': '#E2D3A0', 'fill-opacity': 0.65 } }, before);
    map.addLayer({ id: 'gt-land-rock', type: 'fill', source: SRC, 'source-layer': LC,
      filter: ['==', ['get', 'class'], 'rock'],
      paint: { 'fill-color': ['match', ['get', 'subclass'], 'scree', '#CBBFAE', '#D8CFC2'], 'fill-opacity': 0.8 } }, before);
    if (map.getLayer('landcover_wood')) map.setPaintProperty('landcover_wood', 'fill-opacity', 0.65);
    if (map.getLayer('landcover_grass')) map.setPaintProperty('landcover_grass', 'fill-opacity', 0.5);
    console.log('VECLAND fills added (rock, scrub, farmland; wood and grass stronger)');
  }
  var lastLog = 0;
  function survey() {
    var n = Date.now(); if (n - lastLog < 3000) return; lastLog = n;
    var f = map.querySourceFeatures(SRC, { sourceLayer: LC }), c = {};
    f.forEach(function (x) { var p = x.properties || {}, k = (p['class'] || '?') + (p.subclass && p.subclass !== p['class'] ? '/' + p.subclass : ''); c[k] = (c[k] || 0) + 1; });
    var parts = Object.keys(c).sort(function (a, b) { return c[b] - c[a]; }).map(function (k) { return k + ' ' + c[k]; });
    var ctr = map.getCenter();
    console.log('VECLAND z' + map.getZoom().toFixed(1) + ' at ' + ctr.lat.toFixed(3) + ',' + ctr.lng.toFixed(3) + ': ' + (parts.length ? parts.join(', ') : 'no land types mapped here'));
  }
  if (map.isStyleLoaded()) add(); else map.on('load', add);
  map.on('styledata', function () { try { add(); } catch (e) {} });
  map.on('moveend', function () { try { survey(); } catch (e) {} });
  console.log('VECLAND loaded');
})();
