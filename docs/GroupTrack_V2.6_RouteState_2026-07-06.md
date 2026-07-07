# GroupTrack V2.6 — Route-Planning State Persistence & Clean Teardown
**Date raised:** 2026-07-06 (this morning) · **Type:** V2.6 no-radio open item · **Status:** root cause located; fix design not yet locked (one source-check remains)

> Companion to the V2.6 milestone checklist. Root cause located 07-06 via fresh xref (recommit docs; navigation_xref regenerated 07-06 11:32). This is the route-recording / route-state issue.

---

## Problem (observed)
The route **nav box disappears**, and route-planning states don't change as they should. Route-planning state is **not reliably persisted** across navigation, **nor reliably torn down** on exit.

**Two repro triggers** — with a route open:
- (a) open/touch another item, or
- (b) close the artifacts panel.

**Two failure modes:**
1. the route **disappears** from the map entirely; or
2. the route **stays drawn but goes dead/inert** — geometry still visible, controls gone/unresponsive.

---

## Root cause (located 07-06 from xref — corpus-grounded, not memory)
Route mode is held in **`var routeMode by remember { mutableStateOf(false) }`** — **ephemeral Compose state** — at **two independent copies**:
- `ConvoyMapViewerScreen.kt:129`
- `ConvoyScreen.kt:197`

Both drive **one shared `window.__routeMode`** in `convoy_map.html`.

Teardown is **~8 scattered hardcoded calls per screen** of `setRouteMode(false); clearBuildLine();` at specific UI actions:
- ConvoyMapViewerScreen: 955 / 1044 / 1075 / 1112 (arm-on at 996/1020; resume at 1154)
- ConvoyScreen: 1608 / 1684 / 1716 / 1743 (arm-on at 1648/1671; resume at 1775)

There is **no single teardown funnel.**

**This one fact explains both failure modes.** Touching another item / closing the artifacts panel recomposes or orphans the `remember` boolean → the Kotlin `routeMode` resets/desyncs, but the JS side gets **no matching** `setRouteMode(false); clearBuildLine()`.
- **Disappear** = a path fired `clearBuildLine` (or a redraw overlaid the route without preserving vertices).
- **Stays-but-dead** = Kotlin `routeMode` went false so the toolbar/controls detach, but JS still shows the drawn line → visible geometry, no controls, inert.

---

## Intent — Fred's 3 requirements
1. **Persist** route-planning state in the **map JSON** so navigating away and back restores the proper state (survives recomposition/nav, which `remember` does not).
2. **States unique per map** — Planning (`grouptrack_map.html`) vs Convoy (`convoy_map.html`) must not collide on the shared JS flag; the two separate `routeMode` booleans need per-map identity.
3. **Every exit point ends the state** — save, discard, navigate away, back, mode-switch, backgrounding, **item-tap, panel-close** — all force route state to end, via **one teardown funnel** every exit routes through (replacing the ~8 scattered manual teardowns).

**Key principle:** persist **unless/until** closed by an exit method — persist across navigation, **never past an exit.**

The diagnosis maps to these three requirements exactly: (1) replace ephemeral `remember` with state saved to the map's JSON; (2) give the two booleans per-map identity so Planning and Convoy don't collide; (3) one teardown funnel so panel-close + item-tap (which currently make **no** teardown call) can't strand JS route-mode.

---

## Known touchpoints (xref-confirmed)
- `setRouteMode` (JS, `convoy_map.html`) toggled from **both** screens.
- `window.__routeMode` gate `if(window.__routeMode)return;` at `convoy_map.html:495` (this is the BUG A popup-suppression gate — must stay mode-scoped, only while `__routeMode==true`; a prior broad leak killed the normal artifact-toggle redraw).
- `RouteManager` holds the build logic: snap / addVertex / undoVertex / clearRoute / routeVertices, buildWktAndBbox, insertRoute.
- JS build fns: drawBuildLine / clearBuildLine / updateRoutes / showRoutes.
- `onTrackTap` handlers: `ConvoyMapViewerScreen:515`, `ConvoyScreen:645` & `895`.

---

## Remaining source-check before locking the fix
The xref indexes definitions + bridge calls, **not full handler bodies.** Before locking the fix, read the actual `.kt` for:
- the artifact-panel-close handler (`onDismiss` / `onClose`), and
- the `onTrackTap` bodies (`ConvoyMapViewerScreen:515`, `ConvoyScreen:645` / `895`)

…and **confirm they lack a `setRouteMode(false)` teardown call** (the diagnosis predicts they do; verify before building).

Then the design is: **persist route state to the map JSON (per-map keyed) + a single teardown funnel every exit routes through** (including panel-close + item-tap). The fix shape follows from the root cause; lock it only after the two-handler read confirms the prediction.

---
*Living doc — mirror of `/areas/grouptrack-route-state.md` as of 2026-07-06.*
