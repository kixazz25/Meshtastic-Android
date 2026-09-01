// spatial_artifacts.js — Shared spatial DB artifact display functions
// Used by both convoy_map.html and grouptrack_map.html
// Requires: global 'map' variable (Leaflet map instance) and 'Android' JS interface

// ── Layer variables ──
var trailLayer = null, trailsVisible = false;
var trackLayer = null, tracksVisible = false;
var waypointLayer = null, waypointsVisible = false;
var routeLayer = null, routesVisible = false;

// ── Viewport trigger ──
function triggerViewportUpdate() {
  var b = map.getBounds();
  var z = map.getZoom();
  try { Android.onViewportChanged(b.getNorth(), b.getSouth(), b.getEast(), b.getWest(), z); } catch(e) {}
}

// ══════════════════════════════════════════════
// TRAIL DISPLAY -- CATCOLOR-2026-08-31
// carto_code now holds CATEGORY NAMES, not digit-prefixed source values, so
// charAt(0) cannot work: "hiking" and "hiking and biking" share 'h'.
// ⭐ Blue = rideable; the SHADE CARRIES CONFIDENCE.
// ⛔ Status OVERRIDES the category -- red is closed, period.
// ══════════════════════════════════════════════
var TRAIL_STYLE = {
  'OHV':                  { c: '#00CCFF', w: 5, d: null },
  'track':                { c: '#00AAFF', w: 5, d: null },
  'forestry/access road': { c: '#0077DD', w: 4, d: null },
  'shape only':           { c: '#0044AA', w: 3, d: '9,5' },
  'hiking and biking':    { c: '#66CC66', w: 2, d: null },
  'hiking':               { c: '#FFCC00', w: 2, d: null },
  'biking':               { c: '#AA44FF', w: 2, d: null },
  'equestrian':           { c: '#CC8844', w: 2, d: '2,4' },
  'steps/bridge':         { c: '#888888', w: 2, d: '2,4' },
  'unknown':              { c: '#00FFFF', w: 2, d: null }
};
var STATUS_STYLE = {
  'CLOSED':  { c: '#FF2222', w: 3, d: null },
  'PLANNED': { c: '#556070', w: 2, d: '3,6' }
};
function trailStyleOf(cartoCode, status) {
  var s = status ? String(status).toUpperCase() : '';
  if (STATUS_STYLE[s]) return STATUS_STYLE[s];
  var k = cartoCode ? String(cartoCode).trim() : '';
  if (TRAIL_STYLE[k]) return TRAIL_STYLE[k];
  if (k.indexOf('R -') === 0) return { c: '#FF1493', w: 2, d: null };
  return { c: '#00FFFF', w: 2, d: null };
}
function trailColor(cartoCode, status)  { return trailStyleOf(cartoCode, status).c; }
function trailWeight(cartoCode, status) { return trailStyleOf(cartoCode, status).w; }
function trailDash(cartoCode, status)   { return trailStyleOf(cartoCode, status).d; }

function loadTrails(geojsonData) {
  if (trailLayer) map.removeLayer(trailLayer);
  trailLayer = L.geoJSON(geojsonData, {
    style: function(feature) {
      var cc = feature.properties.CartoCode || '';
      // CATCOLOR-2026-08-31: status overrides the category.
      var st = feature.properties.Status || '';
      return {
        color: trailColor(cc, st),
        weight: trailWeight(cc, st),
        dashArray: trailDash(cc, st),
        opacity: 0.85
      };
    },
    onEachFeature: function(feature, layer) {
      var p = feature.properties;
      var name = p.PrimaryName || p.SystemName || p.name || 'Unnamed Trail';
      var cc = p.CartoCode || 'Unknown';
      var surface = p.SurfaceType || 'Unknown';
      var uses = p.DesignatedUses || 'Unknown';
      var motor = p.MotorizedAllowed || 'Unknown';
      var popup = '<div style="font-family:monospace;font-size:12px;max-width:260px;">' +
        '<b style="font-size:14px;color:#00AAFF;">' + name + '</b><br>' +
        '<hr style="border:0;border-top:1px solid #444;margin:4px 0;">' +
        'CartoCode: ' + cc + '<br>' +
        'Surface: ' + surface + '<br>' +
        'Uses: ' + uses + '<br>' +
        'Motorized: ' + motor +
        '</div>';
      layer.bindPopup(popup);
    }
  });
}

