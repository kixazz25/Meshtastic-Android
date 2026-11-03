// VECPORTS-2026-10-31 (Fred 17:27, the port list from 10-30): the map calls the vector page still lacked, rewritten for MapLibre with the
// SAME names, arguments and look as convoy_map.html (Leaflet). Loaded last, so each replaces its "VECPORT todo" stub.
//   1 Getting maps   activateDrawMode, cancelDrawMode, clearAreaBoundary, showDownloadArea; showDownloadedAreas / clearDownloadedAreas;
//                    showQueuedAreas / clearQueuedAreas
//   2 Route+         renderPins / clearPins (the missing clearPins left the map in route mode), drawBuildLine / clearBuildLine,
//                    showAimRing; AI batch drawBatchRoute / showBatchRoute / clearBatchRoutes / fitBatchRoutes
//   3 Touch, search  longPressAt (the app's long press; one waypoint per press with the page's own), showSearchCenter / clearSearchCenter
//   4 No-ops         setTileUrl, setOverlayLayers (satellite leftovers); getMapBounds, showProximityPopup kept for completeness
// Points arrive as Leaflet [lat, lon]; MapLibre wants [lon, lat] -- gtLL() turns them round.
(function () {
var EMPTY = { type: 'FeatureCollection', features: [] };
function gtLL(p) { return Array.isArray(p) ? [+p[1], +p[0]] : [+(p.lng != null ? p.lng : p.lon), +p.lat]; }
function gtJ(v) { return (typeof v === 'string') ? JSON.parse(v) : v; }
function gtBox(n, s, e, w, props) { return { type: 'Feature', properties: props || {}, geometry: { type: 'Polygon', coordinates: [[[w, s], [e, s], [e, n], [w, n], [w, s]]] } }; }
function gtFirstSym() { var ls = map.getStyle().layers; for (var i = 0; i < ls.length; i++) if (ls[i].type === 'symbol') return ls[i].id; return undefined; }
// one source + its layers, made the first time they are needed (after the map is ready); under=true puts them beneath the labels
var gtMade = {};
function gtSrc(id, layers, under) {
  if (gtMade[id]) return map.getSource(id);
  map.addSource(id, { type: 'geojson', data: EMPTY });
  var before = under ? gtFirstSym() : undefined;
  layers.forEach(function (l) { l.source = id; map.addLayer(l, before); });
  gtMade[id] = 1; return map.getSource(id);
}
function gtSet(id, layers, data, under) { gtWhenReady(function () { try { gtSrc(id, layers, under).setData(data); } catch (e) { console.log('VECPORTS ' + id + ' ' + e); } }); }
function gtPx(v) { return v / (window.devicePixelRatio || 1); }   // the app sends screen pixels; the page works in CSS pixels

// ---- 1 GETTING MAPS: draw a box (press, drag, lift) -> Android.onAreaSelected(n, s, e, w) ------------------------------------------
var AREA_L = [{ id: 'gt-area-fill', type: 'fill', paint: { 'fill-color': '#FFB74D', 'fill-opacity': 0.15 } },
              { id: 'gt-area-line', type: 'line', paint: { 'line-color': '#FFB74D', 'line-width': 2 } }];
var DLA_L = [{ id: 'gt-dlarea-fill', type: 'fill', paint: { 'fill-color': '#27AE60', 'fill-opacity': 0.1 } },
             { id: 'gt-dlarea-line', type: 'line', paint: { 'line-color': '#27AE60', 'line-width': 2, 'line-dasharray': [2.5, 2.5] } }];
var gtDrawStart = null, gtDrawBox = null;
window.gtDrawOn = false;
function gtGestures(on) {
  ['dragPan', 'dragRotate', 'touchZoomRotate', 'touchPitch', 'doubleClickZoom', 'boxZoom'].forEach(function (h) {
    try { if (map[h]) { if (on) map[h].enable(); else map[h].disable(); } } catch (e) {}
  });
}
function gtEvLL(e) {
  if (e.originalEvent && e.originalEvent.touches && e.originalEvent.touches.length > 1) return null;   // two fingers is not a box
  return e.lngLat;
}
function gtDrawDown(e) {
  if (!window.gtDrawOn) return;
  var ll = gtEvLL(e); if (!ll) return;
  if (typeof gtLpCancel === 'function') gtLpCancel();
  gtDrawStart = ll; gtDrawBox = null;
  if (e.preventDefault) e.preventDefault();
}
function gtDrawMove(e) {
  if (!window.gtDrawOn || !gtDrawStart) return;
  var ll = gtEvLL(e); if (!ll) return;
  gtDrawBox = { n: Math.max(gtDrawStart.lat, ll.lat), s: Math.min(gtDrawStart.lat, ll.lat),
                e: Math.max(gtDrawStart.lng, ll.lng), w: Math.min(gtDrawStart.lng, ll.lng) };
  gtSet('gt-area', AREA_L, { type: 'FeatureCollection', features: [gtBox(gtDrawBox.n, gtDrawBox.s, gtDrawBox.e, gtDrawBox.w)] });
}
function gtDrawUp() {
  if (!window.gtDrawOn || !gtDrawStart) return;
  var b = gtDrawBox; gtDrawStart = null; gtDrawBox = null;
  if (!b || b.n - b.s < 1e-5 || b.e - b.w < 1e-5) return;   // a tap, not a box: stay in draw mode
  window.gtDrawOn = false; gtGestures(true);                 // one box per draw, as Leaflet's rectangle tool
  console.log('VECPORTS area N=' + b.n.toFixed(5) + ' S=' + b.s.toFixed(5) + ' E=' + b.e.toFixed(5) + ' W=' + b.w.toFixed(5));
  try { if (window.Android && Android.onAreaSelected) Android.onAreaSelected(b.n, b.s, b.e, b.w); } catch (x) { console.log('VECPORTS onAreaSelected ' + x); }
}
map.on('touchstart', gtDrawDown); map.on('mousedown', gtDrawDown);
map.on('touchmove', gtDrawMove);  map.on('mousemove', gtDrawMove);
map.on('touchend', gtDrawUp);     map.on('mouseup', gtDrawUp);   map.on('touchcancel', function () { gtDrawStart = null; });
window.activateDrawMode = function () {
  window.clearAreaBoundary(); window.gtDrawOn = true; gtDrawStart = null; gtGestures(false);
  console.log('VECPORTS draw mode on');
};
window.cancelDrawMode = function () {
  window.gtDrawOn = false; gtDrawStart = null; gtGestures(true); window.clearAreaBoundary();
  console.log('VECPORTS draw mode off');
};
window.clearAreaBoundary = function () { gtSet('gt-area', AREA_L, EMPTY); gtSet('gt-dlarea', DLA_L, EMPTY); };
window.showDownloadArea = function (n, s, e, w) {
  gtSet('gt-area', AREA_L, EMPTY);
  gtSet('gt-dlarea', DLA_L, { type: 'FeatureCollection', features: [gtBox(n, s, e, w)] });
};
// FILTER > OFF-GRID MAPS > Available: the boxes of maps held (blue)
var HELD_L = [{ id: 'gt-held-fill', type: 'fill', paint: { 'fill-color': '#4DA6FF', 'fill-opacity': 0.22 } }];
window.showDownloadedAreas = function (bounds) {
  var bs = gtJ(bounds) || [];
  gtSet('gt-held', HELD_L, { type: 'FeatureCollection', features: bs.map(function (b) { return gtBox(b.n, b.s, b.e, b.w); }) }, true);
  console.log('VECPORTS downloaded areas ' + bs.length);
};
window.clearDownloadedAreas = function () { gtSet('gt-held', HELD_L, EMPTY, true); };
// FILTER > OFF-GRID MAPS > Downloading: queued / downloading jobs (red; a corridor's box is an estimate, drawn hollow and dashed)
var QUEUE_L = [
  { id: 'gt-queue-fill', type: 'fill', filter: ['!=', ['get', 'corridor'], true], paint: { 'fill-color': '#FF3333', 'fill-opacity': 0.15 } },
  { id: 'gt-queue-line', type: 'line', filter: ['!=', ['get', 'corridor'], true], paint: { 'line-color': '#FF3333', 'line-width': 1, 'line-dasharray': [6, 3] } },
  { id: 'gt-queue-corr', type: 'line', filter: ['==', ['get', 'corridor'], true], paint: { 'line-color': '#FF3333', 'line-width': 2, 'line-dasharray': [6, 4] } }];
window.showQueuedAreas = function (boundsJson) {
  try {
    var bs = gtJ(boundsJson) || [];
    gtSet('gt-queue', QUEUE_L, { type: 'FeatureCollection', features: bs.map(function (b) { return gtBox(b.n, b.s, b.e, b.w, { corridor: !!b.corridor, label: b.label || '' }); }) }, true);
    console.log('VECPORTS queued areas ' + bs.length);
  } catch (e) { console.log('VECPORTS showQueuedAreas ' + e); }
};
window.clearQueuedAreas = function () { gtSet('gt-queue', QUEUE_L, EMPTY, true); };

// ---- 2 ROUTE+ ----------------------------------------------------------------------------------------------------------------------
// the dropped points: red numbered discs, drop order; renderPins clears and redraws the whole set
window.__pinLayers = [];
window.clearPins = function () { try { window.__pinLayers.forEach(function (m) { m.remove(); }); } catch (e) {} window.__pinLayers = []; };
window.renderPins = function (json) {
  window.clearPins();
  try {
    var pts = gtJ(json) || [];
    pts.forEach(function (p, i) {
      var el = document.createElement('div');
      el.style.cssText = 'background:#FF0000;color:#fff;border:2px solid #fff;border-radius:50%;width:26px;height:26px;line-height:22px;'
        + 'text-align:center;font-weight:900;font-size:13px;box-shadow:0 1px 4px rgba(0,0,0,0.5);box-sizing:border-box;pointer-events:none';
      el.textContent = String(i + 1);
      window.__pinLayers.push(new maplibregl.Marker({ element: el, anchor: 'center' }).setLngLat(gtLL(p)).addTo(map));
    });
    console.log('PINDRAW rendered ' + pts.length + ' pin(s)');
  } catch (err) { console.log('PINDRAW failed: ' + err); }
};
// the route being built: magenta, one point = a dot
var BUILD_L = [{ id: 'gt-build-line', type: 'line', filter: ['==', ['geometry-type'], 'LineString'], layout: { 'line-cap': 'round', 'line-join': 'round' },
                 paint: { 'line-color': '#FF00FF', 'line-width': 4, 'line-opacity': 0.95 } },
               { id: 'gt-build-dot', type: 'circle', filter: ['==', ['geometry-type'], 'Point'],
                 paint: { 'circle-radius': 5, 'circle-color': '#FF00FF', 'circle-stroke-color': '#FF00FF', 'circle-stroke-width': 1 } }];
function gtLineOrDot(pts, props) {
  if (!pts || !pts.length) return null;
  if (pts.length === 1) return { type: 'Feature', properties: props || {}, geometry: { type: 'Point', coordinates: gtLL(pts[0]) } };
  return { type: 'Feature', properties: props || {}, geometry: { type: 'LineString', coordinates: pts.map(gtLL) } };
}
window.drawBuildLine = function (jsonPts) {
  try { var f = gtLineOrDot(gtJ(jsonPts)); gtSet('gt-build', BUILD_L, { type: 'FeatureCollection', features: f ? [f] : [] }); }
  catch (err) { console.log('VECPORTS drawBuildLine ' + err); }
};
window.clearBuildLine = function () { gtSet('gt-build', BUILD_L, EMPTY); };
// the aim ring: a quarter mile (402 m -- RouteExplorer's TRAILHEADNEAR radius) round the map centre, follows the map
var AIM_L = [{ id: 'gt-aim-fill', type: 'fill', paint: { 'fill-color': '#7FC8FF', 'fill-opacity': 0.08 } },
             { id: 'gt-aim-line', type: 'line', paint: { 'line-color': '#7FC8FF', 'line-width': 2, 'line-opacity': 0.9 } }];
function gtCircle(c, m) {
  var ring = [], R = 6378137, la = c.lat * Math.PI / 180;
  for (var i = 0; i <= 64; i++) {
    var a = i / 64 * 2 * Math.PI;
    ring.push([c.lng + (m * Math.sin(a) / (R * Math.cos(la))) * 180 / Math.PI, c.lat + (m * Math.cos(a) / R) * 180 / Math.PI]);
  }
  return { type: 'FeatureCollection', features: [{ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [ring] } }] };
}
// AIFAILCHECK-2026-11-03 (Fred 07:18): the ring is 1/2 mile from the trailhead (1 mile across) with cross hairs at the centre --
// MUST equal RouteExplorer.AIM_RING_M (805).
function gtAim(c) {
  var m = 805, fc = gtCircle(c, m), R = 6378137, la = c.lat * Math.PI / 180;
  var dLat = m / R * 180 / Math.PI, dLng = m / (R * Math.cos(la)) * 180 / Math.PI;
  if (window.__aimOk === false) return fc;   // AIMCOLOUR-2026-11-03 v2 (Fred 07:27): red = no cross hairs
  fc.features.push({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[c.lng - dLng, c.lat], [c.lng + dLng, c.lat]] } });
  fc.features.push({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[c.lng, c.lat - dLat], [c.lng, c.lat + dLat]] } });
  return fc;
}
window.__aimMove = null;
// AIMCOLOUR-2026-11-03 (Fred 07:25-07:26): GREEN = a trail you are showing within 1/2 mile + 200 m of the ring's centre (a trailhead
// here will be accepted), RED = no trail access; blue until the first check. From the trails the map draws (gtData.trails) -- the
// trailhead step's own check still decides. 1005 MUST equal RouteExplorer.TRAILHEAD_CHECK_M.
window.__aimOk = null;
function gtAimMsg(show) {   // AIMCOLOUR-2026-11-03 v2: "No trail access" at the ring's centre (the ring follows the map's centre)
  var d = document.getElementById('gt-aim-msg');
  if (!d && show) {
    d = document.createElement('div'); d.id = 'gt-aim-msg'; d.textContent = 'No trail access';
    d.style.cssText = 'position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);z-index:5;pointer-events:none;' +
      'background:rgba(229,57,53,0.92);color:#fff;font:bold 15px sans-serif;padding:6px 12px;border-radius:6px';
    document.body.appendChild(d);
  }
  if (d) d.style.display = show ? 'block' : 'none';
}
function gtAimPaint() {
  gtAimMsg(window.__aimOk === false);
  try { if (window.__aimMove) window.__aimMove(); } catch (e) {}   // cross hairs on / off with the state
  var col = window.__aimOk === true ? '#43A047' : (window.__aimOk === false ? '#E53935' : '#7FC8FF');
  try { if (map.getLayer('gt-aim-line')) map.setPaintProperty('gt-aim-line', 'line-color', col);
        if (map.getLayer('gt-aim-fill')) map.setPaintProperty('gt-aim-fill', 'fill-color', col); } catch (e) {}
}
window.gtAimCheck = function () {
  try {
    if (!window.__aimMove) return;
    var c = map.getCenter(), lim = 1005, kx = 111320 * Math.cos(c.lat * Math.PI / 180), ky = 111320, best = Infinity;
    var fs = (gtVis.trails && gtData.trails && gtData.trails.features) || [];
    function seg(a, b) {
      var ax = (a[0] - c.lng) * kx, ay = (a[1] - c.lat) * ky, bx = (b[0] - c.lng) * kx, by = (b[1] - c.lat) * ky;
      var dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy, t = l2 === 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2));
      var px = ax + t * dx, py = ay + t * dy; return Math.sqrt(px * px + py * py);
    }
    function line(cs) { for (var j = 1; j < cs.length && best > lim; j++) best = Math.min(best, seg(cs[j - 1], cs[j]));
                        if (cs.length === 1) best = Math.min(best, seg(cs[0], cs[0])); }
    for (var i = 0; i < fs.length && best > lim; i++) {
      var g = fs[i].geometry; if (!g) continue;
      if (g.type === 'LineString') line(g.coordinates);
      else if (g.type === 'MultiLineString') g.coordinates.forEach(line);
    }
    window.__aimOk = best <= lim;
  } catch (e) { window.__aimOk = null; console.log('AIMCOLOUR ' + e); }
  gtAimPaint();
};
window.showAimRing = function (on) {
  try {
    if (!on) { if (window.__aimMove) { map.off('move', window.__aimMove); map.off('moveend', window.gtAimCheck); window.__aimMove = null; }
               window.__aimOk = null; gtAimMsg(false); gtSet('gt-aim', AIM_L, EMPTY); return; }   // AIMCOLOUR-2026-11-03: moveend too
    if (window.__aimMove) return;
    window.__aimMove = function () { gtSet('gt-aim', AIM_L, gtAim(map.getCenter())); };   // AIFAILCHECK-2026-11-03
    window.__aimMove(); map.on('move', window.__aimMove);
    map.on('moveend', window.gtAimCheck); setTimeout(window.gtAimCheck, 300);   // AIMCOLOUR-2026-11-03: checked when the map stops
  } catch (e) { console.log('aim ring: ' + e); }
};
// the AI batch: up to six routes, each its own colour, keyed by draft name; show / hide without redrawing; fit to those shown
var BATCH_L = [{ id: 'gt-batch-line', type: 'line', filter: ['all', ['==', ['geometry-type'], 'LineString'], ['!=', ['get', 'h'], true]],
                 layout: { 'line-cap': 'round', 'line-join': 'round' },
                 paint: { 'line-color': ['get', 'c'], 'line-width': ['get', 'w'], 'line-opacity': 0.95 } },
               { id: 'gt-batch-dot', type: 'circle', filter: ['all', ['==', ['geometry-type'], 'Point'], ['!=', ['get', 'h'], true]],
                 paint: { 'circle-radius': 5, 'circle-color': ['get', 'c'] } }];
