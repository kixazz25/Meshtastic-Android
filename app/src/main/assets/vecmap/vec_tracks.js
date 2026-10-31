// VECTRACKS-2026-10-30 (Fred 18:41): MY TRACK and THE LEAD TRACK on the vector map -- the same look as the satellite map.
//   My track (while RECORD is on): bright blue #1E88FF dashed on a dashed black edge, blue arrows -- myTrackAdd(pts) appends
//   [[lat, lon], ...]; myTrackClear().
//   The lead track (group): solid line in its colour on a black edge, blue arrows -- drawTrack(segments) with
//   {startLat, startLon, endLat, endLon, color}; consecutive segments of one colour join into one line; clearTrack().
// Drawn above trails / tracks / routes; carts and waypoints (screen markers) stay on top.
(function () {
  var EMPTY = { type: 'FeatureCollection', features: [] };
  var mine = [];          // my track's points, [lon, lat]
  function arrows(id, src, color, size) {
    map.addLayer({ id: id, type: 'symbol', source: src,
      layout: { 'symbol-placement': 'line', 'symbol-spacing': 90, 'text-field': '▶', 'text-font': ['Noto Sans Regular'],
                'text-size': size, 'text-keep-upright': false, 'text-rotation-alignment': 'map', 'text-allow-overlap': true,
                'text-ignore-placement': true },
      paint: { 'text-color': color, 'text-halo-color': '#000000', 'text-halo-width': 1.5 } });
  }
  function layers() {
    if (map.getSource('gt-mytrack')) return;
    map.addSource('gt-lead', { type: 'geojson', data: EMPTY });
    map.addSource('gt-mytrack', { type: 'geojson', data: EMPTY });
    map.addLayer({ id: 'gt-lead-edge', type: 'line', source: 'gt-lead', layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#000000', 'line-width': 9, 'line-opacity': 0.9 } });
    map.addLayer({ id: 'gt-lead', type: 'line', source: 'gt-lead', layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': ['get', 'c'], 'line-width': 5 } });
    arrows('gt-lead-arrows', 'gt-lead', '#1E88FF', 13);
    map.addLayer({ id: 'gt-mytrack-edge', type: 'line', source: 'gt-mytrack', layout: { 'line-cap': 'butt', 'line-join': 'round' },
      paint: { 'line-color': '#000000', 'line-width': 7, 'line-opacity': 0.85, 'line-dasharray': [12 / 7, 9 / 7] } });
    map.addLayer({ id: 'gt-mytrack', type: 'line', source: 'gt-mytrack', layout: { 'line-cap': 'butt', 'line-join': 'round' },
      paint: { 'line-color': '#1E88FF', 'line-width': 4, 'line-dasharray': [3, 2.25] } });
    arrows('gt-mytrack-arrows', 'gt-mytrack', '#1E88FF', 12);
    console.log('VECTRACKS layers ready');
    rideStyles();   // VECRIDESTYLE-2026-10-30
  }
  function line(coords, props) { return { type: 'Feature', properties: props || {}, geometry: { type: 'LineString', coordinates: coords } }; }
  function drawMine() {
    gtWhenReady(function () { layers();
      map.getSource('gt-mytrack').setData(mine.length > 1 ? { type: 'FeatureCollection', features: [line(mine)] } : EMPTY); });
  }
  window.myTrackClear = function () { mine = []; drawMine(); };
  window.myTrackAdd = function (pts) {
    (pts || []).forEach(function (p) { mine.push([p[1], p[0]]); });
    drawMine();
    if (!window.__gtMyTrackLogged) { window.__gtMyTrackLogged = true; console.log('VECTRACKS my track drawing (' + mine.length + ' points)'); }
  };
  window.drawTrack = function (segments) {
    var runs = [], run = null, rc = null;
    (segments || []).forEach(function (s) {
      if (run && rc === s.color && run[run.length - 1][1] === s.startLat && run[run.length - 1][0] === s.startLon) run.push([s.endLon, s.endLat]);
      else { if (run) runs.push(line(run, { c: rc || '#1E88FF' })); run = [[s.startLon, s.startLat], [s.endLon, s.endLat]]; rc = s.color; }
    });
    if (run) runs.push(line(run, { c: rc || '#1E88FF' }));
    gtWhenReady(function () { layers(); map.getSource('gt-lead').setData({ type: 'FeatureCollection', features: runs }); });
    console.log('VECTRACKS drawTrack: ' + (segments || []).length + ' segs, ' + runs.length + ' lines');
  };
  window.clearTrack = function () { gtWhenReady(function () { layers(); map.getSource('gt-lead').setData(EMPTY); }); };
  // VECRIDESTYLE-2026-10-30 (Fred 19:42): MAP KEY's "My track" (__mytrack) and "Lead track" (__lead) style these lines
  function dash(d, w) {
    if (!d) return null;
    var a = String(d).split(',').map(Number).filter(function (x) { return isFinite(x) && x > 0; });
    return a.length ? a.map(function (x) { return x / Math.max(1, w); }) : null;
  }
  function rideStyles() {
    if (!map.getSource('gt-mytrack') || typeof TRAIL_STYLE === 'undefined') return;
    var m = TRAIL_STYLE['__mytrack'];
    if (m) {
      map.setPaintProperty('gt-mytrack', 'line-color', m.c); map.setPaintProperty('gt-mytrack', 'line-width', m.w);
      map.setPaintProperty('gt-mytrack', 'line-dasharray', dash(m.d, m.w));
      map.setPaintProperty('gt-mytrack-edge', 'line-width', m.w + 3); map.setPaintProperty('gt-mytrack-edge', 'line-dasharray', dash(m.d, m.w + 3));
      map.setPaintProperty('gt-mytrack-arrows', 'text-color', m.c);
    }
    var l = TRAIL_STYLE['__lead'];
    if (l) {
      map.setPaintProperty('gt-lead', 'line-color', l.c); map.setPaintProperty('gt-lead', 'line-width', l.w);
      map.setPaintProperty('gt-lead', 'line-dasharray', dash(l.d, l.w));
      map.setPaintProperty('gt-lead-edge', 'line-width', l.w + 4); map.setPaintProperty('gt-lead-edge', 'line-dasharray', dash(l.d, l.w + 4));
      map.setPaintProperty('gt-lead-arrows', 'text-color', l.c);
    }
    if (m || l) console.log('VECRIDESTYLE my track ' + (m ? m.c : '-') + ', lead ' + (l ? l.c : '-'));
  }
  var baseStyles = window.setTrailStyles;
  window.setTrailStyles = function (json) { baseStyles(json); gtWhenReady(function () { layers(); rideStyles(); }); };
  console.log('VECTRACKS loaded');
})();