function showTrails() {
  if (trailLayer && !trailsVisible) { trailLayer.addTo(map); trailsVisible = true; }
}
function hideTrails() {
  if (trailLayer && trailsVisible) { map.removeLayer(trailLayer); trailsVisible = false; }
}
function toggleTrails() {
  if (trailsVisible) hideTrails(); else showTrails();
  return trailsVisible;
}
function updateTrails(geojsonData) {
  if (typeof geojsonData === 'string') geojsonData = JSON.parse(geojsonData);
  var wasVisible = trailsVisible;
  if (trailsVisible && trailLayer) map.removeLayer(trailLayer);
  loadTrails(geojsonData);
  trailsVisible = false;
  if (wasVisible) showTrails();
}
function clearTrails() {
  if (trailLayer) { map.removeLayer(trailLayer); trailLayer = null; }
  trailsVisible = false;
}

// ══════════════════════════════════════════════
// TRACK DISPLAY (dashed neon green)
// ══════════════════════════════════════════════
function loadTracks(geojsonData) {
  if (trackLayer) map.removeLayer(trackLayer);
  trackLayer = L.geoJSON(geojsonData, {
    style: function() {
      return { color: '#39FF14', weight: 3, opacity: 0.85, dashArray: '10,5' };
    },
    onEachFeature: function(feature, layer) {
      var name = (feature.properties && feature.properties.name) || 'Unnamed Track';
      layer.bindPopup('<b>' + name + '</b>');
    }
  });
}

function showTracks() {
  if (trackLayer && !tracksVisible) { trackLayer.addTo(map); tracksVisible = true; }
}
function hideTracks() {
  if (trackLayer && tracksVisible) { map.removeLayer(trackLayer); tracksVisible = false; }
}
function toggleTracks() {
  if (tracksVisible) hideTracks(); else showTracks();
  return tracksVisible;
}
function updateTracks(geojsonData) {
  if (typeof geojsonData === 'string') geojsonData = JSON.parse(geojsonData);
  var wasVisible = tracksVisible;
  if (tracksVisible && trackLayer) map.removeLayer(trackLayer);
  loadTracks(geojsonData);
  tracksVisible = false;
  if (wasVisible) showTracks();
}
function clearTracks() {
  if (trackLayer) { map.removeLayer(trackLayer); trackLayer = null; }
  tracksVisible = false;
}

// ══════════════════════════════════════════════
// WAYPOINT DISPLAY (type-based colored markers)
// ══════════════════════════════════════════════
function waypointColor(type) {
  switch(type) {
    case 'trailhead': return '#2ECC40';
    case 'fuel':      return '#FF6B35';
    case 'gate':      return '#FF4136';
    case 'hazard':    return '#FFDC00';
    case 'scenic':    return '#7FDBFF';
    case 'water':     return '#0074D9';
    case 'camp':      return '#3D9970';
    case 'parking':   return '#AAAAAA';
    case 'rally':     return '#F012BE';
    case 'other':     return '#DDDDDD';
    default:          return '#FFFFFF';
  }
}
function waypointSymbol(type) {
  switch(type) {
    case 'trailhead': return 'TH';
    case 'fuel':      return 'F';
    case 'gate':      return 'G';
    case 'hazard':    return '!';
    case 'scenic':    return 'S';
    case 'water':     return 'W';
    case 'camp':      return 'C';
    case 'parking':   return 'P';
    case 'rally':     return 'R';
    case 'other':     return '*';
    default:          return '?';
  }
}

