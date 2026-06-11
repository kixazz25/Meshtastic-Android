# Manual update — "Creating a Route" — 2026-06-10

_Target = the CURRENT living manual: **GroupTrack_V25_UserManual_2026-06-05.html** (from the 06-06 docs set). Section 4 → "Creating a Route (early access)". EDIT IN PLACE. This corrects the snap-to-trail status now that it's LIVE + proven on both maps (commits 56713ab1e / 5630fb0b9 / 6b1628f82 + the convoy snap-2 from 06-06)._
_(Note: this is the screen-anchored 06-05 manual, NOT the older 28,050-byte cookbook-draft HTML in assets. Update THIS one — it's the current living manual.)_

## What's now wrong in the manual
The current "Creating a Route" entry says, under **"Coming next (not in this build)"**:
> "Snap-to-trail: points will snap to the nearest trail or track so the route follows real trails instead of straight lines between taps. In this build, points place where you tap (straight segments between them). This is the next feature being built."

That is now OUT OF DATE. Snap-to-trail is LIVE and proven on both the Convoy and Planning maps as of 2026-06-10.

## EDIT 1 — the "Add points" step
CURRENT:
> "Add is on by default (green): tap the map to drop points. A magenta line connects your points in order."

REPLACE WITH:
> "Add is on by default (green): tap the map to drop points. If a trail or track is nearby, the point snaps to it and the magenta route line follows the trail's actual shape between your points — so the route traces real trails instead of cutting straight across. Tap away from any trail to place a free point exactly where you tapped. Tip: zoom in while building for the most reliable snapping (the snap range is fixed, so zoomed far out fewer points catch)."

## EDIT 2 — MOVE snap-to-trail OUT of "Coming next", INTO the live description
DELETE this bullet from "Coming next (not in this build)":
> "Snap-to-trail: points will snap to the nearest trail or track... In this build, points place where you tap (straight segments between them). This is the next feature being built."

The "Coming next" list should now contain only what's actually still coming:
> "Coming next (not in this build):
> - Save In Progress / Resume: save a half-finished route and re-open it later to keep editing (the 'In Progress' entry).
> - Draw and Suggest build methods (placeholders today).
> - Renaming/editing a saved route from the Routes list (route maintenance)."

(NOTE on "Save In Progress / Resume": the resume + rollback REDRAW now trace correctly along trails — that part is done — but the full save-in-progress lifecycle UX still has open pieces, so it's fair to keep it under "coming." If you want, soften to "Save In Progress / Resume — partially available; full flow coming.")

## EDIT 3 — add a short Notes line after the steps
ADD:
> "Notes: the route traces the underlying trail/track geometry between snapped points. Undo keeps the line traced; rolling back to a saved draft or resuming an in-progress route also redraws the route traced along the trails."

## Leave unchanged
- "Route building works on both the convoy map and the planning map." — still true, now MORE true (snap works on both).
- Route maintenance paragraph (SEL/Edit, not the toolbar) — unchanged.
- The Section 1/2 "Building a route → see Section 4" cross-references — unchanged.

## Honest non-claims
- Screenshots: not addressed here.
- sliceLine whole-trail explosion (internal bug) — do NOT document as user behavior.
