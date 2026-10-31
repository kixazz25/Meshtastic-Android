// VECHEADING-2026-10-30 (2.7e): the CARTS on the vector map + THE N UP / MY DIR BUTTON.
// Carts: the same look as the satellite map (my cart = white circle with a star, others = small squares, label above, red when off track,
// the silent lead blinks at its estimate, a grey "last heard" dot, the lead's blue gap dots). A tap on a cart -> Android.onMarkerTapped.
// Heading-up: gtSetHeadingUp(true) turns the map so my direction of travel points up; the app pushes my cart's course every tick
// (gtMyHeading). Below 2 mph the course is noise -- the map holds the last good direction. gtSetHeadingUp(false) turns north back up.
var gtCarts = {};
function gtCartHtml(color, label, isMine, offTrack, blink) {
  var sz = isMine ? 28 : 14;
  var dot = isMine
    ? "<div style='width:" + sz + "px;height:" + sz + "px;background:white;border-radius:50%;border:2px solid " + color + ";box-shadow:0 0 6px rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;box-sizing:border-box;'><span style='font-size:18px;line-height:1;color:" + color + ";'>&#9733;</span></div>"
    : "<div style='width:" + sz + "px;height:" + sz + "px;background:" + color + ";border:2px solid white;border-radius:3px;box-shadow:0 0 4px rgba(0,0,0,0.5);box-sizing:border-box;'></div>";
  if (blink) { gtBlinkCss(); dot = "<div class='gt-blink'>" + dot + "</div>"; }
  var lab = "<div style='position:absolute;left:50%;bottom:" + (sz + 6) + "px;transform:translateX(-50%);white-space:nowrap;background:#fff;" +
    "border-radius:3px;padding:1px 5px;font:12px sans-serif;box-shadow:0 1px 3px rgba(0,0,0,0.4);" +
    (offTrack ? "color:red;font-weight:bold;" : "color:#222;") + "'>" + label + "</div>";
  return { html: "<div style='position:relative;width:" + sz + "px;height:" + sz + "px;'>" + dot + lab + "</div>", sz: sz };
}
function gtBlinkCss() {
  if (document.getElementById('gtBlinkCss')) return;
  var st = document.createElement('style'); st.id = 'gtBlinkCss';
  st.textContent = '@keyframes gtBlink{0%,100%{opacity:1}50%{opacity:0.15}} .gt-blink{animation:gtBlink 1s infinite}';
  document.head.appendChild(st);
}
window.clearMarkers = function () { for (var id in gtCarts) gtCarts[id].remove(); gtCarts = {}; };
window.removeMarker = function (id) { if (gtCarts[id]) { gtCarts[id].remove(); delete gtCarts[id]; } };
window.addMarker = function (id, lat, lon, color, label, isMine, offTrack, blink) {
  window.removeMarker(id);
  var c = gtCartHtml(color, label, isMine, offTrack, blink);
  var el = document.createElement('div'); el.innerHTML = c.html;
  el.addEventListener('click', function (ev) {
    ev.stopPropagation(); gtMarkerTapAt = Date.now();
    if (window.Android && Android.onMarkerTapped) Android.onMarkerTapped(id);
  });
  gtCarts[id] = new maplibregl.Marker({ element: el, anchor: 'center', opacityWhenCovered: '1' /* VECMARKER3D-2026-10-31: visible in 3D */ }).setLngLat([lon, lat]).addTo(map);
};
window.findNearestMarker = function (x, y) {
  var best = '', bd = Infinity;
  for (var id in gtCarts) {
    var p = map.project(gtCarts[id].getLngLat()), dx = p.x - x, dy = p.y - y, d = Math.sqrt(dx * dx + dy * dy);
    if (d < bd) { bd = d; best = id; }
  }
  return bd < 60 ? best : '';
};
var gtLastHeard = null;
window.setLastHeard = function (lat, lon) {
  if (gtLastHeard) { gtLastHeard.setLngLat([lon, lat]); return; }
  var el = document.createElement('div');
  el.innerHTML = "<div style='position:relative;width:10px;height:10px;'><div style='width:10px;height:10px;background:#888;border:2px solid white;border-radius:50%;box-shadow:0 0 3px rgba(0,0,0,0.6);box-sizing:border-box;'></div>" +
    "<div style='position:absolute;left:50%;top:14px;transform:translateX(-50%);white-space:nowrap;background:#fff;border-radius:3px;padding:1px 5px;font:11px sans-serif;color:#222;box-shadow:0 1px 3px rgba(0,0,0,0.4);'>last heard</div></div>";
  el.style.pointerEvents = 'none';
  gtLastHeard = new maplibregl.Marker({ element: el, anchor: 'center', opacityWhenCovered: '1' /* VECMARKER3D-2026-10-31: visible in 3D */ }).setLngLat([lon, lat]).addTo(map);
};
window.clearLastHeard = function () { if (gtLastHeard) { gtLastHeard.remove(); gtLastHeard = null; } };
window.setLeadDots = function (arr) {   // [[lat, lon], ...] -- dots, never joined
  var fc = { type: 'FeatureCollection', features: (arr || []).map(function (p) {
    return { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [p[1], p[0]] } }; }) };
  gtWhenReady(function () {
    if (!map.getSource('gt-leaddots')) {
      map.addSource('gt-leaddots', { type: 'geojson', data: fc });
      map.addLayer({ id: 'gt-leaddots', type: 'circle', source: 'gt-leaddots',
        paint: { 'circle-radius': 5, 'circle-color': '#1E88FF', 'circle-stroke-color': '#000000', 'circle-stroke-width': 2 } });
    } else map.getSource('gt-leaddots').setData(fc);
  });
};
// ---- heading-up ---------------------------------------------------------------------------------------------------------
var gtHeadUp = false, gtLastHdg = null, gtHdgLog = 0;
function gtTurnTo(b) {
  gtWhenReady(function () {
    var cur = map.getBearing(), d = ((b - cur) % 360 + 540) % 360 - 180;
    if (Math.abs(d) < 3) return;   // no jitter
    map.rotateTo(b, { duration: 800 });
  });
}
window.gtSetHeadingUp = function (on) {
  gtHeadUp = !!on;
  console.log('VECHEADING ' + (gtHeadUp ? 'MY DIR' : 'N UP') + ' last heading ' + gtLastHdg);
  if (gtHeadUp) { if (gtLastHdg != null) gtTurnTo(gtLastHdg); }
  else gtTurnTo(0);
};
window.gtMyHeading = function (hdg, mph) {
  if (mph >= 2 && isFinite(hdg)) gtLastHdg = hdg;   // stopped -> hold the last good direction
  if (Date.now() - gtHdgLog > 15000) { gtHdgLog = Date.now(); console.log('VECHEADING my course ' + hdg + ' at ' + mph + ' mph (held ' + gtLastHdg + ')'); }   // diagnostic
  if (gtHeadUp && gtLastHdg != null) gtTurnTo(gtLastHdg);
};
console.log('VECHEADING loaded');