function loadWaypoints(geojsonData) {
  if (waypointLayer) map.removeLayer(waypointLayer);
  waypointLayer = L.geoJSON(geojsonData, {
    pointToLayer: function(feature, latlng) {
      var wType = (feature.properties && feature.properties.wpt_type) || 'other';
      var color = waypointColor(wType);
      var sym = waypointSymbol(wType);
      var icon = L.divIcon({
        className: '',
        html: '<div style="width:22px;height:22px;background:' + color + ';border:2px solid #fff;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:9px;font-weight:bold;color:#000;font-family:monospace;box-shadow:0 0 4px rgba(0,0,0,0.5);">' + sym + '</div>',
        iconSize: [22, 22],
        iconAnchor: [11, 11]
      });
      return L.marker(latlng, { icon: icon });
    },
    onEachFeature: function(feature, layer) {
      var p = feature.properties || {};
      var name = p.name || 'Unnamed Waypoint';
      var wType = p.wpt_type || 'other';
      var popup = '<div style="font-family:monospace;font-size:12px;">' +
        '<b style="color:' + waypointColor(wType) + ';">' + name + '</b><br>' +
        'Type: ' + wType +
        (p.description ? '<br>' + p.description : '') +
        '</div>';
      layer.bindPopup(popup);
    }
  });
}

function showWaypoints() {
  if (waypointLayer && !waypointsVisible) { waypointLayer.addTo(map); waypointsVisible = true; }
}
function hideWaypoints() {
  if (waypointLayer && waypointsVisible) { map.removeLayer(waypointLayer); waypointsVisible = false; }
}
function toggleWaypoints() {
  if (waypointsVisible) hideWaypoints(); else showWaypoints();
  return waypointsVisible;
}
function updateWaypoints(geojsonData) {
  if (typeof geojsonData === 'string') geojsonData = JSON.parse(geojsonData);
  var wasVisible = waypointsVisible;
  if (waypointsVisible && waypointLayer) map.removeLayer(waypointLayer);
  loadWaypoints(geojsonData);
  waypointsVisible = false;
  if (wasVisible) showWaypoints();
}
function clearWaypoints() {
  if (waypointLayer) { map.removeLayer(waypointLayer); waypointLayer = null; }
  waypointsVisible = false;
}

// ══════════════════════════════════════════════
// ROUTE DISPLAY (gold dashed)
// ══════════════════════════════════════════════
function loadRoutes(geojsonData) {
  if (routeLayer) map.removeLayer(routeLayer);
  routeLayer = L.geoJSON(geojsonData, {
    style: function() {
      return { color: '#FFD700', weight: 3, opacity: 0.9, dashArray: '15,5,5,5' };
    },
    onEachFeature: function(feature, layer) {
      var name = (feature.properties && feature.properties.name) || 'Unnamed Route';
      layer.bindPopup('<b style="color:#FFD700;">' + name + '</b>');
    }
  });
}

function showRoutes() {
  if (routeLayer && !routesVisible) { routeLayer.addTo(map); routesVisible = true; }
}
function hideRoutes() {
  if (routeLayer && routesVisible) { map.removeLayer(routeLayer); routesVisible = false; }
}
function toggleRoutes() {
  if (routesVisible) hideRoutes(); else showRoutes();
  return routesVisible;
}
function updateRoutes(geojsonData) {
  if (typeof geojsonData === 'string') geojsonData = JSON.parse(geojsonData);
  var wasVisible = routesVisible;
  if (routesVisible && routeLayer) map.removeLayer(routeLayer);
  loadRoutes(geojsonData);
  routesVisible = false;
  if (wasVisible) showRoutes();
}
function clearRoutes() {
  if (routeLayer) { map.removeLayer(routeLayer); routeLayer = null; }
  routesVisible = false;
}