window.__batchLayers = {};
function gtBatchPush() {
  var fs = []; for (var k in window.__batchLayers) fs.push(window.__batchLayers[k]);
  gtSet('gt-batch', BATCH_L, { type: 'FeatureCollection', features: fs });
}
window.drawBatchRoute = function (name, jsonPts, color) {
  try {
    delete window.__batchLayers[name];
    var f = gtLineOrDot(gtJ(jsonPts), { name: name, c: color || '#FF00FF', w: 4, h: false });
    if (f) window.__batchLayers[name] = f;
    gtBatchPush();
    console.log('BATCHDRAW ' + name + ' ' + (color || '#FF00FF') + ' ' + (f ? (f.geometry.type === 'Point' ? 1 : f.geometry.coordinates.length) : 0) + ' pts');
  } catch (err) { console.log('BATCHDRAW FAILED ' + name + ' ' + err); }
};
window.showBatchRoute = function (name, on, weight) {
  var f = window.__batchLayers[name];
  if (!f) { console.log('SHOWBATCH no layer ' + name); return false; }
  if (weight) f.properties.w = weight;
  f.properties.h = !on; gtBatchPush();
  console.log('SHOWBATCH ' + name + ' ' + on); return true;
};
window.clearBatchRoutes = function () { window.__batchLayers = {}; gtBatchPush(); };
window.fitBatchRoutes = function () {
  try {
    var b = null;
    for (var k in window.__batchLayers) {
      var f = window.__batchLayers[k]; if (f.properties.h) continue;
      var cs = f.geometry.type === 'Point' ? [f.geometry.coordinates] : f.geometry.coordinates;
      cs.forEach(function (c) { b = b ? b.extend(c) : new maplibregl.LngLatBounds(c, c); });
    }
    if (b) map.fitBounds(b, { padding: 30, duration: 0 });
  } catch (err) { console.log('VECPORTS fitBatchRoutes ' + err); }
};

