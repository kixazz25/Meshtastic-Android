// VECPORT1-2026-10-30 (2.7e step 4.3, part 1): THE FILTER OVERLAYS on the vector page -- trails, tracks, waypoints, routes -- plus
// the viewport report, map-ready, fitBounds, route mode, taps and long-press. A port of convoy_map.html's SHARED SPATIAL ARTIFACTS
// (spatial_artifacts.js) to MapLibre. The app is UNCHANGED: it calls the same names with the same GeoJSON (SpatialDisplayManager).
// ⚠ ZOOM: MapLibre's zoom is ONE LOWER than Leaflet's for the same view (512 px tiles). Everything the app sends or reads is in
//   Leaflet zoom; GT_ZOFF converts at this edge, so the app's rules (trails from z9, the z18 cap) keep their meaning.
// ⚠ Every function is assigned to window.* AFTER the stub loop in vecmap_main.html -- a function declaration would be hoisted and
//   then overwritten by its stub.
var GT_ZOFF = 1;
function gtAppZoom() { return map.getZoom() + GT_ZOFF; }
var GT_EMPTY = { type: 'FeatureCollection', features: [] };
var gtData = { trails: GT_EMPTY, tracks: GT_EMPTY, routes: GT_EMPTY, wpts: GT_EMPTY };
var gtVis = { trails: false, tracks: false, routes: false, wpts: false };
var gtReady = false, gtPending = [];
function gtWhenReady(f) { if (gtReady) f(); else gtPending.push(f); }

// ---- trail styles: the rider's map_keys.json, pushed by setTrailStyles (same table, same rules as convoy_map.html) ----------
var TRAIL_STYLE = {};
var STATUS_STYLE = { 'CLOSED': { c: '#FF2222', w: 3, d: null }, 'PLANNED': { c: '#556070', w: 2, d: '3,6' } };
function trailStyleOf(cartoCode, status) {
  var s = status ? String(status).toUpperCase() : '';
  if (STATUS_STYLE[s]) return STATUS_STYLE[s];
  var k = cartoCode ? String(cartoCode).trim() : '';
  if (TRAIL_STYLE[k]) return TRAIL_STYLE[k];
  if (k.indexOf('R -') === 0) return { c: '#FF1493', w: 2, d: null };
  return { c: '#00FFFF', w: 2, d: null };
}
// a Leaflet dashArray is pixels ("3,6"); MapLibre's is in line widths
function gtDash(d, w) {
  if (!d) return null;
  var a = String(d).split(/[ ,]+/).map(Number).filter(function (n) { return n > 0; });
  return a.length ? a.map(function (n) { return n / Math.max(w, 1); }) : null;
}
function gtStyleTrails(fc) {
  (fc.features || []).forEach(function (f) {
    var p = f.properties || (f.properties = {});
    var st = trailStyleOf(p.CartoCode || '', p.Status || '');
    p._c = st.c; p._w = st.w; p._d = st.d ? String(st.d) : '';
  });
  return fc;
}

