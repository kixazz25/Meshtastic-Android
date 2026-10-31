// VECWATCH-2026-10-31 (Fred 08:33-08:35: it cannot be repeated on demand; "my guess is ... we are waiting for data to render"):
// a HEARTBEAT every 5 s so the next freeze explains itself -- map and elevation requests started / finished / still OUT (waiting
// for data?), whether the map says it is loaded, zoom (map's own; the app's Z is +1), tilt, 3D, the drawing size and pixel ratio,
// tiles held in memory, JS memory. Plus a line for every request that takes over 2 s, and a lost-WebGL line. Changes nothing.
(function () {
  var st = { t: { start: 0, done: 0, fail: 0 }, d: { start: 0, done: 0, fail: 0 } }, open = {};
  window.gtReqStart = function (url) {
    var k = String(url).indexOf('gt://d/') === 0 ? 'd' : (String(url).indexOf('gt://t/') === 0 ? 't' : null);
    if (!k) return null; st[k].start++; var id = k + ':' + url + ':' + Date.now(); open[id] = Date.now(); return { k: k, id: id };
  };
  window.gtReqEnd = function (h, ok) {
    if (!h) return; st[h.k][ok ? 'done' : 'fail']++;
    var ms = Date.now() - (open[h.id] || Date.now()); delete open[h.id];
    if (ms > 2000) console.log('VECWATCH slow ' + h.k + ' ' + ms + ' ms ' + h.id.split(':').slice(1, -1).join(':'));
  };
  function tilesHeld() {
    try { var sc = map.style.sourceCaches || {}, n = 0; for (var s in sc) n += Object.keys(sc[s]._tiles || {}).length; return n; } catch (e) { return -1; }
  }
  setInterval(function () {
    try {
      var cv = map.getCanvas(), outT = 0, outD = 0, oldest = 0, now = Date.now();
      for (var id in open) { if (id[0] === 't') outT++; else outD++; oldest = Math.max(oldest, now - open[id]); }
      var mem = (performance && performance.memory) ? Math.round(performance.memory.usedJSHeapSize / 1048576) + ' MB' : '?';
      console.log('VECWATCH z' + map.getZoom().toFixed(1) + ' pitch ' + map.getPitch().toFixed(0) + ' 3D ' + (!!map.getTerrain()) +
        ' loaded ' + map.loaded() + ' tilesLoaded ' + map.areTilesLoaded() +
        ' | map req ' + st.t.start + '/' + st.t.done + '/' + st.t.fail + ' out ' + outT +
        ' | elev req ' + st.d.start + '/' + st.d.done + '/' + st.d.fail + ' out ' + outD + (oldest ? ' oldest ' + oldest + ' ms' : '') +
        ' | canvas ' + cv.width + 'x' + cv.height + ' ratio ' + window.devicePixelRatio + ' | tiles in memory ' + tilesHeld() + ' | js ' + mem);
    } catch (e) { console.log('VECWATCH failed ' + e); }
  }, 5000);
  var cv = map.getCanvas();
  cv.addEventListener('webglcontextlost', function () { console.log('VECWATCH WEBGL CONTEXT LOST'); }, false);
  cv.addEventListener('webglcontextrestored', function () { console.log('VECWATCH webgl context restored'); }, false);
  console.log('VECWATCH loaded');
})();