// ---- 3 TOUCH AND SEARCH ------------------------------------------------------------------------------------------------------------
// The app's long press (500 ms, its own touch listener) and the page's (600 ms) both land here: one waypoint per press.
var gtLpLast = 0;
window.gtLongPress = function (lat, lng, from) {
  if (window.gtDrawOn) return;                     // drawing a box is not a long press
  var now = Date.now(); if (now - gtLpLast < 1500) return; gtLpLast = now;
  console.log('LONGPRESS -> ' + lat + ',' + lng + ' (' + from + ')');
  try { if (window.Android && Android.onMapLongPress) Android.onMapLongPress(lat, lng); else console.log('LONGPRESS: Android.onMapLongPress MISSING'); }
  catch (err) { console.log('LONGPRESS ERR ' + err); }
};
window.longPressAt = function (x, y) {
  try { var ll = map.unproject([gtPx(x), gtPx(y)]); window.gtLongPress(ll.lat, ll.lng, 'app'); } catch (err) { console.log('LONGPRESS ERR ' + err); }
};
// the area search centre: an orange dot with blue dashed cross-hairs through it
var SC_L = [{ id: 'gt-sc-lines', type: 'line', filter: ['==', ['geometry-type'], 'LineString'], paint: { 'line-color': '#4DA6FF', 'line-width': 1.5, 'line-opacity': 0.7, 'line-dasharray': [4, 2.7] } },
            { id: 'gt-sc-dot', type: 'circle', filter: ['==', ['geometry-type'], 'Point'], paint: { 'circle-radius': 6, 'circle-color': '#FF6B35', 'circle-stroke-color': '#FF6B35', 'circle-stroke-width': 2 } }];
