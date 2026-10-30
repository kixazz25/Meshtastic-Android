// VECCOMPASS-2026-10-30 (Fred 17:36): the compass beside MENU. The page tells the app the map's bearing as it turns
// (Android.onBearing, at most ~12 a second, and once more when the turn ends); the app turns its compass needle.
// gtNorthUp() turns the map back to north (the compass tap when My dir is off).
(function () {
  var last = -999, t = 0;
  function push() {
    var b = map.getBearing();
    if (Math.abs(b - last) < 1) return;
    last = b;
    if (window.Android && Android.onBearing) Android.onBearing(b);
  }
  map.on('rotate', function () { var n = Date.now(); if (n - t > 80) { t = n; push(); } });
  map.on('moveend', push);
  map.on('load', push);
  window.gtNorthUp = function () { gtWhenReady(function () { map.rotateTo(0, { duration: 500 }); }); };
  console.log('VECCOMPASS loaded');
})();