// ---- the layers: added once the style is loaded; trails UNDER the map's labels, tracks / routes on top ---------------------
var gtTrailDashLayers = {};   // dash string -> layer id (line-dasharray cannot vary per feature)
function gtFirstLabel() { var ls = map.getStyle().layers; for (var i = 0; i < ls.length; i++) if (ls[i].type === 'symbol') return ls[i].id; return undefined; }
function gtVisib(on) { return on ? 'visible' : 'none'; }
function gtTrailLayerFor(dash) {
  if (dash === '' ) return 'gt-trails';
  if (gtTrailDashLayers[dash]) return gtTrailDashLayers[dash];
  var id = 'gt-trails-d' + Object.keys(gtTrailDashLayers).length;
  map.addLayer({ id: id, type: 'line', source: 'gt-trails', filter: ['==', ['get', '_d'], dash],
    layout: { 'line-cap': 'butt', 'line-join': 'round', visibility: gtVisib(gtVis.trails) },
    paint: { 'line-color': ['get', '_c'], 'line-width': ['get', '_w'], 'line-opacity': 0.85,
             'line-dasharray': gtDash(dash, 2) || [1, 0] } }, gtFirstLabel());
  gtTrailDashLayers[dash] = id;
  return id;
}
function gtTrailLayers() { return ['gt-trails'].concat(Object.keys(gtTrailDashLayers).map(function (k) { return gtTrailDashLayers[k]; })); }
function gtSetup() {
  var under = gtFirstLabel();
  ['gt-trails', 'gt-tracks', 'gt-routes'].forEach(function (s) { map.addSource(s, { type: 'geojson', data: GT_EMPTY }); });
  map.addLayer({ id: 'gt-trails', type: 'line', source: 'gt-trails', filter: ['==', ['get', '_d'], ''],
    layout: { 'line-cap': 'round', 'line-join': 'round', visibility: 'none' },
    paint: { 'line-color': ['get', '_c'], 'line-width': ['get', '_w'], 'line-opacity': 0.85 } }, under);
  map.addLayer({ id: 'gt-tracks', type: 'line', source: 'gt-tracks',
    layout: { 'line-join': 'round', visibility: 'none' },
    paint: { 'line-color': '#39FF14', 'line-width': 3, 'line-opacity': 0.85, 'line-dasharray': [10 / 3, 5 / 3] } });
  map.addLayer({ id: 'gt-tracks-arrows', type: 'symbol', source: 'gt-tracks',
    layout: { 'symbol-placement': 'line', 'symbol-spacing': 80, 'text-field': '▶', 'text-font': ['Noto Sans Regular'],
              'text-size': 13, 'text-keep-upright': false, 'text-rotation-alignment': 'map', 'text-allow-overlap': true,
              'text-ignore-placement': true, visibility: 'none' },
    paint: { 'text-color': '#39FF14' } });
  map.addLayer({ id: 'gt-routes', type: 'line', source: 'gt-routes',
    layout: { 'line-join': 'round', 'line-cap': 'round', visibility: 'none' },
    paint: { 'line-color': '#FF00FF', 'line-width': 3, 'line-opacity': 0.9 } });
  map.addLayer({ id: 'gt-routes-arrows', type: 'symbol', source: 'gt-routes',
    layout: { 'symbol-placement': 'line', 'symbol-spacing': 90, 'text-field': '▶', 'text-font': ['Noto Sans Regular'],
              'text-size': 11, 'text-keep-upright': false, 'text-rotation-alignment': 'map', 'text-allow-overlap': true,
              'text-ignore-placement': true, visibility: 'none' },
    paint: { 'text-color': '#FF00FF' } });
  gtReady = true;
  var p = gtPending; gtPending = []; p.forEach(function (f) { try { f(); } catch (e) { console.log('VECPORT1 pending ' + e); } });
  console.log('VECPORT1 overlay layers ready');
}