window.showSearchCenter = function (lat, lng) {
  gtSet('gt-sc', SC_L, { type: 'FeatureCollection', features: [
    { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[-180, lat], [180, lat]] } },
    { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[lng, -85], [lng, 85]] } },
    { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [lng, lat] } }] });
};
window.clearSearchCenter = function () { gtSet('gt-sc', SC_L, EMPTY); };

// ---- 4 THE REST --------------------------------------------------------------------------------------------------------------------
window.setTileUrl = function () {};          // satellite leftover: the vector page has one map
window.setOverlayLayers = function () {};    // satellite leftover (label overlays): labels are in the vector map
window.getMapBounds = function () {
  var b = map.getBounds();
  try { Android.onMapBoundsReady(b.getNorth(), b.getSouth(), b.getEast(), b.getWest()); } catch (e) {}
};
window.showProximityPopup = function (lat, lon, html) {
  var p = new maplibregl.Popup({ closeOnClick: true }).setLngLat([lon, lat]).setHTML('<div style="font-size:11px;line-height:1.6;">' + html + '</div>').addTo(map);
  setTimeout(function () { p.remove(); }, 5000);
};
console.log('VECPORTS-2026-10-31 loaded: area draw, held / queued areas, Route+ pins / build line / aim ring / AI batch, long press, search centre (dpr ' + (window.devicePixelRatio || 1) + ')');
})();
