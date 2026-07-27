# ADDENDUM — Convoy-map waypoint drop + QUEUES button (captured 2026-06-01)

Two PORT tasks (port existing planning-map wiring to the convoy map — NOT new code). Both are good "build-window" tasks: research + construct the patch while a build runs, apply later. They touch the convoy map HTML/screen, a DIFFERENT file from the recap fix (TrailImporter.kt), so they batch cleanly into a future build without ambiguity if a build fails.

## 1. Convoy-map waypoint drop (route-planning-adjacent)
- **Gap:** waypoint ADD works on the PLANNING map but NOT the convoy map. Currently you can only drop waypoints on the planning map. Add waypoint-drop to the convoy map.
- **It's a PORT, not new code:** the planning map already wires long-press → waypoint drop → insert → draw. Mirror that wiring onto the convoy map.
- **Research first (build-window work):**
  - `grep -rn "longpress\|long_press\|longPress\|contextmenu\|dblclick\|dropWaypoint\|addWaypoint\|insertWaypoint" app/src/main/assets/convoy_map.html`
  - `ls app/src/main/assets/*.html` then grep the planning map's filename for the same terms
  - KEY thing to surface: how the planning map signals a waypoint-drop back to Kotlin (the JS→Android bridge call, e.g. `Android.something()` / a JSBridge interface). That bridge is what the convoy map is missing or not wiring. The convoy map had earlier JS binding errors (`addMarker`/`clearMarkers` undefined), so part of the port may be ensuring the convoy map exposes the same JS interface methods the planning map relies on.
- **Related carried bug (Section F):** long-press waypoint drop must fire ONLY on empty map, not on node markers — fold this in so the convoy version is correct from the start.

## 2. QUEUES button on convoy (Section F carried bug)
- **Gap:** the QUEUES button on the convoy map is DEAD.
- **Also a PORT:** port the planning-map QUEUES wiring to convoy — do NOT build new. Lands in BOTH convoy interfaces (convoy line 494 and convoy line 622).
- **Placement constraints (from Section F):** same row as the +/- zoom and north indicator; watch for double-accordion; don't cover the NET/LOCAL controls.
- **Research first:** find the planning-map QUEUES handler and the two convoy insertion points (494, 622); diff to see what wiring convoy is missing.

## Why these are good build-window tasks
Both are investigation-heavy ports with no device needed and no repo changes during research. Drafting the anchored, self-tested patches while a build runs is the efficient move (the lesson from today: use the ~30-min build windows to construct the NEXT independent patch). Apply/batch them in a later build — different file from the recap work, so failures stay attributable.