// ---- TRAILS -----------------------------------------------------------------------------------------------------------------
window.trailLayer = { getLayers: function () { return gtData.trails.features || []; } };   // the app's TRAILDRAW probe counts these
window.updateTrails = function (fc) {
  if (typeof fc === 'string') fc = JSON.parse(fc);
  gtData.trails = gtStyleTrails(fc || GT_EMPTY);
  gtWhenReady(function () {
    (gtData.trails.features || []).forEach(function (f) { gtTrailLayerFor(f.properties._d); });
    map.getSource('gt-trails').setData(gtData.trails);
  });
};
window.loadTrails = window.updateTrails;
window.showTrails = function () { gtVis.trails = true; gtWhenReady(function () { gtTrailLayers().forEach(function (id) { map.setLayoutProperty(id, 'visibility', 'visible'); }); }); };
window.hideTrails = function () { gtVis.trails = false; gtWhenReady(function () { gtTrailLayers().forEach(function (id) { map.setLayoutProperty(id, 'visibility', 'none'); }); }); };
window.toggleTrails = function () { if (gtVis.trails) window.hideTrails(); else window.showTrails(); return gtVis.trails; };
window.clearTrails = function () { gtData.trails = GT_EMPTY; gtVis.trails = false; gtWhenReady(function () { map.getSource('gt-trails').setData(GT_EMPTY); window.hideTrails(); }); };
window.setTrailStyles = function (json) {
  try {
    var o = (typeof json === 'string') ? JSON.parse(json) : json;
    for (var k in o) {
      if (!o.hasOwnProperty(k)) continue;
      var v = o[k];
      TRAIL_STYLE[k] = { c: v.c || '#00FFFF', w: (typeof v.w === 'number') ? v.w : 2, d: (v.d === null || v.d === undefined || v.d === '') ? null : v.d };
    }
    if (gtData.trails.features && gtData.trails.features.length) window.updateTrails(gtData.trails);   // restyle what is on screen
    console.log('VECPORT1 trail styles ' + Object.keys(TRAIL_STYLE).length);
  } catch (e) { console.log('setTrailStyles failed: ' + e); }
};
window.trailColor = function (c, s) { return trailStyleOf(c, s).c; };

// ---- TRACKS / ROUTES ------------------------------------------------------------------------------------------------------
function gtLineType(key, src, layers) {
  var cap = key.charAt(0).toUpperCase() + key.slice(1);
  window['update' + cap] = function (fc) {
    if (typeof fc === 'string') fc = JSON.parse(fc);
    gtData[key] = fc || GT_EMPTY;
    gtWhenReady(function () { map.getSource(src).setData(gtData[key]); });
  };
  window['load' + cap] = window['update' + cap];
  window['show' + cap] = function () { gtVis[key] = true; gtWhenReady(function () { layers.forEach(function (id) { map.setLayoutProperty(id, 'visibility', 'visible'); }); }); };
  window['hide' + cap] = function () { gtVis[key] = false; gtWhenReady(function () { layers.forEach(function (id) { map.setLayoutProperty(id, 'visibility', 'none'); }); }); };
  window['toggle' + cap] = function () { if (gtVis[key]) window['hide' + cap](); else window['show' + cap](); return gtVis[key]; };
  window['clear' + cap] = function () { gtData[key] = GT_EMPTY; window['hide' + cap](); gtWhenReady(function () { map.getSource(src).setData(GT_EMPTY); }); };
}
gtLineType('tracks', 'gt-tracks', ['gt-tracks', 'gt-tracks-arrows']);
gtLineType('routes', 'gt-routes', ['gt-routes', 'gt-routes-arrows']);

