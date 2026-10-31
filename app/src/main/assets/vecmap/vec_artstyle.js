// VECARTSTYLE-2026-10-30 (Fred 17:49): MAP KEY's "My tracks" (__track) and "My routes" (__route) rows now style the map.
// Before, tracks were drawn in a fixed green and routes in a fixed magenta (on the satellite map too) -- the key changed, the map
// did not. setTrailStyles still restyles the trail categories; this adds the two rows of yours: colour, width and dash,
// the arrows in the same colour. A row the rider never changed keeps the drawn default.
(function () {
  var base = window.setTrailStyles;
  function dash(d, w) {
    if (!d) return null;
    var a = String(d).split(',').map(Number).filter(function (x) { return isFinite(x) && x > 0; });
    return a.length ? a.map(function (x) { return x / Math.max(1, w); }) : null;   // MapLibre dashes are in line widths
  }
  function apply() {
    gtWhenReady(function () {
      [['__track', 'gt-tracks', 'gt-tracks-arrows'], ['__route', 'gt-routes', 'gt-routes-arrows']].forEach(function (r) {
        var st = TRAIL_STYLE[r[0]]; if (!st) return;
        map.setPaintProperty(r[1], 'line-color', st.c);
        map.setPaintProperty(r[1], 'line-width', st.w);
        map.setPaintProperty(r[1], 'line-dasharray', dash(st.d, st.w));   // null = solid
        map.setPaintProperty(r[2], 'text-color', st.c);
        console.log('VECARTSTYLE ' + r[0] + ' ' + st.c + ' w' + st.w + ' d' + st.d);
      });
    });
  }
  window.setTrailStyles = function (json) { base(json); apply(); };
  console.log('VECARTSTYLE loaded');
})();
