# Release Notes update — snap-to-trail now live — 2026-06-10

_Target = the CURRENT living release notes: **GroupTrack_V25_ReleaseNotes_2026-06-05.html** (from the 06-06 docs set). EDIT IN PLACE. Snap-to-trail is now LIVE + proven on both maps (commits 56713ab1e / 5630fb0b9 / 6b1628f82 + convoy snap-2 from 06-06)._
_(There is also a separate grouptrack_release_notes.html in assets, 11,290 bytes, that carries the tester upgrade-hazard callout. If both ship, keep them consistent — this snap edit applies wherever the "Coming next → snap-to-trail" language appears.)_

## EDIT 1 — under "Route planning — build and save a route (early access)"
The current bullet:
> "Add is on by default — tap the map to drop points. A magenta line connects them."

REPLACE WITH:
> "Add is on by default — tap the map to drop points. Points near a trail or track snap to it, and the magenta route line follows the trail's real shape between points (it traces the trail, not a straight line). Tap away from trails to place a free point where you tap. Zoom in while building for the most reliable snapping."

## EDIT 2 — MOVE snap-to-trail out of "Coming next"
DELETE from "Coming next":
> "Snap-to-trail: route points will snap to the nearest trail or track so a route follows real trails instead of straight lines between taps. In this build, points place where you tap (straight segments). This is the feature being built next."

"Coming next" should now read:
> "Coming next:
> - Save In Progress / Resume: save a half-finished route and re-open it later to keep editing.
> - Draw and Suggest route methods.
> - Renaming/editing a saved route from the Routes list (route maintenance)."

## EDIT 3 — add to "Changed" (optional, tester-facing highlight)
ADD:
> "Routes now follow trails: when you build a route near trails or tracks, the line traces the trail's actual shape instead of cutting straight across — on both the convoy and planning maps."

## Honest scope
- This is a tester-facing accuracy fix; it doesn't change the upgrade-hazard callout (keep that as-is in the assets release notes).
- Don't claim Save-In-Progress full lifecycle (still partial). Don't claim Draw/Suggest.