// ---- WAYPOINTS: the same pin as convoy_map.html (a DOM marker each -- emoji symbols need the system font) -------------------
function waypointColor(type) {
  switch (type) {
    case 'hazard': case 'gate': return '#FF4136';
    case 'water': case 'fuel': case 'shelter': return '#0074D9';
    case 'trailhead': case 'scenic': case 'viewpoint': case 'camp': case 'campsite': return '#2ECC40';
    case 'parking': case 'junction': return '#AAAAAA';
    case 'rally': return '#F012BE';
    default: return '#DDDDDD';
  }
}
function waypointSymbol(type) {
  switch (type) {
    case 'hazard': return '☠'; case 'gate': return '⛔'; case 'water': return '💧'; case 'fuel': return '⛽';
    case 'shelter': return '🏠'; case 'trailhead': return '🥾'; case 'scenic': case 'viewpoint': return '👁';
    case 'camp': case 'campsite': return '⛺'; case 'parking': return 'P'; case 'junction': return 'Y'; case 'rally': return '🚩';
    default: return '•';
  }
}
var gtWptMarkers = [];
var gtMarkerTapAt = 0;   // a marker tap must not also count as a map tap
function gtDrawWpts() {
  gtWptMarkers.forEach(function (m) { m.remove(); }); gtWptMarkers = [];
  if (!gtVis.wpts) return;
  (gtData.wpts.features || []).forEach(function (f) {
    var p = f.properties || {}, t = p.wpt_type || 'other', c = f.geometry && f.geometry.coordinates;
    if (!c) return;
    var el = document.createElement('div');
    el.innerHTML = '<div style="width:26px;height:26px;background:' + waypointColor(t) + ';border:2px solid #fff;border-radius:50% 50% 50% 0;' +
      'transform:rotate(-45deg);display:flex;align-items:center;justify-content:center;box-shadow:0 0 4px rgba(0,0,0,0.5);">' +
      '<span style="transform:rotate(45deg);font-size:13px;font-weight:bold;color:#000;font-family:-apple-system,Segoe UI Emoji,Noto Color Emoji,sans-serif;">' +
      waypointSymbol(t) + '</span></div>';
    el.addEventListener('click', function (ev) {
      ev.stopPropagation(); gtMarkerTapAt = Date.now();
      console.log('ARTIFACTTAP waypoint name=' + (p.name || '') + ' routeMode=' + window.__routeMode);
      if (window.__pinSelect && window.Android && Android.onProximityTap) { Android.onProximityTap(c[1], c[0]); return; }
      if (p.waypoint_id && window.Android && Android.onWaypointTap) Android.onWaypointTap(p.waypoint_id);
    });
    gtWptMarkers.push(new maplibregl.Marker({ element: el, anchor: 'bottom', opacityWhenCovered: '1' /* VECMARKER3D-2026-10-31: visible in 3D */ }).setLngLat(c).addTo(map));
  });
}
window.updateWaypoints = function (fc) { if (typeof fc === 'string') fc = JSON.parse(fc); gtData.wpts = fc || GT_EMPTY; gtDrawWpts(); };
window.loadWaypoints = window.updateWaypoints;
window.showWaypoints = function () { gtVis.wpts = true; gtDrawWpts(); };
window.hideWaypoints = function () { gtVis.wpts = false; gtDrawWpts(); };
window.toggleWaypoints = function () { gtVis.wpts = !gtVis.wpts; gtDrawWpts(); return gtVis.wpts; };
window.clearWaypoints = function () { gtData.wpts = GT_EMPTY; gtVis.wpts = false; gtDrawWpts(); };

// ---- VIEWPORT: the app asks the database for what is in view (same call, same 400 ms settle, Leaflet zoom) ------------------
window.triggerViewportUpdate = function () {
  var b = map.getBounds();
  try { Android.onViewportChanged(b.getNorth(), b.getSouth(), b.getEast(), b.getWest(), gtAppZoom()); } catch (e) {}
};
var gtVpTimer = null;
map.on('moveend', function () { if (gtVpTimer) clearTimeout(gtVpTimer); gtVpTimer = setTimeout(window.triggerViewportUpdate, 400); });

// ---- the app's own view calls, in Leaflet zoom ------------------------------------------------------------------------------
window.setView = function (lat, lon, z) { map.resize(); map.jumpTo({ center: [lon, lat], zoom: (z == null ? map.getZoom() : z - GT_ZOFF) }); };
window.fitBounds = function (lats, lons, tries, pad) {
  if (!lats || !lats.length) return;
  map.resize();
  var c = map.getContainer();
  if (!c.clientWidth || !c.clientHeight) { tries = tries || 0; if (tries < 20) setTimeout(function () { window.fitBounds(lats, lons, tries + 1, pad); }, 150); return; }
  if (lats.length === 1) { map.jumpTo({ center: [lons[0], lats[0]], zoom: 16 - GT_ZOFF }); return; }
  var s = Math.min.apply(null, lats), n = Math.max.apply(null, lats), w = Math.min.apply(null, lons), e = Math.max.apply(null, lons);
  var px = (pad && pad > 0) ? Math.round(Math.min(c.clientWidth, c.clientHeight) * pad) : 0;
  map.fitBounds([[w, s], [e, n]], { padding: px, maxZoom: 18 - GT_ZOFF, duration: 0 });
};
window.setRouteMode = function (on) { window.__routeMode = !!on; console.log('ROUTEMODE -> ' + window.__routeMode); };
window.__routeMode = false;

