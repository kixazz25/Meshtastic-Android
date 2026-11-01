// VECWATCH-2026-10-31 (Fred 08:33-08:35: it cannot be repeated on demand; "my guess is ... we are waiting for data to render"):
// a HEARTBEAT every 5 s so the next freeze explains itself -- map and elevation requests started / finished / still OUT (waiting
// for data?), whether the map says it is loaded, zoom (map's own; the app's Z is +1), tilt, 3D, the drawing size and pixel ratio,
// tiles held in memory, JS memory. Plus a line for every request that takes over 2 s, and a lost-WebGL line. Changes nothing.
// VECWATCH2-2026-10-31 (Fred 18:39-18:48: scrolling in 3D seizes -- 18:36:37 no touch reached the app for ~10 min). Still changes
// nothing; it now also logs:
//   - ABANDONED requests (MapLibre cancels tiles it no longer needs): counted as "ab", no longer left "out" -- the 18:36 "10 + 10
//     stuck" was most likely this counter missing aborts;
//   - while anything is out over 5 s: the stuck tiles, up to 5, each beat (VECWATCH STUCK);
//   - touches on the map page each beat: start / move / end / cancel (did the fingers reach the page?), and every cancel at once;
//   - map moves each beat (movestart / moveend);
//   - the page thread stalling: a 100 ms tick late by over 400 ms -> VECWATCH STALL n ms (the page too busy to take a touch);
//   - cart updates (addMarker): count and the slowest each beat.
(function () {
  var st = { t: { start: 0, done: 0, fail: 0, ab: 0 }, d: { start: 0, done: 0, fail: 0, ab: 0 } }, open = {};
  window.gtReqStart = function (url) {
    var k = String(url).indexOf('gt://d/') === 0 ? 'd' : (String(url).indexOf('gt://t/') === 0 ? 't' : null);
    if (!k) return null; st[k].start++; var id = k + ':' + url + ':' + Date.now(); open[id] = Date.now(); return { k: k, id: id };
  };
  // why: undefined (done / failed as before) or 'abort' (MapLibre cancelled it) -- once per request whatever the path
  window.gtReqEnd = function (h, ok, why) {
    if (!h || h.ended) return; h.ended = true;
    st[h.k][why === 'abort' ? 'ab' : (ok ? 'done' : 'fail')]++;
    var ms = Date.now() - (open[h.id] || Date.now()); delete open[h.id];
    if (ms > 2000) console.log('VECWATCH slow ' + h.k + ' ' + ms + ' ms ' + (why || (ok ? 'done' : 'failed')) + ' ' + h.id.split(':').slice(1, -1).join(':'));
  };
  function tilesHeld() {
    try { var sc = map.style.sourceCaches || {}, n = 0; for (var s in sc) n += Object.keys(sc[s]._tiles || {}).length; return n; } catch (e) { return -1; }
  }
  // touches on the page, moves, stalls, cart updates -- counted between beats
  var tc = { s: 0, m: 0, e: 0, c: 0 }, mv = { s: 0, e: 0 }, stallMax = 0, carts = { n: 0, max: 0 };
  var el = map.getCanvasContainer();
  el.addEventListener('touchstart', function () { tc.s++; }, { capture: true, passive: true });
  el.addEventListener('touchmove', function () { tc.m++; }, { capture: true, passive: true });
  el.addEventListener('touchend', function () { tc.e++; }, { capture: true, passive: true });
  el.addEventListener('touchcancel', function (ev) { tc.c++; console.log('VECWATCH TOUCHCANCEL fingers ' + (ev.touches ? ev.touches.length : '?')); }, { capture: true, passive: true });
  map.on('movestart', function () { mv.s++; }); map.on('moveend', function () { mv.e++; });
  var tick = Date.now();
  setInterval(function () {
    var late = Date.now() - tick - 100; tick = Date.now();
    if (late > 400) { console.log('VECWATCH STALL ' + late + ' ms (page thread busy)'); stallMax = Math.max(stallMax, late); }
  }, 100);
  if (typeof window.addMarker === 'function') {
    var am = window.addMarker;
    window.addMarker = function () { var t0 = Date.now(); try { return am.apply(this, arguments); } finally { var d = Date.now() - t0; carts.n++; if (d > carts.max) carts.max = d; } };
  }
  setInterval(function () {
    try {
      var cv = map.getCanvas(), outT = 0, outD = 0, oldest = 0, now = Date.now(), stuck = [];
      for (var id in open) {
        if (id[0] === 't') outT++; else outD++;
        var age = now - open[id]; oldest = Math.max(oldest, age);
        if (age > 5000) stuck.push(id.split(':').slice(1, -1).join(':').replace('gt://', '') + ' ' + Math.round(age / 1000) + 's');
      }
      var mem = (performance && performance.memory) ? Math.round(performance.memory.usedJSHeapSize / 1048576) + ' MB' : '?';
      console.log('VECWATCH z' + map.getZoom().toFixed(1) + ' pitch ' + map.getPitch().toFixed(0) + ' 3D ' + (!!map.getTerrain()) +
        ' loaded ' + map.loaded() + ' tilesLoaded ' + map.areTilesLoaded() +
        ' | map req ' + st.t.start + '/' + st.t.done + '/' + st.t.fail + ' ab ' + st.t.ab + ' out ' + outT +
        ' | elev req ' + st.d.start + '/' + st.d.done + '/' + st.d.fail + ' ab ' + st.d.ab + ' out ' + outD + (oldest ? ' oldest ' + oldest + ' ms' : '') +
        ' | touch ' + tc.s + '/' + tc.m + '/' + tc.e + '/' + tc.c + ' moves ' + mv.s + '/' + mv.e + (stallMax ? ' stall ' + stallMax + ' ms' : '') +
        ' | carts ' + carts.n + ' max ' + carts.max + ' ms' +
        ' | canvas ' + cv.width + 'x' + cv.height + ' ratio ' + window.devicePixelRatio + ' | tiles in memory ' + tilesHeld() + ' | js ' + mem);
      if (stuck.length) console.log('VECWATCH STUCK ' + stuck.length + ': ' + stuck.slice(0, 5).join(', '));
      tc = { s: 0, m: 0, e: 0, c: 0 }; mv = { s: 0, e: 0 }; stallMax = 0; carts = { n: 0, max: 0 };
    } catch (e) { console.log('VECWATCH failed ' + e); }
  }, 5000);
  var cv = map.getCanvas();
  cv.addEventListener('webglcontextlost', function () { console.log('VECWATCH WEBGL CONTEXT LOST'); }, false);
  cv.addEventListener('webglcontextrestored', function () { console.log('VECWATCH webgl context restored'); }, false);
  console.log('VECWATCH2 loaded (aborts, stuck tiles, touches, moves, stalls, cart updates)');
})();
