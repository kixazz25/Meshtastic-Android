// VECNODATA-2026-10-31 (Fred 08:14: "if we hit empty tiles at a zoom level can we return to the prior zoom and message no tiles
// available below this level?"): when the map settles and EVERY map tile it fetched for the view was NOT HELD (not downloaded, and
// not filled from the internet), it zooms back to the last zoom that had map, or steps out one level at a time, then says
// "No maps downloaded below this zoom here". The server tags each tile X-GT-Held: 1 / 0, so a held-but-empty tile (bare desert)
// never triggers it. Online the internet fill means it never triggers; it is for offline and the Status: Offline mode.
(function () {
  var held = 0, missing = 0, lastGood = null, backing = false, banner = null;
  window.gtTileSeen = function (url, h) {
    if (String(url).indexOf('gt://t/') !== 0) return;
    if (h === '0') missing++; else held++;
  };
  map.on('movestart', function () { held = 0; missing = 0; });
  function note(msg) {
    if (!banner) {
      banner = document.createElement('div');
      banner.style.cssText = 'position:fixed;left:50%;bottom:96px;transform:translateX(-50%);background:rgba(0,0,0,0.78);color:#fff;' +
        'font:15px sans-serif;padding:9px 16px;border-radius:9px;z-index:20;pointer-events:none;display:none;white-space:nowrap';
      document.body.appendChild(banner);
    }
    banner.textContent = msg; banner.style.display = 'block';
    clearTimeout(banner._t); banner._t = setTimeout(function () { banner.style.display = 'none'; }, 5000);
  }
  map.on('idle', function () {
    var z = map.getZoom();
    if (missing > 0 && held === 0) {
      var target = (lastGood != null && lastGood < z - 0.25) ? lastGood : z - 1;
      if (target < 3) { held = 0; missing = 0; return; }   // nothing stored anywhere near -- leave the view alone
      console.log('VECNODATA no stored map at z' + z.toFixed(1) + ' -> z' + target.toFixed(1));
      backing = true; held = 0; missing = 0;
      map.easeTo({ zoom: target, duration: 400 });
      return;
    }
    if (held > 0 || backing) lastGood = z;
    if (backing) { backing = false; note('No maps downloaded below this zoom here'); console.log('VECNODATA stopped at z' + z.toFixed(1)); }
    held = 0; missing = 0;
  });
  console.log('VECNODATA loaded');
})();