// ---- TAPS: route mode first (Route+ / AI pins), then the nearest overlay -- routes, then tracks, then trails ---------------
map.on('click', function (e) {
  if (window.gtDrawOn) return;   // VECPORTS-2026-10-31: drawing a box
  if (Date.now() - gtMarkerTapAt < 400) return;
  try {
    if (!window.gtOneMapRoute) window.__routeMode = false;
    if (window.__routeMode && window.Android) {
      if (window.__pinSelect && Android.onProximityTap) Android.onProximityTap(e.lngLat.lat, e.lngLat.lng);
      else if (Android.onMapTap) Android.onMapTap(e.lngLat.lat, e.lngLat.lng);
      return;
    }
    var box = [[e.point.x - 12, e.point.y - 12], [e.point.x + 12, e.point.y + 12]];
    var tries = [['gt-routes', 'route_id', 'onRouteTap'], ['gt-tracks', 'track_id', 'onTrackTap']];
    for (var i = 0; i < tries.length; i++) {
      if (!gtVis[tries[i][0].slice(3)]) continue;
      var hit = map.queryRenderedFeatures(box, { layers: [tries[i][0]] });
      if (hit.length && hit[0].properties[tries[i][1]] && window.Android && Android[tries[i][2]]) {
        console.log('ARTIFACTTAP ' + tries[i][0] + ' ' + hit[0].properties[tries[i][1]]);
        Android[tries[i][2]](hit[0].properties[tries[i][1]]); return;
      }
    }
    if (gtVis.trails) {
      var th = map.queryRenderedFeatures(box, { layers: gtTrailLayers() });
      if (th.length && th[0].properties.trail_id && window.Android && Android.onTrailTap) {
        console.log('ARTIFACTTAP trail ' + th[0].properties.trail_id); Android.onTrailTap(th[0].properties.trail_id); return;
      }
    }
  } catch (err) { console.log('VECPORT1 tap ' + err); }
});

// ---- LONG PRESS -> create a waypoint (Leaflet emulated contextmenu on touch; MapLibre does not) -----------------------------
var gtLpTimer = null;
function gtLpCancel() { if (gtLpTimer) { clearTimeout(gtLpTimer); gtLpTimer = null; } }
map.on('touchstart', function (e) {
  gtLpCancel();
  if (!e.points || e.points.length !== 1) return;
  var ll = e.lngLat;
  gtLpTimer = setTimeout(function () { gtLpTimer = null; try { if (window.gtLongPress) gtLongPress(ll.lat, ll.lng, 'page'); else if (window.Android && Android.onMapLongPress) Android.onMapLongPress(ll.lat, ll.lng); } catch (x) {}   /* VECPORTS-2026-10-31 */ }, 600);
});
map.on('touchmove', gtLpCancel); map.on('touchend', gtLpCancel); map.on('touchcancel', gtLpCancel);
map.on('contextmenu', function (e) { try { if (window.gtLongPress) gtLongPress(e.lngLat.lat, e.lngLat.lng, 'menu'); else if (window.Android && Android.onMapLongPress) Android.onMapLongPress(e.lngLat.lat, e.lngLat.lng); } catch (x) {}   /* VECPORTS-2026-10-31 */ });

// ---- MAP READY: the app pushes the rider's trail styles, then the first viewport draws the overlays ------------------------
map.on('load', function () {
  try { gtSetup(); } catch (e) { console.log('VECPORT1 setup ' + e); }
  try { var b = map.getBounds(); if (window.Android && Android.onMapReady) Android.onMapReady(b.getNorth(), b.getSouth(), b.getEast(), b.getWest()); } catch (e) {}
  setTimeout(window.triggerViewportUpdate, 300);
});
if (map.loaded()) { try { gtSetup(); } catch (e) {} }
console.log('VECPORT1 loaded');
